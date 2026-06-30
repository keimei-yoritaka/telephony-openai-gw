# 複数独立セッション対応 実装計画

## 目的

現行実装は、PJSUA2 account単位で`activeCalls`が1件でも存在すると新規INVITEへ`486 Busy Here`を返す。そのため、実質的に1コールのみ受け付ける構造になっている。

本対応では、設定ファイルに複数の固定セッションスロットを定義し、各スロットがSIP transport、SIP Registration、OpenAI Realtime設定、Bot設定を独立して持てるようにする。

## 要件整理

- ブランチは`codex/multi-session-support`を利用する。
- 各スロットは最大1コールを受け付ける。
- 全体の最大同時通話数は、設定された有効スロット数で決まる。
- 各スロットは以下を独立して持つ。
  - SIP bind address
  - SIP port
  - SIP codec policy
  - RTP port range
  - SIP Registration情報
  - OpenAI Realtime設定
  - Bot system instructions
  - Bot initial greeting
- 既存の単一セッション設定との後方互換は必須としない。
- SIP側セッションとOpenAI Realtime sessionは1:1で紐付ける。

## 設計方針

### セッションスロット

`session-1`、`session-2`のような設定上の固定スロットを導入する。スロットIDはログ、会話モニター、障害調査で追跡できるよう、call session IDとは別に保持する。

```text
session slot
  -> SIP Transport
  -> SIP Account / Registration
  -> active call 0 or 1
  -> CallSession
  -> OpenAI RealtimeSession
```

PJSUA2は同一JVM内で1つのEndpointを起動し、そのEndpoint配下にスロットごとのUDP transportとAccountを作る方針とする。PJSUA2のpjsua層はプロセス内グローバル状態を持つため、Endpoint自体をスロット数分作るよりも、1 Endpoint + 複数transport/accountの方が安定しやすい。

この方式でも、SIP bind port、Registration、Account callback、active call管理、NAT advertisementはスロットごとに分離できる。一方で、codec priorityはPJSUA2 Endpoint全体の設定になるため、スロットごとに完全に異なるcodec priorityを持たせることは初期実装の対象外とする。

### CallSession

`CallSession`へ`slotId`を追加する。

```text
CallSession
  sessionId: UUID
  slotId: session-1
  state: ACTIVE / CLOSED
```

OpenAI Realtime sessionは引き続き`sessionId`単位で作成する。OpenAI設定は`slotId`から解決した設定を使う。

### OpenAI設定解決

現行の`RealtimeClient`は単一の`OpenAiConfig`と`BotConfig`を保持している。これを、`sessionId -> slotId -> slot config`で解決する構造へ変更する。

候補:

```text
CallSessionManager.createSession(slotId)
  -> create listenerへsessionId, slotId, reasonを通知
  -> RealtimeClient.startSession(sessionId, slotConfig, reason)
```

音声転送workerは`AudioFrame.sessionId`から既存`RealtimeSession`を引く。未作成の場合は`sessionId -> slot config`のmapから設定を解決して作成する。

### AudioBridge

AudioBridgeは`sessionId`単位でinbound queue、outbound queue、sample rateを分離する。

queue capacityは全セッション共通値として`media.inboundQueueCapacity`、`media.outboundQueueCapacity`で指定する。デフォルトはinboundが500 frame、outboundが10000 frameとする。

`RealtimeClient`のforwarding workerは、call sessionごとに起動し、該当sessionのinbound queueだけをOpenAIへ転送する。これにより、複数同時通話時に片方のinbound queue滞留が別セッションのOpenAI転送を遅らせる影響を避ける。

### 会話モニター

会話イベントにはすでに`sessionId`が付与されているため、複数セッションの履歴保持は可能である。

追加で`slotId`を会話イベントに含めるかは検討対象とする。初期実装ではログの`GW_EVENT`に`slotId`を出し、UI拡張は後続対応にしてもよい。

