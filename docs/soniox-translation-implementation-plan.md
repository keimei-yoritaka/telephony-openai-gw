# Soniox translation追加 調査・実装計画

作成日・公式仕様確認日: 2026-11-11（日本時間）

## 状態と確定した用途

2026-11-13に英→日の追加実装依頼を受領し、両方向の片方向翻訳へ拡張した。以下の初期計画の日→英限定事項は初版時点の記録であり、現行仕様は[設計書](system-design.md)と末尾の英→日拡張記録を参照する。

2026-11-11に計画を提示し、2026-11-12にユーザーから「計画に沿って実装」の承認を受領した。計画作成時は本書だけを追加し、承認後にコード・設定例・現行設計書・draw.ioを同じ変更単位で更新した。実装結果と検証範囲は末尾に記録する。

ユーザー回答により、初期用途は**日→英・同じ電話へ翻訳音声返送＋原文／翻訳字幕**と確定した。Sonioxを独立したAgent（スロット別voice backend）として追加する。既存realtime、GPT-Live（live）、OpenAI translationを維持し、動作確認済みのOpenAI translationの日→英処理は変更しない。

delegation・業務tool、別通話への中継、PBX、双方向電話通訳は今回の対象外。Soniox自体のtwo-way対応と、Gatewayの通話間中継機能は別の話として扱う。

## 開始状態と確認範囲

| 項目 | 確認結果 |
| --- | --- |
| 作業ディレクトリ | `/Users/keimei/workspace/Study/022.TelephonyOpenAI_GW` |
| branch | `codex/experimental` |
| HEAD | `4a65071d3e6cb3acce591764d3423287ae89fe5a` |
| origin（fetch / push） | `https://github.com/keimei-yoritaka/telephony-openai-gw.git` |
| 開始時status | 管理対象ファイルに変更なし。未追跡`bin/`のみ |
| ローカル追跡参照との差 | `HEAD...origin/codex/experimental`はahead / behindとも0。今回fetchは未実施のため、GitHub上の最新状態との一致は未確認 |

確認済み: `AGENTS.md`、[引き継ぎ](additional-translation-api-handoff.md)、[README](../README.md)、[現行設計書](system-design.md)、[draw.io](diagrams/telephony-openai-gw-design.drawio)の7ページ、[既存translation計画](realtime-translation-implementation-plan.md)、設定例、RHEL反映手順、デプロイ手順。実装では設定loader / validation、VoiceSession / VoiceSessionManager、TranslationSession、字幕assembler、PCM decoder / resampler、AudioBridge、PJSUA2 callback、モニターevent / Hub / UI、検証scriptと既存検証項目を確認した。

未追跡`bin/`、機密設定、API key、SIP credential、native成果物は変更・stageしない。mainへのマージ、Stable `v0.1.0`の変更、新規リリースは行わない。今回commit / pushは行わず、実装後の履歴管理は指示された範囲で実施する。

## 公式仕様で確認した事項

### 翻訳テキストと翻訳音声の区別

