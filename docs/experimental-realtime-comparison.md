# StreamingとRealtimeの比較、およびExperimental開発への影響

調査日: 2026-10-13（日本時間）
対象: `codex/experimental`、調査開始時のHEAD `306c083`
位置付け: 比較・検討資料。実装計画や現行仕様の変更を確定する文書ではない。

## 結論

本プロジェクトはすでにOpenAI Realtime APIをWebSocketで使用している。「音声をstreamingしている」ことと「Chat Completions / ResponsesのHTTP streamingを使用している」ことは異なる。今回の実験は、Realtimeの新規導入ではなく、既存Realtime構成の改善、Realtimeモデルの更新、または別の会話方式であるGPT-Liveの追加として整理する。

比較時点ではリリース名は未指定だったが、後続のユーザー確認で追加対象はGPT-Liveと確定した。以下は当初の比較結果を維持し、具体的な追加方針は[GPT-Live追加の要件・実装計画](gpt-live-implementation-plan.md)で管理する。

## 開始時点の確認

- `AGENTS.md`、`docs/experimental-handoff.md`、`docs/system-design.md`、`README.md`と関連Javaソースを確認した。
- 作業ブランチは`codex/experimental`。追跡先は`origin/codex/experimental`。
- ローカルのremote追跡参照とのahead / behindは`0 / 0`。fetchしていないため、GitHub上の最新状態を保証するものではない。
- remoteは`https://github.com/keimei-yoritaka/telephony-openai-gw.git`。
- 開始時点の変更は未追跡の`bin/`のみ。既存資材として維持する。
- 引き継ぎ上のStableは`v0.1.0`、Experimentalはその起点に引き継ぎ文書を追加した状態。
- Java 21 / PJSUA2でSIP/RTPを扱い、PCMU / G.722のPCMとOpenAI向け24kHz PCMを相互変換する。固定スロットごとに最大1通話、音声queueは通話ごとに分離する。
- 設定例のモデルは`gpt-realtime`。実環境の設定・実際に解決されたモデルは今回確認していない。機密を含む`config/gateway.local.yaml`は調査対象にしていない。

## 現行コードで確認できるAPI

| 根拠 | 現行の処理 |
| --- | --- |
| `src/main/java/com/example/telephonygw/openai/RealtimeSession.java` | `wss://api.openai.com/v1/realtime?model=...`へJava標準WebSocketで接続 |
| 同クラスの`sessionUpdateEvent` | `session.update`で音声形式、voice、instructions、VADを設定 |
| 同クラスの`appendInputAudio` | PCMを24kHzへ変換し、`input_audio_buffer.append`で音声を逐次送信 |
| 同クラスの`SessionListener` | `response.output_audio.delta`を受信し、音声queueへ投入 |
| `config/gateway.example.yaml`、`GatewayConfigLoader` | `semantic_vad` / `eagerness: low`、barge-in既定無効、AI送話中の入力破棄既定無効 |
| `ConversationMonitorServer` | SSEはGatewayからブラウザへの会話表示に使用 |

ソース内にChat Completions / Responsesの呼び出しや、独立したSTT → テキストLLM → TTSの会話処理は見当たらない。モニターのSSEはOpenAIとの通信方式ではない。

## 機能面の比較

