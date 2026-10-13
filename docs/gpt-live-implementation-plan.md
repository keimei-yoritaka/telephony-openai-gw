# GPT-Live追加の要件・実装計画

作成・更新日: 2026-10-13（日本時間）

状態: ユーザー承認済み。delegation・業務tool連携を除外して実装。オフライン検証済み、実API・実通話・RHEL実機検証は未実施。

## 承認された範囲

日本語の電話応対で、相づち、言い直し、話しながら聞く対話を試せるようにGPT-Liveを追加する。スロットごとの`openai.backend: realtime / live`で既存Realtimeと比較する。

ユーザーから「delegationと業務tool連携は不要」と指定されたため、Responses backend設定、backend model、業務tool、検索tool、委譲処理の実行は追加しない。GPT-Liveに会話だけで応対する方針を設定する。API仕様ではdelegationを省略してもclientモードになるが、アプリケーションは委譲先を接続しない。想定外の委譲通知には、この窓口では照会や操作を実行できないことだけを返す。

Java 21、PJSUA2、PCMU/G.722、固定スロット、通話別queueを維持する。backend未指定はRealtimeとし、従来設定を読み込めるようにする。SIP直結、録音保存、fork、Realtimeのbarge-in改善は今回の対象外。

## 構成と変更対象

| 対象 | 実装内容 |
| --- | --- |
| `GatewayConfig` / `GatewayConfigLoader` | backend別の設定・検証。Liveのmodel、voice、開始bufferを追加 |
| `VoiceSessionManager` | 旧`RealtimeClient`を共通化。backend選択、通話worker、再接続、開始中の通話終了処理 |
| `VoiceSession` | 開始、PCM入力、挨拶、idle判定、終了の共通interface |
| `RealtimeSession` | 共通interfaceに対応。既存Realtimeの通信・VADを維持 |
| `LiveSession` | primary WebSocket、開始確認、音声、挨拶、transcript、usage、終了確認 |
| `LiveAudioDecoder` | 24kHz PCMのdelta境界を跨いでsample・20ms frameを保持し、通話sample rateへ変換 |
| `LiveTranscriptAssembler` | caller / assistantを独立して表示segmentへ集約 |
| `AudioBridge` / `Pjsua2AudioBridgePort` | Liveの入力継続、開始buffer、再生queue計測、終了後のqueue再生成防止 |
| monitor Java classes / `app.js` | segment snapshotの更新、履歴との統合、選択変更時の競合防止 |
| build / scripts | Gson 2.13.2を追加。直接javacする経路はchecksum付き取得scriptで再現 |
| 文書・設定例・draw.io | 現行仕様と操作手順を同じ変更単位で更新 |

## Live設定

既存の`session.<slotId>`セクションとドット付きキー形式を使う。

```yaml
session.session-2:
  openai.backend: live
  openai.apiKey: ${OPENAI_API_KEY}
  openai.liveModel: gpt-live-1
  openai.liveVoice: marin
  openai.liveStartBufferFrames: 2
  openai.transcriptLoggingEnabled: true
  bot.systemInstructions: "日本語で自然な電話応対をしてください。短い相づちを交え、訂正や言い直しを聞いてください。外部委譲や業務ツールは使わず、会話だけで応対してください。"
  bot.initialGreeting: "こちらはGPT-Live電話受付です。ご用件をお話しください。"
```

これは追加項目の抜粋であり、完全な設定例は`config/gateway.live.example.yaml`に置く。session-1はRealtime、session-2はLiveで、両方のSIP backendはpjsua2。接続先とcredentialはplaceholderを実行設定側で変更する。

Liveの初期値は`gpt-live-1`、`marin`、2 frame。Realtime専用のVAD・maxOutputTokens・入力transcription modelはLiveへ送らず、必須にしない。未知のvoice等のAPI互換性は接続先の検証も必要。

## 接続と音声

`wss://api.openai.com/v1/live/sessions`へ接続し、`session.start`にmodel、instructions、mono PCM16LE / 24kHz、voice、`store: false`を指定する。`session.started`まで入力は通話別queueで保持し、確認後に転送する。