## 設定ファイル案

外部YAML parser依存を追加せず、現行の簡易YAML loaderで扱える形式にする。

`config/gateway.local.yaml`はgit管理外の実行用ローカル設定として扱う。デモ用途では、スロットごとの`registration.password`へSIP passwordを平文で記載してよい。一方、`config/*.example.yaml`や設計文書などRepositoryにcommitするファイルでは、実password、API key、SIP credentialを書かずplaceholderを使う。

```yaml
gateway:
  sessionIds: session-1,session-2

session.session-1:
  sip.backend: pjsua2
  sip.bindAddress: 192.168.1.1
  sip.port: 6060
  sip.transport: UDP
  sip.ipVersion: IPv4
  sip.preferredCodec: G722
  sip.codecs: G722,PCMU
  sip.publicContactAddress: ""
  sip.rtpPortStart: 40000
  sip.rtpPortEnd: 41000
  registration.domain: example.com
  registration.userName: 1111
  registration.password: ${SIP_REGISTRATION_PASSWORD_1111}
  registration.sipAddress: sip:1111@example.com
  registration.registryServerAddress: registrar.example.com
  registration.registryServerPort: 5060
  openai.apiKey: ${OPENAI_API_KEY}
  openai.realtimeModel: gpt-realtime
  openai.voice: coral
  openai.maxOutputTokens: inf
  openai.turnDetectionType: semantic_vad
  openai.turnDetectionEagerness: low
  openai.transcriptLoggingEnabled: true
  openai.inputTranscriptionModel: gpt-realtime-whisper
  openai.inputTranscriptionLanguage: ja
  bot.systemInstructions: "あなたはsession-1の電話応対を行うAIアシスタントです。"
  bot.initialGreeting: "こちらは一番窓口です。ご用件をお話しください。"

session.session-2:
  sip.backend: pjsua2
  sip.bindAddress: 192.168.1.1
  sip.port: 6062
  sip.transport: UDP
  sip.ipVersion: IPv4
  sip.preferredCodec: G722
  sip.codecs: G722,PCMU
  sip.publicContactAddress: ""
  sip.rtpPortStart: 41002
  sip.rtpPortEnd: 42000
  registration.domain: example.com
  registration.userName: 2222
  registration.password: ${SIP_REGISTRATION_PASSWORD_2222}
  registration.sipAddress: sip:2222@example.com
  registration.registryServerAddress: registrar.example.com
  registration.registryServerPort: 5060
  openai.apiKey: ${OPENAI_API_KEY}
  openai.realtimeModel: gpt-realtime
  openai.voice: shimmer
  openai.maxOutputTokens: inf
  openai.turnDetectionType: semantic_vad
  openai.turnDetectionEagerness: low
  openai.transcriptLoggingEnabled: true
  openai.inputTranscriptionModel: gpt-realtime-whisper
  openai.inputTranscriptionLanguage: ja
  bot.systemInstructions: "あなたはsession-2の電話応対を行うAIアシスタントです。"
  bot.initialGreeting: "こちらは二番窓口です。ご用件をお話しください。"

logging:
  level: INFO

monitor:
  enabled: true
  bindAddress: 127.0.0.1
  port: 8080
  maxEvents: 500
  sessionHistoryDepth: 10
```

この形式では、トップレベルに`gateway.sessionIds`を置き、各スロットを`session.<slotId>` sectionとして表現する。見た目は通常のYAML配列より少し冗長だが、既存scriptの手動`javac`起動を壊さずに済む。

## 実装ステップ

### Step 1: 設定モデルの再設計

- `GatewayConfig`に`List<SessionSlotConfig>`を追加する。
- `SessionSlotConfig`は`slotId`、`SipConfig`、`RegistrationConfig`、`OpenAiConfig`、`BotConfig`を持つ。
- 既存のトップレベル`sip`、`registration`、`openai`、`bot`は廃止する。
- `GatewayConfigLoader`を`gateway.sessionIds`と`session.<slotId>.*`形式に対応させる。
- `config/gateway.example.yaml`と`config/gateway.pjsua2.example.yaml`を新形式へ更新する。