ここでの「Streaming」はChat Completions / Responsesの`stream=true`によるHTTP/SSE出力を指す。Streamingは出力を順次受け取る方法であり、それ自体が音声会話の仕組みを提供するわけではない。音声対応の有無はAPI・モデルによる。[公式: Streaming API responses](https://developers.openai.com/api/docs/guides/streaming-responses)

| 観点 | HTTP Streamingを使う一般的な音声アプリ | Realtime API | 本プロジェクトの現状 |
| --- | --- | --- | --- |
| 会話の流れ | 典型的にはSTT → LLM → TTS。各段階をアプリで連携 | 音声入力から音声出力まで継続セッションで処理 | Realtimeによる音声対話を実装済み |
| 入出力 | 1リクエストの応答をSSEで逐次受信 | WebSocket等で音声・制御イベントを双方向通信 | WebSocketを使用 |
| 応答速度 | 逐次出力で待ちを減らせるが、STT・LLM・TTSの境界が残る | 低遅延の音声対話向け。VADや再生bufferの待ちは別に生じる | 発話区切りとRTP再生開始も測定が必要 |
| 発話終了判定 | 外部VADなどをアプリ側で組み合わせる | `server_vad` / `semantic_vad`を提供 | 両方式に対応済み |
| 割り込み | 生成停止・TTS停止・再生停止・履歴修正を連携 | speechイベント、生成キャンセル、会話item切り詰めを利用 | キャンセルとqueue破棄は実装済み。履歴切り詰めは未実装 |
| 声の情報 | STTの文字列だけをLLMへ渡す構成では、声の情報をそこで失う | 音声を直接入力する会話方式 | 文字起こしはログ・モニター用途 |
| 会話状態 | APIのstate機能またはアプリで履歴を扱う | 接続中の会話itemを管理 | 通話に対応するRealtime sessionを使用 |
| 費用 | 選ぶLLM、STT、TTSの費用を合算 | 音声・テキスト等の使用量とモデルに依存 | 同一シナリオでusageと費用を測る必要がある |

Realtimeの音声対話とイベント処理は[公式: Realtime conversations](https://developers.openai.com/api/docs/guides/realtime-conversations)、サーバーからの接続は[公式: WebSockets（Realtime API欄）](https://developers.openai.com/api/docs/guides/voice-websockets)を参照。

## 実装面と本プロジェクトへの影響

| 項目 | HTTP Streaming構成からRealtimeへ移す場合 | 本プロジェクトで必要なこと |
| --- | --- | --- |
| APIクライアント | HTTP/SSEから継続接続とイベント処理へ変更 | `RealtimeClient` / `RealtimeSession`を再利用できる |
| 音声処理 | 音声送受信、sample rate変換、再生bufferを追加 | `AudioBridge` / `Pcm16Resampler` / PJSUA2 bridgeを実装済み |
| 通話との対応 | 接続開始・終了、エラー、再接続を通話に結び付ける | セッション別workerとqueueを実装済み。再接続時の会話継続は見直し候補 |
| VAD | 発話区切りと応答開始を新たに設計 | 既存設定で比較実験できる |
| barge-in | 音声生成、実再生、履歴を一体で制御 | 現行の生成状態とRTP再生状態の整合を改善する余地がある |
| モニター | transcriptイベントへ連携を変更 | 現行Realtimeを維持するなら基本構成を再利用できる |
| 設定・デプロイ | WebSocketの通信要件や設定を追加 | 現行構成に導入済み。通常のRealtime改善に新言語やnative libraryは必須ではない |

このため「APIをRealtimeへ交換する」だけでは新機能にならない。自然さの改善点を測定し、必要な変更を選ぶ。

## 自然な会話に影響する現行実装の論点

### 1. 発話区切りと応答開始の待ち

`semantic_vad`の`low`は、話し終わるまで長めに待つ設定である。`medium` / `auto` / `high`との比較で、応答開始の速さとユーザーの言い直し・間の許容を評価する。速く設定すれば常に自然になるわけではない。[公式: VAD](https://developers.openai.com/api/docs/guides/realtime-vad)

RTP出力は20ms frameを8個蓄積してから再生開始するため、通常は約160ms分の開始bufferを持つ。生成完了済みの短い出力では8 frame未満でも再生する。bufferを減らす実験では途切れ・underflowも評価する。

既存ログはcommit → 最初のaudio deltaなどを記録する。利用者が感じる待ちを評価するには「実際の発話終了 → 最初のRTP音声送出」までを分解する。queue容量は実際の待ち時間ではなく、queue深さ・滞留時間・非同期送信待ちも確認する。

### 2. barge-inの自動キャンセルとアプリ側判定

`turnDetectionJson`は`cancelResponseOnUserSpeech`をサーバーの`interrupt_response`にも設定する。一方、Java側は発話継続時間・RMS・猶予時間を満たした場合にqueueを破棄して`response.cancel`を送る。

サーバー側の自動キャンセルはJava側の閾値を待つ制御ではない。この二つを併用した際、サーバー生成が先に終了して`responseActive`がfalseになると、Java側の判定によるqueue破棄が実行されない可能性がある。生成完了後もRTPに未再生音声が残る場合があるため、生成中かどうかだけでなく再生中かどうかを扱う必要がある。これはコードからの懸念であり、今回の実通話で再現確認した障害ではない。

### 3. 未再生音声と会話履歴の整合

現行コードに`conversation.item.truncate`送信はない。WebSocketでは再生をGatewayが管理するため、OpenAI側は何msを電話へ送出したか分からない。割り込み時には、未再生queueの破棄に加え、対応itemと送出済み音声長を管理して会話履歴を切り詰める設計が必要になる。[公式: Realtime interruptions](https://developers.openai.com/api/docs/guides/realtime-conversations#handling-interruptions)

RTPへ送出した長さは、電話端末で実際に聞こえた長さの近似であり、ネットワークや端末の再生buffer分の差がある。モニターの全文transcriptも「生成した発話」と「送出した発話」を区別できるようにする候補となる。

### 4. AI送話中の入力と接続寿命

`dropInputAudioWhileAssistantSpeaking: true`では、AI送話中のユーザー音声がOpenAIへ届かず、相づち・訂正・割り込みを扱えない。自然な同時対話の実験では入力継続を基本とし、電話側のエコーや環境音を確認する。

`RealtimeClient`には15秒のidle closeと再接続待ちがあり、`RealtimeSession.isIdle`は最終入力frame送信時刻を参照する。入力が来ない、または入力破棄が続く条件での接続寿命を確認する。再接続は新しいsessionを開く処理であり、履歴復元処理は見当たらない。無音だけで必ず切断されるとは断定できず、継続的な無音frameが来るかにも依存する。

## 新しいモデル・会話方式を試す選択肢

### A. Realtimeを維持し、モデルと会話制御を改善する

現在の公式資料は`gpt-realtime-2.1`を掲載し、英数字認識、無音・雑音への対応、割り込み動作の改善を説明している。reasoning effortを高くすると遅延と出力tokenが増える可能性も説明されている。[公式: GPT-Realtime-2.1](https://developers.openai.com/api/docs/models/gpt-realtime-2.1)

本プロジェクトのモデル名は設定文字列なので、モデル更新の比較は既存構成を維持して始められる。ただし利用権限、voice、transcription、出力上限等の互換性と実通話を検証する。設定例の`gpt-realtime`が実環境でどのモデルに解決されるかは未確認で、モデル名の変更だけによる改善は保証しない。

### B. GPT-Liveを別backendとして追加する

GPT-Liveは、話しながら聞くfull duplexの会話を扱い、必要な推論や業務処理を別backendへ委譲する構成である。通常のRealtime APIとは別の接続・イベント体系を持つ。[公式: Getting started with GPT-Live](https://developers.openai.com/api/docs/guides/live)

| 現行Realtime | GPT-Liveを追加する場合の影響 |
| --- | --- |
| `/v1/realtime?model=...`、`session.update` | `/v1/live/sessions`、`session.start`と`session.started`の待ち合わせへ変更 |
| `input_audio_buffer.append` | `session.input_audio.append`へ変更 |
| `response.output_audio.delta` | `session.output_audio.delta`へ変更 |
| VADによるターン制御、初回`response.create` | 継続入力とLiveの発話判断へ変更。挨拶もLive用の制御へ変更 |
| `response.output_audio.done` / `response.done` | 発話ごとの対応する終了イベントがない。再生状態の扱いを再設計 |
| caller / assistantの確定transcript | `session.input_transcript.delta` / `session.output_transcript.delta`を集約。重なった発話の表示を設計 |
| 1つのinstructions、Realtimeのvoice・VAD設定 | 会話用設定と委譲先設定を分離し、Live向けに検証 |

イベント対応は[公式: Migrate to GPT-Live](https://developers.openai.com/api/docs/guides/live-migration)、transcriptの集約と再生管理は[公式: Managing GPT-Live sessions](https://developers.openai.com/api/docs/guides/live-conversations)を参照。

これはmodel名だけの交換ではない。Java/PJSUA2、固定スロット、PCM queueの基盤は再利用候補だが、`RealtimeSession`に密結合した開始・終了・barge-in・transcript・計測を分離する必要がある。24kHz PCMの公式WebSocket例はあるが、Live向け設定と音声処理の互換性は検証する。[公式: GPT-Live WebSockets](https://developers.openai.com/api/docs/guides/voice-websockets)

費用も比較条件が変わり、GPT-Liveの音声sessionは時間単位で課金され、委譲先モデル・toolは別途課金される。Realtimeと同じ通話シナリオで費用を比較する。[公式: GPT-Liveの利用と課金](https://developers.openai.com/api/docs/guides/live)

### C. OpenAIのSIP接続を直接使う

OpenAIへSIP trunkを向け、着信webhookで受付し、WebSocketで会話を制御する方式もある。[公式: Telephony and SIP（Realtime API欄）](https://developers.openai.com/api/docs/guides/voice-sip?voice-api=realtime)

この場合、現在のGatewayによるREGISTER、UDP/SIP待ち受け、PJSUA2のRTP処理、NAT処理の役割が変わる。公開webhook、trunk接続、session対応付けも必要になるため、本プロジェクトへの影響は大きい。通常のRealtime利用にSIP直結への変更は必須ではない。

## Experimental開発の進め方の提案

1. 現行Realtimeで待ち時間・割り込み・言い直しの基準を測定する。
2. 同じ電話・codec・発話・ネットワーク条件でVAD設定とRealtimeモデルを個別に比較する。
3. barge-inの生成停止、queue破棄、送出済み音声長、履歴切り詰めを一体として設計する。
4. 目的が相づち・同時発話・業務処理中の会話継続なら、GPT-Liveを追加する実装計画を別途作る。

評価は応答開始時間のp50 / p95、割り込みから送話停止まで、意図しない中断回数、言い直しの取りこぼし、音声の途切れ、会話履歴の整合、複数通話時の挙動、通話あたり費用を対象とする。日本語の「えーと」「はい」や短い間、AI音声生成完了後の割り込みを含める。

今回の作業は比較文書の追加のみで、コード・設定・現行仕様は変更していない。実通話やAPI接続の検証は実施していない。実装で仕様を変更する場合は、同じ変更単位で`docs/system-design.md`とdraw.io図を更新する。大きな変更は引き継ぎ文書の合意に従い、日本語の実装計画を提示して承認後に着手する。
