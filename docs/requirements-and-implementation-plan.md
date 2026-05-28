# Telephony OpenAI Gateway 要件・実装計画

## 1. 目的

本プロジェクトでは、SIP/RTPによる電話音声とOpenAIの音声Botを接続する軽量Gatewayを構築する。

Gatewayは単純なSIP User Agent Server (UAS) として動作し、着信を受け、RTPから音声を取り出し、OpenAI Realtime APIへ音声ストリームを送信する。OpenAIから返却される音声レスポンスは、RTPとして発信者側へ返送する。

初期実装では、AsteriskやFreeSWITCHのような高機能PBXは利用しない。

## 2. 対象アーキテクチャ

```text
SIP端末 / SIP Provider
        |
      SIP/RTP
        |
Telephony OpenAI Gateway
  - SIP UAS
  - RTP/audio bridge
  - call session manager
  - OpenAI Realtime client
        |
OpenAI Realtime API
```

## 3. 主要な技術判断

### 3.1 実装言語

アプリケーション本体はJavaで実装する。

理由:

- 本プロジェクトではC/C++によるアプリケーション実装を避けたい。
- Javaは常駐型Gatewayプロセスに適している。
- WebSocket、設定管理、ログ、メトリクス、デプロイ周辺のライブラリが成熟している。
- 低遅延Gateway用途では、Python拡張モジュールを含む実行環境よりも、Javaの方がRHEL上で運用しやすい可能性が高い。

### 3.2 SIP/RTPスタック

PJSIPをPJSUA2 Java binding経由で利用する。

理由:

- PJSIPはSIP、SDP、RTP、RTCP、codec、media処理を提供する。
- PJSUA2はendpoint、account、call、mediaを扱う高レベルAPIを提供する。
- PJSUA2はSWIGによるJava/Python bindingを公式に想定している。
- SIP/RTPスタックを自前実装せずに、アプリケーション層をJavaで実装できる。

重要な制約:

- PJSIP/PJSUA2はネイティブ依存として残る。アプリケーション本体をC/C++で書く必要はないが、macOSおよびRHEL向けにPJSIPのネイティブライブラリをビルド・パッケージングする必要がある。

### 3.3 PBXを使わない方針

MVPではAsteriskやFreeSWITCHを利用しない。

理由:

- Gatewayが必要とするのは、単純な着信受付とAI Botへの音声ブリッジである。
- dialplan、IVR、会議、録音管理、大規模transcoding、エンタープライズ向け電話制御などはMVPの範囲外である。
- 初期検証では軽量なUASで十分である。

### 3.4 OpenAI連携

低遅延の音声対話にはOpenAI Realtime APIを利用する。

評価対象の音声フォーマット:

- `g711_ulaw`
- `g711_alaw`
- `pcm16`

MVPでの優先方針:

- SIP/RTP側のcodecはPCMUを前提とし、G.711 μ-lawのpassthroughを優先する。
- 中間処理やAPI/session設定の都合で必要な場合のみPCM16を利用する。

## 4. 機能要件

### 4.1 SIP呼制御

Gatewayは以下を実現する。

- SIP endpointを起動する。
- アプリケーション起動直後にSIP Registrationを実行する。
- Registration成功後、SIP INVITEの待ち受けを開始する。
- SIP/RTPはUDP/IPv4を前提とする。
- SIP INVITEを待ち受ける。
- UASとして動作する。
- 対応可能な通話に対して`200 OK`を返す。
- 対応できない通話に対して適切なSIP応答で拒否する。
- BYEおよび通話終了処理を扱う。
- SIP通話ごとにアプリケーション上のcall sessionを1つ作成する。

Registrationでは以下を扱う。

- Domain。
- User Name。
- Password。
- SIP Address。
- Registry Server address。SIP用語としてはRegistrar Serverを指す。
- Registry Server port。SIP用語としてはRegistrar Server portを指す。

### 4.2 SDPおよびcodec制御

Gatewayは以下を実現する。

- SDPにより音声mediaをnegotiationする。
- MVPではPCMUをサポート対象とする。
- PCMU以外のcodecは、初期実装では原則として拒否する。
- negotiated codec、sample rate、packetization interval、RTP directionを管理する。

対象:

- PCMU、8 kHz、20 ms packet。

### 4.3 RTPおよび音声ブリッジ

Gatewayは以下を実現する。

- PJSIP/PJMEDIA経由でRTPから発信者音声を受信する。
- OpenAI Realtime input向けに音声frameを変換またはpackagingする。
- OpenAI output audioを受信する。
- 返却音声をRTP側へ適切なpaceで送信する。
- MVPとして基本的なsilence、jitter、packet loss、backpressureを扱う。