状態: 実装済み。

### Step 2: スロット単位のCallSession管理

- `CallSession`へ`slotId`を追加する。
- `CallSessionManager.createSession(slotId)`を追加する。
- create/close listenerの通知に`slotId`を含めるため、専用listener interfaceを導入する。
- ログに`slotId`を出す。

状態: 実装済み。

### Step 3: SIP endpointのスロット化

- `GatewayApp`は1つの`PjsipEndpoint`を作成し、全スロット設定を渡す。
- `PjsipEndpoint`は全スロット設定を受け取る。
- `Pjsua2SipEndpoint`は1つのPJSUA2 Endpoint上で、スロット単位のSIP transport/account/registrationを開始する。
- `Pjsua2Account`は自スロット内の`activeCalls`だけを見て、同一スロット2コール目を`486 Busy Here`で拒否する。
- 他スロットの通話中状態は影響させない。

状態: 実装済み。1つのPJSUA2 Endpoint上に、スロットごとのUDP transportとAccountを作成する。

### Step 4: OpenAI Realtime設定のスロット化

- `RealtimeClient`は`sessionId -> SessionRuntimeConfig`を保持する。
- call session作成時に、該当スロットのOpenAI/Bot設定を登録する。
- `openSession(sessionId)`はsessionIdからスロット設定を解決してRealtimeSessionを作成する。
- 初期挨拶もスロットごとの`bot.initialGreeting`を使う。

状態: 実装済み。

### Step 5: ライフサイクルと停止処理

- Gateway起動時はPJSUA2 Endpointを起動し、全スロットのSIP transport/accountを作成してRegistrationを行う。
- Gateway停止時はPJSUA2 Endpointを停止し、全CallSession/OpenAI session/AudioBridge queueを閉じる。
- 一部スロットの起動失敗を全体停止にするか、失敗スロットだけ無効化するかを実装時に決める。初期実装ではデモ運用の明確性を優先し、1スロットでも起動失敗したらGateway起動失敗とする。

状態: 実装済み。

### Step 6: 検証

- 設定ファイル検証。
- 2スロットでそれぞれRegistrationされることを確認する。
- `session-1`への1コールと`session-2`への1コールを同時に確立できることを確認する。
- 同一スロットへ2コール目を入れた場合、該当スロットだけ`486 Busy Here`になることを確認する。
- 各スロットで異なるvoice、initial greeting、system instructionsが反映されることを確認する。
- 会話モニターで複数`sessionId`が見えることを確認する。

状態: 実施済み。設定検証、compile、2スロットのPJSUA2 transport作成、2スロットのRegistration開始、実SIP環境での2セッション同時通話、スロットごとのOpenAI/Bot設定反映を確認済み。

## リスクと注意点

- codec priorityはPJSUA2 Endpoint全体の設定になるため、スロットごとのcodec policy差分には制約がある。
- 複数同時通話ではOpenAI Realtime session数が増えるため、API利用量と同時接続制限に注意する。
- OpenAI audio forwarding workerは通話セッションごとに起動する。OpenAI側の応答音声が長い場合、RTP再生待ちのoutbound queueが残ることがあるため、デモ設定ではBot instructionや`openai.maxOutputTokens`で応答長を調整する。
- RTP port rangeはスロット間で重複しない設定を推奨する。重複時にPJSIPが空きportを選べる可能性はあるが、運用上は明示的に分けた方が調査しやすい。

## 現時点の判断

複数独立セッション対応は実装済み。スロット単位のtransport/account/registration、OpenAI/Bot設定、session別audio queue、session別OpenAI送信workerにより、2スロットの同時通話で独立動作することを確認した。