Sonioxのリアルタイム翻訳はSTT APIの機能であり、音声から原文と翻訳テキストを返す。音声返送は**STT＋translation → Soniox TTS → PCM再生**という2本のWebSocketを組み合わせる。翻訳専用の単一接続から音声が返るOpenAI translationとは構成が異なる。別のテキスト翻訳LLMは不要。[公式speech-to-speech構成](https://docs.soniox.com/translation/sts-translation)

`one_way`は検出した発話を単一の目的言語へ翻訳し、`two_way`は指定した2言語間を翻訳する。初期実装は`one_way / target_language: en`のみ。入力言語ヒントを`ja`とするが、ヒントだけで日本語以外を排除できるとは扱わない。原文と訳文は1対1のtoken対応ではなく、訳文には原音声のtimestampが付かない。[翻訳モード・token形式](https://docs.soniox.com/translation/stt-translation)

対応言語一覧に`ja`と`en`がある。英→日や他言語は将来の拡張候補とし、初期の実機合格範囲には含めない。[対応言語](https://docs.soniox.com/translation/supported-languages)

### モデル・認証・入力

- 現在のActive STTモデルは`stt-rt-v6`。v5は2026-12-15廃止予定のため、新規追加の既定値に採用しない。[STTモデル](https://docs.soniox.com/stt/models)
- 現在のActive TTSモデルは`tts-rt-v2`。モデル名は設定可能とし、古いpreview例を転用しない。[TTSモデル](https://docs.soniox.com/tts/models)
- サーバ側Javaから接続時の`Authorization: Bearer <SONIOX_API_KEY>`を使用する。開始JSONの`api_key`は非推奨で、2027-01-15以降それだけでは接続できない。WebSocket open後に認証エラーが通知される場合があるため、openを認証成功の証明にしない。[WebSocket認証](https://docs.soniox.com/authentication/websocket-authentication)
- STT接続先は`wss://stt-rt.soniox.com/transcribe-websocket`。開始JSONの後にbinary音声を送り、終了時は**空のtext message**を送り`finished: true`を待つ。OpenAIの`session.created / session.update / session.close`は送らない。[STT WebSocket](https://docs.soniox.com/api-reference/stt/websocket-api)
- raw PCMはformat・sample rate・channel数を明示する。初期案はmono / `pcm_s16le` / 16kHzへ統一する。GatewayのPCMU入力8kHzは16kHzへ変換し、G.722の内部PCM16kHzはそのまま使用する。8kHz入力の帯域そのものは復元されない。[STT音声形式](https://docs.soniox.com/stt/rt/real-time-transcription#audio-formats)

### 字幕・区切り・TTS

STTは確定token（`is_final: true`）を一度だけ送り、未確定tokenは各responseの内容で置き換える。両者を単純にdelta連結すると重複する。原文と翻訳を分離して集約し、未確定字幕をTTSへ送らない。[リアルタイムtoken処理](https://docs.soniox.com/stt/rt/real-time-transcription)

`enable_endpoint_detection: true`により確定`<end>`を受け取れる。初期案の`max_endpoint_delay_ms`は1000msとし、実機で遅延と認識品質を評価する。これはAPIの既定値ではなくGateway側の提案値。表示用の文字数区切りとAPI endpointを区別する。[endpoint仕様](https://docs.soniox.com/stt/rt/endpoint-detection)

TTS接続先は`wss://tts-rt.soniox.com/tts-websocket`。`stream_id`を付けた設定とテキストを送り、base64 `audio`を受信する。入力終端`text_end`、音声終端`audio_end`、資源解放`terminated`を区別する。1接続最大5 streamだが初期実装では1通話ごとに独立した接続と直列再生を使う。[TTS WebSocket](https://docs.soniox.com/api-reference/tts/websocket-api)

TTS出力はmono raw `pcm_s16le / 24000Hz`を明示し、既存の24kHz→通話sample rateの20ms frame化を再利用する。WAV headerをPCMとして扱わない。[TTS音声形式](https://docs.soniox.com/tts/concepts/audio-formats)

voiceは指定可能。初期設定例は公式例にある`Adrian`、話速は`1.0`とするが、好み・品質は未確認。voiceは設定変更可能とし、試聴・実機評価後に選べる。[voice仕様](https://docs.soniox.com/tts/concepts/voices)

### 接続維持・制限・費用

STTへの音声送信が途切れる場合は`{"type":"keepalive"}`を送る。開始後は20秒以内の受信が必要で、Gateway案は音声未送信時に5秒ごととする。課金は無音を含むstream時間であり、発話時間だけではない。[STT keepalive](https://docs.soniox.com/stt/rt/connection-keepalive)

TTSでは接続keepaliveだけでは個々のstreamのテキスト待ちtimeoutを延ばせない。入力の止まったTTS streamを長く保持しない。正常終端のほかcancel、streamエラー、接続エラーを個別管理する。[TTS生成](https://docs.soniox.com/tts/rt/real-time-generation)、[終了仕様](https://docs.soniox.com/tts/rt/termination)

公開STT制限は100 request/分、同時10 request、1stream最大300分。実アカウント・projectの枠とTTSの枠は導入時に確認する。300分上限は新接続への切替が必要で、切替を無欠落と保証しない。[STT制限](https://docs.soniox.com/stt/rt/limits-and-quotas)

公開価格はtoken課金。STTリアルタイムは入力音声$2 / 100万token、入力・出力テキスト各$4 / 100万token。TTSは入力テキスト$4、出力音声$21.50 / 100万token。翻訳で出力テキストが増え、TTS分も加算されるため、STT単体の約$0.12/時を音声翻訳全体の価格と扱わない。実費はusageとConsoleで評価する。[公式料金](https://soniox.com/pricing)

## 推奨する追加構成

```text
SIP/RTP → PJSUA2 → 通話別inbound queue
  → SonioxTranslationSession → STT / one_way translation
      ├ 原文・翻訳token → SonioxTranscriptAssembler → Monitor / SSE
      └ 確定した英訳 → SonioxTtsClient → PCM24
          → StreamingAudioDecoder → 通話別outbound queue → 同じ電話
```

`VoiceSession`を実装する`soniox/SonioxTranslationSession`を追加し、TTS transportと字幕集約を別クラスにする。通話の1:1対応、スロットごとの最大1通話、UDP/IPv4、PCMU / G.722、queue容量の既存方針を維持する。SonioxをLiveとして登録しない。

### Agent選択と後方互換

新しい共通キー`session.<slotId>.voice.backend`を追加する。選択値は`realtime / live / translation / soniox_translation`。

1. `voice.backend`が明示された場合はその値を使う。
2. 未指定の場合は既存`openai.backend`を使い、それも未指定なら`realtime`。
3. 両キーが明示され値が異なる場合は設定エラーとする。暗黙の上書きを防ぐ。
4. `openai.backend`の従来3値は維持する。Sonioxは`voice.backend`で選び、`soniox.*`に設定する。
5. SonioxスロットではOpenAI API key、bot instructions / greeting、OpenAIモデル・VADを必須にせず送信もしない。OpenAIスロットの従来validationは維持する。

`SessionSlotConfig`に実効backendと`SonioxConfig`を持たせ、loader / factory / runtime config / 起動表示を必要範囲で対応する。`OpenAiConfig`全体への他社設定追加や、全packageの一括改名は行わない。現行独自YAML parserに合わせ、ドット付きの平坦なキーを使う。

承認後に追加する設定例の抜粋（現在のloaderでは未対応）:

```yaml
session.session-4:
  name: Soniox日英翻訳
  voice.backend: soniox_translation
  soniox.apiKey: ${SONIOX_API_KEY}
  soniox.sttModel: stt-rt-v6
  soniox.languageHints: ja
  soniox.translationTargetLanguage: en
  soniox.maxEndpointDelayMs: 1000
  soniox.ttsModel: tts-rt-v2
  soniox.ttsVoice: Adrian
  soniox.ttsSpeed: 1.0
  soniox.transcriptLoggingEnabled: false
```

SIP・Registrationは省略した。完全な`config/gateway.soniox.example.yaml`には既存3方式＋Sonioxの4スロットを用意する。Sonioxでは原文／翻訳字幕と音声返送を有効とし、初期実装で字幕専用切替やtwo-wayの設定を増やさない。目的言語キーは初期検証対象`en`として検証する。他言語への拡張は別途評価する。

### 音声送信・再生

- STTは20ms PCM frameを順序どおりbinary送信する。送信を直列化し、待ち上限100 frame相当（2秒）、送信timeout 5秒を初期案とする。overflowは無制限に蓄積せずsession障害として通知する。
- OpenAI translation固有の24kHz / 200ms無音clockは移植しない。PJSUA2が渡す無音を含むframeを送り、callbackが途切れたらkeepaliveする。
- 初期案は確定英訳を短い単位でTTSへ送り、各単位を`text_end: true`で閉じる。単位は句読点、確定`<end>`、または最初の未送信確定訳から500ms経過を目安にし、512文字を上限とする。これはアプリの音声生成単位でありAPI確定turnではない。token途中の文字列を加工せず、空白・繰り返しを保持する。
- TTSの同時実行は1通話につき1streamとし、`terminated`後に次を開始する。確定訳の待ちは最大10単位・合計4096文字、RTP送話待ちはSonioxに限り10秒を初期上限とする。上限超過は状態表示して当該Soniox sessionを停止・再接続し、黙って無限に遅延を増やさない。値は実機で評価し、変更時は設計書へ反映する。
- 短いTTS単位は遅延とtimeoutを抑える一方、文全体を渡す方式より韻律が途切れる可能性がある。遅延・自然さを実機で比較する。公式のend-to-end遅延保証は確認できておらず、数値SLAは置かない。
- TTSの出力は20ms frame化し、開始bufferは2 frame。各streamの音声終端で残PCMをflushし、stream間で奇数byteを誤結合しない。queue空、生成完了、実再生完了を区別する。
- Soniox送話中も入力を継続し、従来Realtime用barge-in・入力破棄を適用しない。挨拶・自発応対は生成しない。受話音の回り込みはヘッドセット等も含めて実機評価する。Soniox v6のagent text / echo cancellationは今回追加しない。

### 字幕とモニター

`SonioxTranscriptAssembler`は確定済み本文とresponseごとの未確定末尾を原文／訳文別に保持する。未確定が次responseから消えた場合も正しく除去し、確定訳のTTS送信cursorを表示更新から分離する。`<end>`等の制御tokenは字幕にもTTSにも出さない。

既存`TranslationTranscriptAssembler`はdelta連結専用なので流用しない。モニターの`transcriptKind: source / translation / status`、`targetLanguage`、全文snapshot更新は再利用する。訳文の原音声時刻は未知として`-1`にし、原文と訳文の架空の1対1対応を作らない。表示segmentと接続世代でIDを分離し、SonioxにないOpenAI event IDを捏造しない。API確定tokenと表示segment全体の確定を混同しない。

モニターに「Soniox日英翻訳」のslot名、原文、英訳、接続・TTS障害状態を表示する。字幕は生成内容であり電話で再生済みの証明ではない。ログは`soniox_*`イベントとし、API key・音声payloadを出さず、字幕ログは独立キーで既定falseにする。

### lifecycle・障害処理

- STTのWS接続→開始config送信完了から入力を受け付ける。存在しないready eventは待たない。認証・設定エラーは後着でも失敗として処理する。接続timeoutは10秒。
- 通話終了では入力worker・再生を直ちに止め、queue登録を解除する。受理済み入力の送信後に空textを送り、最大15秒で残字幕と`finished`を確認する。終了後の訳文から新規TTSを起動しない。
- 実行中TTSはcancelし`terminated`を最大5秒待ってからsocketを閉じる。終了確認なしは明示し、遅れたcallbackで再生・queue再生成・session再登録をしない。
- 401/403、設定400、残高・予算402は操作が必要な停止状態。無限再試行しない。429、timeout、5xx、通信断は15秒を初期待機に上限60秒のbackoffとjitterで再接続する。API errorの全文や機密値を状態表示へ流さず、分類とrequest IDを残す。
- 再接続は新しいSTT＋TTS世代。過去音声、確定訳、生成音声を再送しない。欠落や未再生の可能性を状態に表示する。TTSだけの障害も初期案では組全体を再開始し、部分再送による二重読みを避ける。
- 無音時もSonioxの健全性・再試行を確認する。現行`recoverTranslation`はOpenAI translationだけなので、Soniox用分岐を追加する。Realtimeのidle closeをSonioxへ適用しない。300分到達も同様に新世代へ移行する。

## 既存構成への影響と変更対象

| 対象 | 変更と抑える影響 |
| --- | --- |
| `config/GatewayConfig.java`, `GatewayConfigLoader.java` | 実効backend・Soniox設定・選択別validation。Sonioxだけの構成でOpenAI keyを要求しない |
| `openai/VoiceSessionManager.java` | Soniox factory、runtime config、再試行・終了分岐。既存3方式のprotocol・cooldownを維持 |
| `soniox/`（新規） | `SonioxTranslationSession`, `SonioxTranscriptAssembler`, `SonioxTtsClient`、送信待ち・世代・stream順序管理 |
| `openai/StreamingAudioDecoder.java`, `Pcm16Resampler.java` | 現在package-private。必要なクラス・methodだけ公開して再利用する。既存処理や固定24kHz契約を変えず、package全移動は避ける |
| `media/AudioBridge.java` | 実効backend登録とSoniox限定の出力滞留検出。queue容量・既存3方式の再生方針は維持 |
| `app/GatewayApp.java`, PJSUA2音声port | backend・model・voiceの表示とSoniox再生ログを正しくする。現行`openai_`固定ログ接頭辞はSoniox分だけ分岐。SIP/RTP native処理は変更不要の見込み |
| `monitor/`, `resources/monitor/app.js` | 既存snapshot形式を原則再利用。必要な言語・状態表示のみ追加。既存UI・履歴競合処理を維持 |
| `src/check/`, `src/test/js/`, `scripts/check-voice.sh`, `build.gradle` | 新しいオフライン検証を既存check経路へ組み込む |
| 設定例・README・運用文書 | 4方式共存、Soniox key、費用・障害・導入条件を日本語で追記 |
| 現行設計書・draw.io | 実装と同時に更新。詳細は次節 |

Java 21 `HttpClient.WebSocket`と既存Gsonを使う方針で、新規SDK・native libraryは不要の見込み。実行設定はユーザーが例を参考に反映する。RHELは実際のsystemd `ExecStart`を確認し、既存SIP設定を保ったままSoniox用slotへ適用する。HTTPS/WSS 443でSTT・TTS両接続先への到達性を確認する。既存スロットを切り替える場合と新SIP accountを増やす場合を手順で分ける。

## 承認後の実装順序と設計更新

1. 設定互換とSoniox選択を追加し、旧設定4種類の読み込みとSoniox単独設定を検証する。
2. Soniox STT／字幕集約、TTS／PCM再生、通話lifecycleと障害処理を追加する。
3. モニター・ログ・4方式混在設定例を整える。
4. 同じ変更単位で`docs/system-design.md`とdraw.ioを更新する。図は既存7ページの該当構成・class・queue・呼シーケンスへSoniox分岐を反映し、`Soniox Translation Sequence`を追加する。OpenAI固有3ページのprotocolは維持する。
5. README、デプロイ・RHEL反映手順、本計画の実装結果・検証記録を更新し、オフライン検証を実施する。
6. 結果と未検証事項を提示し、ユーザー環境で実API・実通話確認へ進む。計画承認はmain merge・タグ変更・リリースの許可を含まない。

## 検証計画・受入条件

### API不要の検証

- 既存4設定例の互換、backend未指定、両選択キーの一致／競合、不正backend、SonioxでOpenAI key不要、OpenAIでSoniox key不要、必須言語・model・voice・range検証。
- 偽WebSocketで接続時Bearer、STT開始JSON→binary順序、fragment JSON、開始後認証エラー、音声8kHz／16kHz、backlog／timeout／overflow／keepaliveを確認。
- 字幕の未確定改訂・削除、確定と未確定の混在、同じ文字列の正当な繰り返し、source／translationの交錯、時刻欠落、制御token、複数endpoint、接続世代を確認。確定訳の一度だけのTTS送信を検証。
- TTSの短文・長文・区切りtimer、送信待ち上限、直列stream順序、可変長PCM・奇数byte境界・短い末尾、8kHz／16kHz再生、audio_end／terminated、TTS異常・cancel・切断を確認。
- 起動中のSIP切断、後着socket、終了drain／timeout、終了後callback、無音時復旧、永続エラー停止、backoff、時間上限、再接続時の再送禁止、4方式の通話間queue分離を確認。
- `scripts/check-config.sh`（既存4例＋新例）、`scripts/check-voice.sh`、`node src/test/js/monitor-check.cjs`、Gradle `check`、PJSUA2込みコンパイルを実施。既存Realtime／Live／OpenAI translation検証は削減しない。
- draw.io XML・page内ID、文書リンク、差分・機密値混入を確認する。計画作成段階ではコード検証の再実行や実API接続は行わない。

### 実API・実通話の受入

- RHEL 8.10で権限・残高・TLS／到達性、PCMU / G.722の日→英音声と両字幕、短文・連続発話・長い無音・通話終了を確認する。
- 既存3方式との同時通話で混線・queue共有・設定干渉がないことを確認する。動作確認済みOpenAI translationの日→英も回帰確認する。
- 発話開始／終了、確定訳、TTS初回音声、RTP初回再生、queue滞留を計測し、遅延・訳抜け・重複読み・韻律・回り込みを評価する。生成字幕と実再生を区別する。
- 断線・TTS失敗・認証／残高エラーと長時間を確認する。実費はSTT＋翻訳text＋TTSを合算する。API keyは文書・ログ・チャットへ出力しない。

## 未確定事項・承認時の扱い

| 項目 | 現在の提案／未確定事項 |
| --- | --- |
| 初期用途 | ユーザー確定: 日→英、同じ電話へ音声返送、原文＋翻訳字幕 |
| voice・話速 | `Adrian / 1.0`を初期例として提案。実際の好みは試聴後に調整可能 |
| 遅延・自然さ | 上記の短い確定訳単位で開始する。許容遅延の数値と品質基準は実機評価で決定 |
| 特定用語 | APIには`context.translation_terms`があるが、辞書要件は未提示。初期実装には設定追加しない |
| リージョン・データ取扱い | 利用projectのリージョン、保存条件、契約・社内要件は未確認。初期案の公開接続先が実用途に適合するか、実API利用前に確認する |
| 契約・権限・同時通話数 | Soniox projectのSTT＋TTS権限・残高・枠、予定同時通話数は未確認。接続を行わずConsole値は推測しない |
| 利用形態の拡張 | 英→日、two-way、別通話中継、字幕専用、voice cloningは今回対象外 |

本計画を承認する場合、初期voice・短い確定訳のTTS単位・互換設定方式を含む上記提案を実装の基準とする。リージョン・契約・実API利用条件が未確定でもオフライン実装・検証は進められるが、実APIの権限・品質・費用を検証済みとは報告しない。


## 実装結果（2026-11-12）

承認された範囲で`voice.backend: soniox_translation`を追加した。初期用途は日→英、同じ電話への音声返送＋原文／翻訳字幕。既存3方式のprotocol実装と既存設定例は維持した。mainへのmerge、タグ変更、release、commit／pushは実施していない。

- `SonioxTranslationSession`、`SonioxSocket`、`SonioxTtsClient`、`SonioxTranscriptAssembler`を追加。Bearer接続、STT raw PCM16 binary、確定英訳のみの直列TTS、raw PCM24の通話別再生、残字幕drainとTTS cancel、永続エラー停止と一時エラー再接続を実装した。
- 実効backendは新キー→旧キー→realtimeで決め、明示値の競合を拒否する。Soniox設定は独立recordとし、SonioxスロットにOpenAI key・bot設定を要求しない。初期版の言語は`ja`ヒントと`en`目的言語に限定する。モデル、voice、話速、endpoint待ち、字幕ログは設定可能。
- 音声生成単位は句読点／確定制御token／500ms／512文字。TTS待ちは10単位・4096文字、stream待ち60秒、Soniox再生queueは500 frameと共通容量の小さい方で制限した。待ち上限やtimingは初期固定値で、設定キーを増やしていない。
- `StreamingAudioDecoder`と`Pcm16Resampler`はpackageを移さず必要APIを公開。decoderへ任意のframe拒否検出を追加し、Sonioxだけ有効化した。既存constructorの動作は維持した。
- Hub／UIはSonioxの空snapshotを削除情報として扱い、未確定字幕の消失と遅れて届く旧履歴を正しく処理する。原文・翻訳・状態の既存event形式を再利用した。表示字幕は確定turn・実再生の証明として扱わない。
- 4方式共存設定例、README、現行設計書、draw.io（8ページ目`Soniox Translation Sequence`）、デプロイ／RHEL反映手順を同時更新した。OpenAI固有の3シーケンスページは維持した。

### 検証記録

- `scripts/check-voice.sh`: 既存41項目、OpenAI translation54項目、Soniox81項目が成功。Sonioxでは4方式設定、原文／訳文の未確定修正・消失、確定訳の一度だけのTTS送信、直列stream、PCM境界・末尾・8kHz／16kHz、空native frame、送信timeout、queue上限、断片JSON、timer、無音keepalive、APIエラー分類、終了・残字幕・後着socket、同時通話分離を検証した。
- 共有managerの追加検証で、永続エラー停止、無音時再接続、15→30秒＋jitterのbackoff、通話終了時の再試行状態解除を確認した。
- `scripts/check-config.sh`は既存4設定例＋`gateway.soniox.example.yaml`の全5例が成功。
- `node --check src/main/resources/monitor/app.js`と`node src/test/js/monitor-check.cjs`が成功。未確定字幕の空snapshot削除と、古い履歴による復活抑止も確認した。
- `GRADLE_USER_HOME="$PWD/.deps/gradle-cache" gradle --offline --no-daemon check`が最終ソースで成功。sandbox内ではロック管理用socketが拒否されたため、許可された制限外実行で確認した。
- PJSUA2 bindingをclasspathへ含めたmain＋pjsua2全Javaソースのコンパイルと、`scripts/check-pjsua2-java.sh`のnative binding読込が成功。既知のOpenH264警告は音声のみの今回の確認には影響しなかった。
- draw.io全8ページのXML、page内cell IDの一意性・参照、文書ローカルリンク、コードフェンス、差分の空白を確認。OpenAI専用3シーケンスページはXML内容を維持した。
- Gitは`codex/experimental`、HEADは開始時の`4a65071`のまま。変更は未コミット。`bin/`と機密設定は変更していない。

STT／TTS WebSocket公式仕様は2026-11-12にも再確認した。実API接続、実際のSoniox voice試聴、RHEL実通話、codec別品質、課金、長時間・実負荷、ブラウザ／draw.ioの外観確認は今回未実施。オフラインの音声変換・接続lifecycle検証と区別する。


## 英→日拡張（2026-11-13）

ユーザーからSoniox日→英の正常動作報告を受領し、英→日も実装するよう依頼を受けた。APIの`one_way`と日本語／英語対応、voiceの言語切替を[公式翻訳仕様](https://docs.soniox.com/translation/stt-translation)、[対応言語](https://docs.soniox.com/translation/supported-languages)、[voice仕様](https://docs.soniox.com/tts/concepts/voices)で再確認した。

- `soniox.languageHints: en`と`soniox.translationTargetLanguage: ja`で英→日を選択する。既定は従来の`ja / en`。スロットごとの片方向翻訳であり、two-wayや別通話中継は追加しない。
- STT翻訳先、確定訳のTTS送信対象、TTS発声言語、字幕の目的言語、接続状態文言を設定へ追従させた。日本語訳のUIラベルを「翻訳（日本語）」とする。voice・PCM・queue・終了・再接続方針は維持した。
- 入力ヒントは目的言語と反対の1言語だけを許可する。同一言語の組合せ、他言語、複数ヒントは設定エラーとする。言語ヒントは厳密な入力言語制限を意味しない。
- `config/gateway.soniox.en-ja.example.yaml`を追加し、README、現行設計書、draw.io、RHEL／デプロイ手順を同時更新した。機密設定と未追跡`bin/`はRepositoryの変更対象外。
- 既存41項目、OpenAI translation54項目、Soniox98項目（合計193項目）と共有managerの追加検証が成功。英→日のSTT設定、未確定字幕、確定日本語TTS、voice維持、日本語字幕metadata、8kHz／16kHz再生、目的言語不一致の読み上げ抑止、逆方向同時通話の分離を検証した。
- UI検証は日本語ラベルと既存snapshot／履歴競合を確認。英→日設定例の読み込み、オフラインGradle checkが成功した。

日→英の実通話成功はユーザー報告で確認済み。英→日の実API・日本語音声品質は、オフライン検証だけでは確認できないため、RHEL反映後の実通話確認と区別する。