PJSUA2/PJMEDIAとの具体的な連携方式は、spike段階で検証する。

候補:

- PJSUA2のaudio media接続で十分なframe accessが得られるか確認する。
- 直接的なin-memory audio frame accessが必要な場合、custom PJMEDIA portを実装する。
- 通常のstreaming pathではfile-based recorder/player方式を避ける。ただし診断用途では利用可能とする。

### 4.4 OpenAI Realtime session

Gatewayは以下を実現する。

- 通話ごとにOpenAI Realtime sessionを1つ作成する。
- 発信者音声をsessionへ送信する。
- modelの音声レスポンスを受信する。
- Botのpersonaや動作を制御するsystem instructionsを設定できる。
- 通話終了時にOpenAI sessionを閉じる。
- API errorをcall sessionおよびログへ反映する。

### 4.5 session lifecycle

各call sessionでは以下を管理する。

- SIP call IDまたはPJSIP側のcall identifier。
- 発信者/着信先のSIP identity。
- negotiated codec。
- OpenAI Realtime connection state。
- media bridge state。
- 開始時刻および終了時刻。
- 終了理由。

### 4.6 設定

Gatewayはコード変更なしで設定変更できる必要がある。

初期設定項目:

- SIP bind address。
- SIP port。
- SIP transport。UDP固定。
- SIP IP version。IPv4固定。
- 必要に応じたpublic/contact address。
- supported codec。初期はPCMU固定。
- Registration Domain。
- Registration User Name。
- Registration Password。
- Registration SIP Address。
- Registration Registry Server address。SIP用語としてはRegistrar Serverを指す。
- Registration Registry Server port。SIP用語としてはRegistrar Server portを指す。
- OpenAI API key。
- OpenAI Realtime model。
- Bot system instructions。
- logging level。

### 4.7 ログ

Gatewayは以下をログ出力する。

- process startup/shutdown。
- SIP endpoint startup status。
- incoming INVITE。
- call accepted/rejected。
- media negotiation result。
- OpenAI session connect/disconnect。
- call teardown。
- callを追跡できる十分なcontextを含むerror。

ログには安定したcall/session identifierを含める。

## 5. 非機能要件

### 5.1 開発・デプロイ環境

開発環境:

- macOS。

デプロイ環境:

- RHEL。

両環境に対して、明示的なscriptとdocumentでbuild手順を管理する。

### 5.2 runtime model

MVPではGatewayを単一の常駐processとして実行する。

将来の本番運用では以下を検討する。

- systemd service。
- container image。
- health endpoint。
- metrics endpoint。

### 5.3 latency

設計上、音声latencyを最小化する。

初期のlatency対策:

- 音声pathで不要なfile I/Oを避ける。
- 過大なbufferingを避ける。
- 可能な場合はcodec passthroughを利用する。
- WebSocket送受信loopをSIP callback処理から分離する。
- media componentとOpenAI componentの間にbounded queueを使う。

### 5.4 reliability

MVPでは以下を扱う。

- 発信者による切断。
- OpenAI WebSocket disconnect。
- unsupported codec。
- SIP call setup failure。
- audio bridge startup failure。

本番向けには以下を追加する。

- reconnect policy。
- circuit breaker。
- rate limiting。
- 詳細なmetrics。
- load testおよびsoak test。

### 5.5 licensing

商用利用またはclosed-source配布の前に、PJSIPのlicenseを確認する。

PJSIPはGPLおよび商用licenseの選択肢がある。production利用前に、本プロジェクトの配布形態とlicense制約を確認する必要がある。

## 6. 想定プロジェクト構成

```text
.
├── AGENTS.md
├── README.md
├── build.gradle
├── settings.gradle
├── config
│   └── gateway.example.yaml
├── native
│   ├── macos
│   └── rhel
├── scripts
│   ├── build-pjsip-macos.sh
│   ├── build-pjsip-rhel.sh
│   ├── run-local.sh
│   └── package-rhel.sh
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com/example/telephonygw
│   │   │       ├── Main.java
│   │   │       ├── app
│   │   │       │   └── GatewayApp.java
│   │   │       ├── config
│   │   │       │   └── GatewayConfig.java
│   │   │       ├── sip
│   │   │       │   ├── PjsipEndpoint.java
│   │   │       │   ├── PjsipAccount.java
│   │   │       │   ├── PjsipCall.java
│   │   │       │   └── SipCallListener.java
│   │   │       ├── session
│   │   │       │   ├── CallSession.java
│   │   │       │   └── CallSessionManager.java
│   │   │       ├── media
│   │   │       │   ├── AudioBridge.java
│   │   │       │   ├── AudioFrame.java
│   │   │       │   ├── AudioQueue.java
│   │   │       │   └── CodecConfig.java
│   │   │       ├── openai
│   │   │       │   ├── RealtimeClient.java
│   │   │       │   ├── RealtimeEvent.java
│   │   │       │   └── RealtimeSession.java
│   │   │       └── util
│   │   │           └── ShutdownHooks.java
│   │   └── resources
│   │       └── logback.xml
│   └── test
│       └── java
└── docs
    ├── architecture.md
    ├── requirements-and-implementation-plan.md
    ├── pjsua2-java-notes.md
    └── rhel-deploy.md
```