入力は`session.input_audio.append`で継続送信する。LiveではAI送話中もユーザー音声を送る。`dropInputAudioWhileAssistantSpeaking`と従来barge-in判定はRealtimeスロットだけに適用する。

出力は`session.output_audio.delta`を順序通り受け、20msの24kHz PCMとして組み立ててから8kHz/16kHzへ変換する。60ms以上deltaが来ない場合は端数frameを無音で埋めて送り、奇数byteの端数は次のdeltaへ保持する。これは発話完了判定ではない。

Liveの再生開始bufferは既定2 frame（40ms）。最後のdelta受信から100ms以上経過した場合は短い残りframeも再生する。Realtimeは従来の8 frameと生成完了通知を使用する。

APIに発話ごとの音声終了通知やprimary WebSocketの音声時刻があるとは仮定しない。RTPで実際に消費したframe、queue滞留時間、queueが空になった時点、PCM音声活動の目安をログで分ける。受信queueに残る音声によって割り込み後の送話停止が遅れる可能性は、実通話で検証する。ユーザーtranscriptが届いただけで出力を全破棄する処理は入れない。

初回挨拶は`session.started`後に`session.instructions.append`で一度だけ要求する。音声入力は挨拶中も継続する。

## モニター・再接続・終了

transcript deltaの空白・繰り返しを保持し、話者別にsnapshotへ集約する。APIのitem IDを捏造せず、表示用segment IDとsource event ID / start_ms / end_msを保存する。表示区切りは1秒超の話者別gap、4秒以上のsegment時間幅、512文字を超える追加を目安とする。APIの確定発話とは扱わず`final: false`を維持する。

Hubの履歴にはsegmentごとの最新snapshotを保持し、SSEにも同じsnapshotを送る。UIは同じsegmentの文字を置換し、履歴取得中に受けた更新を統合する。会話ログはLiveのdeltaをJSONでescapeして記録する。

Liveは通話終了まで接続し、入力が15秒来ないという理由だけでは閉じない。通信失敗時は既存の再試行待ちを使い、新しいsessionへ最近の会話テキストを渡す。履歴は最大20件・計2000文字であり、音声文脈や全履歴の完全復元を保証しない。再接続で挨拶を繰り返さず、古い音声queueは破棄する。

通話終了時は入力・再生queueを停止し、別workerで`session.close`を送り、最大15秒待って`session.closed`後にtransportを解放する。開始中に通話が終わった場合も、遅れて接続したsessionを登録しない。終了済み通話のcallbackはqueueを再生成できないようにする。

usageは累積秒の最新値を保持し、足し合わせない。`session.closed`を得られなかった場合は最終usage未確認と記録する。Live warm-upは実行しない。

## 検証と残る確認

オフライン検証は、従来設定とLive設定、PCM境界と8kHz/16kHz変換、同時transcript、snapshot更新、開始前音声拒否、開始中終了、最終usage、終了timeout、送信backlog、古いcallbackを対象とする。UIはsnapshotの重複防止、履歴取得競合、選択変更をDOM stubで確認する。PJSUA2込みのJavaコンパイル、JS/shell構文、draw.io XMLも確認する。

実API接続と日本語の実通話は別途検証する。相づち、言い直し、AI発話中の発話、間、PCMU/G.722、同時2通話、切断、モニター、queue滞留を比較する。GPT-Liveのモデル・voice・API利用権限もその時点で確認する。RHEL実機とブラウザの外観プレビューは未検証。

## 参照した公式仕様

- [Live primary WebSocket](https://developers.openai.com/api/reference/resources/live/primary-websocket)
- [WebSockets](https://developers.openai.com/api/docs/guides/voice-websockets)
- [Managing GPT-Live sessions](https://developers.openai.com/api/docs/guides/live-conversations)
- [Prompting GPT-Live](https://developers.openai.com/api/docs/guides/live-prompting)
- [Server-side controls](https://developers.openai.com/api/docs/guides/voice-server-controls?api=live)