## 7. 実装フェーズ

### Phase 0: 技術spike

目的:

- JavaからPJSUA2を制御でき、Gatewayに必要なmedia機能へアクセスできるか確認する。

作業:

- macOSでPJSIPをJava SWIG binding付きでbuildする。
- JavaからPJSUA2 endpointを起動する。
- 設定ファイルのRegistration情報を使い、SIP Registrationを実行する。
- UDP/IPv4でSIP endpointを待ち受ける。
- local SIP clientからINVITEを受ける。
- 通話に応答する。
- media establishmentを確認する。
- inbound/outbound audio frameへのアクセス方法を検証する。

完了条件:

- SIP Registrationが成功する。
- Java processがSIP callを受けられる。
- media pathを観測できる。
- in-memory audio bridgeの実装方針が明確になる。

### Phase 1: Java application skeleton

目的:

- 保守しやすいJava Gatewayアプリケーションの雛形を作る。

作業:

- Gradle projectを追加する。
- configuration loadingを追加する。
- Registration設定の読み込みを追加する。
- structured loggingを追加する。
- lifecycle managementを追加する。
- 基本的なSIP endpoint wrapperを追加する。
- call session managerを追加する。
- placeholder media bridgeを追加する。
- placeholder OpenAI Realtime clientを追加する。

完了条件:

- アプリケーションが正常にstart/stopできる。
- YAMLおよびenvironment variablesから設定を読み込める。
- native library setupがlocal前提であっても、SIP endpoint initializationがコード上に表現されている。

### Phase 2: SIP UAS MVP

目的:

- 実際のinbound SIP callを受ける。

作業:

- 起動時SIP Registrationを実装する。
- Registration失敗時のretryまたはprocess終了方針を定義する。
- inbound INVITE callbackを実装する。
- call answer/reject behaviorを実装する。
- PCMU固定のcodec handlingを実装する。
- call lifecycle eventを追跡する。
- call teardown handlingを追加する。

完了条件:

- Gateway起動後にSIP Registrationが成功する。
- SIP clientからGatewayへ発信できる。
- Gatewayが応答し、通話終了処理も正しく行える。
- ログでstable session ID付きのcall lifecycleを確認できる。

### Phase 3: RTP/audio bridge MVP

目的:

- PJSIP mediaとapplication queueの間でaudio frameを流せるようにする。

作業:

- inbound audio frame extractionを実装する。
- outbound audio frame injectionを実装する。
- bounded queueを追加する。
- outbound RTP audio向けの基本pacingを追加する。
- frame count、drop、queue depthの診断ログを追加する。

完了条件:

- 発信者音声をapplication-level frameとして取得できる。
- application-generated audio frameを発信者へ返送できる。
- 通常のstreaming pathでfile I/Oを必要としない。

### Phase 4: OpenAI Realtime integration

目的:

- 各通話をOpenAI Realtime sessionへ接続する。

作業:

- Realtime WebSocket connectionを実装する。
- session configurationを送信する。
- inbound caller audioをOpenAIへstreamingする。
- response audioを受信する。
- response audioをoutbound RTP bridgeへ渡す。
- disconnectおよびerror handlingを実装する。

完了条件:

- 発信者がBotと会話できる。
- Bot音声レスポンスが発信者に聞こえる。
- 通話終了時にOpenAI sessionが閉じられる。

### Phase 5: RHEL deployment preparation

目的:

- GatewayをRHELへdeployできる状態にする。

作業:

- RHEL dependenciesをdocument化する。
- RHEL向けPJSIP build scriptを追加する。
- native library loading layoutを定義する。
- systemd unit exampleを追加する。
- packaging scriptまたはdeployment notesを追加する。

完了条件:

- RHELでbuildしたPJSIP native libraryを使い、同じJava applicationをRHEL上で実行できる。
- deployment stepsが再現可能な形でdocument化されている。

## 8. 主なリスクと未確定事項

### 8.1 PJSUA2 Java media access

リスク:

- PJSUA2 Java bindingが、低遅延のdirect frame accessに必要なmedia hookを十分に公開していない可能性がある。

対策:

- full application構築前にPhase 0で検証する。
- 必要であればcustom PJMEDIA portを使い、最小限のbridgeをSWIG/JNIで公開する。
- native surfaceが必要になった場合でも、ごく小さい範囲に隔離する。

### 8.2 native build complexity

リスク:

- PJSIP native buildおよびJava SWIG artifactが、macOSとRHELで異なる問題を起こす可能性がある。

対策:

- macOS/RHELそれぞれにbuild scriptを用意する。
- 明示的に必要になるまでgenerated native binariesはcommitしない。
- 必要なtoolchainとversionをdocument化する。

### 8.3 codec/format mismatch

リスク:

- SIP/RTP側はPCMU固定とするが、OpenAI側とのsession設定や返却音声formatによって変換が必要になる可能性がある。

対策:

- PCMUから開始する。
- MVPではPCMU以外を拒否し、codec negotiationを狭く保つ。
- 変換処理は必要になった時点で追加する。

### 8.4 real-time backpressure

リスク:

- OpenAI response timingとRTP packet pacingにずれが生じる可能性がある。

対策:

- bounded queueを利用する。
- queue depthを監視する。
- queueが上限を超えた場合は、明示的にaudioをdropまたはtrimする。
- 初期段階からmetricsを用意する。

### 8.5 licensing

リスク:

- PJSIPのlicenseが配布形態に制約を与える可能性がある。

対策:

- production前に想定配布形態を確認する。
- closed-source commercial deploymentが必要な場合は、PJSIP commercial licenseを評価する。

## 9. GitHub Repository管理方針

本プロジェクトはGitHub Repositoryで継続管理する。

初期方針:

- ソースコード、設計文書、設定例、build script、deployment notesをRepositoryで管理する。
- API key、SIP password、実環境のSIP接続先、private certificateなどの機密情報はRepositoryに含めない。
- 機密値が必要な設定は、`.env.example` や `config/*.example.yaml` にplaceholderとして記載する。
- PJSIP native library、SWIG生成物、build成果物、log、temporary filesは原則としてcommitしない。
- PJSIP/PJSUA2のnative依存は、macOS/RHELそれぞれのbuild scriptと手順で再現する。
- GitHub Repository作成後は、README、要件・実装計画、build手順、local test手順を最新化する。

初期Repositoryに含める想定:

- `AGENTS.md`
- `README.md`
- `docs/`
- `config/*.example.yaml`
- `scripts/`
- `src/`
- Gradle関連file
- `.gitignore`
- `.env.example`

初期Repositoryに含めない想定:

- `.env`
- 実credential入り設定file
- OpenAI API key
- SIP password
- PJSIP build成果物
- Java class/jar build成果物
- log file
- packet capture file
- IDE固有のlocal設定

## 10. 初期local test計画

想定ツール:

- local SIP softphone。
- SIPpによるscripted SIP call test。
- WiresharkによるSIP/RTP確認。

初期test case:

- Gatewayが起動し、SIP portをbindできる。
- Gatewayが起動直後にSIP Registrationを実行できる。
- GatewayがRegistration失敗時に想定どおりretryまたは終了できる。
- GatewayがINVITEを受けられる。
- GatewayがPCMU以外のunsupported codecを拒否できる。
- GatewayがPCMU callをacceptできる。
- Gatewayがcaller BYEを処理できる。
- Gatewayがinternal error時にhangupできる。
- GatewayがcallごとにOpenAI Realtime sessionをopen/closeできる。
- OpenAI integration前にaudio loopback testが成功する。
- end-to-endでcaller-to-bot audio testが成功する。

## 11. Open Questions

- 初期deploymentでNAT traversalは必要か。
- MVPで想定する同時通話数はいくつか。
- OpenAI Realtime modelの初期defaultは何にするか。
- 通話音声またはtranscriptを保存する必要があるか。
- 録音、ログ、PIIに関するcompliance要件はあるか。
- 対象RHEL versionは固定されているか。
- closed-source commercial distributionを想定するか。
- GitHub Repository名は何にするか。
- GitHub Repositoryはprivate/publicのどちらにするか。
- default branch名は`main`でよいか。
- branch運用は直接`main`へcommitするか、feature branchとPull Requestを使うか。
- GitHub ActionsによるCIを初期段階から利用するか。
