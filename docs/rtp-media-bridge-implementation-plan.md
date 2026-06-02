# RTP / OpenAI 音声ブリッジ実装計画

## 1. 目的

SIP呼制御は、実SIP環境でRegistration、INVITE受信、200 OK応答、1コールの成立まで確認できた。

次の段階では、RTP音声をアプリケーション内で取り出し、OpenAI Realtime APIへ転送し、OpenAIから返却される音声をRTP側へ返送する。

この文書は、実装前の承認用計画として作成する。承認後に、ここで定義した順序で実装を進める。

## 2. 現時点の前提

- SIP/RTPはUDP/IPv4。
- SIP側codecはPCMU固定。
- アプリケーション本体はJava。
- PJSIP/PJSUA2 Java bindingはmacOSでbuild済み。
- PJSUA2 Java callbackでINVITEを受け、`Call`を生成し、200 OKを返せる。
- `AudioBridge`と`RealtimeClient`は現時点ではplaceholder。
- OpenAI連携はRealtime APIを前提とする。

## 3. 調査結果

### 3.1 PJSUA2 Java media API

PJSUA2 Java bindingには以下のAPIが存在する。

- `AudioMedia`
- `AudioMediaPort`
- `AudioMediaPlayer`
- `AudioMediaRecorder`
- `AudioMediaAiPort`
- `MediaFrame`
- `MediaFormatAudio`

特に`AudioMediaPort`は以下をoverrideできる。

- `onFrameReceived(MediaFrame frame)`
- `onFrameRequested(MediaFrame frame)`

このため、第一候補として、PJSUA2 conference bridge上にJava実装の`AudioMediaPort`を作成し、通話の`AudioMedia`と接続する。

想定接続:

```text
Caller RTP -> PJSIP media stream -> Call AudioMedia -> Java AudioMediaPort -> OpenAI input queue
OpenAI output queue -> Java AudioMediaPort -> Call AudioMedia -> PJSIP media stream -> Caller RTP
```

### 3.2 OpenAI Realtime API

OpenAI Realtime APIは低遅延の音声対話に利用でき、WebSocket経由では`input_audio_buffer.append`でBase64化した音声chunkを送信する。音声の入力形式はRealtime sessionで設定する。

公式document上、Realtime系APIではG.711 μ-law相当の形式も扱えるため、将来的にはPCMU passthroughを狙える。ただし、PJSUA2の`AudioMediaPort`から得られるframeはconference bridgeのaudio frameであり、RTP payloadそのものではなくPCM系frameになる可能性が高い。

参照:

- [Realtime conversations - OpenAI API](https://platform.openai.com/docs/guides/realtime-model-capabilities)
- [Realtime transcription - OpenAI API](https://platform.openai.com/docs/guides/realtime-transcription)
- [Realtime API Reference - OpenAI API](https://platform.openai.com/docs/api-reference/realtime)

## 4. 実装方針

### 4.1 第一候補: PJSUA2 AudioMediaPort方式

まず、PJSUA2 Javaの`AudioMediaPort`を利用する。

理由:

- Java側だけで実装を進められる。
- 既存のPJSUA2 Java bindingに含まれている。
- custom PJMEDIA C/C++ portを追加する前に、最小の実装で音声frame accessを検証できる。
- 本プロジェクトの「アプリケーション本体をC/C++で書かない」方針に沿う。

想定する初期音声形式:

- PJSIP側: PCMU negotiated。
- Java AudioMediaPort側: PCM 8 kHz / mono / 16-bit / 20 ms frameを第一候補として検証。
- OpenAI側: まず`pcm16`で送受信する。必要に応じて24 kHz resamplingを追加する。

注意:

- この方式ではPCMU passthroughではなく、PJSIP内でdecodeされたPCMを扱う可能性がある。
- OpenAI側で`g711_ulaw`を使う場合、RTP payloadに近いPCMU byte列を取り出す経路が必要になる可能性がある。
- まずは「音声が双方向に流れる」ことを優先し、passthrough最適化は後続に回す。

### 4.2 第二候補: custom PJMEDIA port方式

`AudioMediaPort`で十分な性能、形式制御、双方向pacingが得られない場合、custom PJMEDIA portを追加する。

この場合も、C/C++実装範囲は最小限に隔離する。

候補:

- native側でPJMEDIA portを作成する。
- Javaからqueueへframeを渡すJNI/SWIG面を最小限公開する。
- Javaアプリケーション本体の責務は変えない。

### 4.3 今回は採用しない方針

通常のstreaming pathでは、以下を採用しない。

- WAV recorder/playerによるfile経由連携。
- 外部プロセスを挟む音声変換。
- RTP stackの自前実装。

ただし、診断用途として短時間の録音file出力は許容する。

## 5. 実装ステップ

### Step 1: Media frame観測spike

目的:

- `AudioMediaPort.onFrameReceived()`が実通話中に呼ばれるか確認する。
- frame size、format、callback周期をログで確認する。
- `onCallMediaState()`でcall audio mediaを取得し、Java portへ接続できるか確認する。

作業:

- `Pjsua2AudioBridgePort`を追加する。
- `Pjsua2Call.onCallMediaState()`で`getAudioMedia()`を取得する。
- caller audio mediaからbridge portへ`startTransmit()`する。
- 受信frame count、byte数、推定ptimeをログ出力する。
- 初期段階では音声をOpenAIへ送らない。

完了条件:

- 実SIP callで`onFrameReceived()`が継続的に呼ばれる。
- 20 ms相当の周期でframeが観測できる。
- frame sizeからPCM形式の仮説を立てられる。

### Step 2: RTP入力からアプリケーションqueueへ接続

目的:

- 発信者音声をapplication-level queueへ渡す。

作業:

- `AudioFrame`を実用途向けに拡張する。
- `AudioQueue`またはbounded queueを追加する。
- session ID、方向、timestamp、payload formatを持つframeを定義する。
- queue上限とdrop policyを定義する。
- frame count、drop count、queue depthをログ出力する。

完了条件:

- 発信者音声frameがJava queueへ流れる。
- queue overflow時にprocessが落ちず、dropが観測できる。

### Step 3: OpenAI Realtime送信

目的:

- 発信者音声をOpenAI Realtime APIへstreamingする。

作業:

- Java標準または既存方針に沿うWebSocket clientを選定する。
- Realtime sessionを作成し、音声input/output形式を設定する。
- input queueから音声chunkをBase64化し、`input_audio_buffer.append`相当のeventとして送信する。
- VADは初期はserver側を利用する。
- session start、updated、speech_started、speech_stopped、errorをログ出力する。

完了条件:

- 実通話中の発話に対して、OpenAI側でinput audio eventが受理される。
- API error時にcall sessionへerror理由を記録できる。

### Step 4: OpenAI音声出力からRTP返送

目的:

- OpenAIの音声レスポンスを発信者へ返送する。

作業:

- Realtime server eventからoutput audio deltaを受信する。
- audio deltaをoutbound queueへ積む。
- `AudioMediaPort.onFrameRequested()`でoutbound queueからframeを取り出し、PJSIPへ渡す。
- queue underrun時は無音frameを返す。
- 必要に応じてresamplingまたはformat変換を追加する。

完了条件:

- 発信者がOpenAIの音声レスポンスを聞ける。
- outbound queue underrun/dropがログで観測できる。

実装内容:

- OpenAI Realtimeの`response.output_audio.delta`を受信し、Base64 decodeする。
- OpenAI出力は24 kHz PCM16として扱い、RTP側の8 kHz PCM16へdownsamplingする。
- downsampling後の音声を20 ms単位、320 byteのPCM16 frameへ分割する。
- 分割したframeを`AudioBridge`のoutbound queueへ積む。
- `Pjsua2AudioBridgePort.onFrameRequested()`でoutbound queueからframeを取り出し、PJSUA2 conference bridgeへ返す。
- outbound queueに音声がない場合は、20 ms分の無音frameを返す。
- `AudioMediaPort`は双方向portとして利用し、`caller audio media -> bridge port`と`bridge port -> caller audio media`の両方向を接続する。

確認観点:

- OpenAIから音声deltaを受信したときに`Queued OpenAI output audio frame for RTP`が出力される。
- PJSUA2から送信frameを要求されたときに`Provided outbound RTP audio frame`が出力される。
- 通話終了時に`Closed PJSUA2 audio bridge`でinbound/outbound/silence frame数を確認できる。
- 初期実装ではOpenAI応答が到着するまで無音frameが送られるため、`outboundSilenceFrames`は一定数増える想定。

### Step 5: PCMU最適化

目的:

- latencyと変換コストを下げる。

作業:

- OpenAI側で`g711_ulaw`を使う場合のsession設定を検証する。
- PJSUA2 AudioMediaPortでPCMU payloadを直接扱えない場合、custom PJMEDIA portまたはcodec hookが必要か判断する。
- PCM16 pathとPCMU pathのlatency、音質、実装複雑度を比較する。

完了条件:

- MVPではPCM16 pathを継続するか、PCMU pathへ切り替えるか判断できる。

## 6. 追加・変更予定ファイル

想定:

```text
src/main/java/com/example/telephonygw/media/
  AudioBridge.java
  AudioFrame.java
  AudioQueue.java
  AudioFormat.java
  PcmuCodec.java
  Resampler.java

src/main/java/com/example/telephonygw/openai/
  RealtimeClient.java
  RealtimeSession.java
  RealtimeEvent.java
  RealtimeWebSocketClient.java

src/pjsua2/java/com/example/telephonygw/sip/
  Pjsua2AudioBridgePort.java
  Pjsua2Call.java
```

必要に応じて追加:

```text
native/
  pjmedia-bridge/
```

ただし、native追加は`AudioMediaPort`方式で不足が確認された場合のみ行う。

## 7. 検証計画

### 7.1 ローカル起動

```sh
scripts/run-pjsua2-local.sh config/gateway.local.yaml
```

### 7.2 SIP call検証

- 外部SIP端末からINVITEを送る。
- 200 OKで応答されることを確認する。
- `onCallMediaState()`が呼ばれることを確認する。
- `onFrameReceived()`のframe countが増えることを確認する。

### 7.3 音声入力検証

- 発話中にinbound frame countが増える。
- 無音時にもRTP/media frameが来る場合、その扱いをログで確認する。
- 20 ms packet前提に対してframe sizeが妥当か確認する。

### 7.4 OpenAI接続検証

- Realtime sessionが作成される。
- audio append eventが送信される。
- response audio deltaが返る。
- call終了時にRealtime sessionが閉じられる。

### 7.5 双方向音声検証

- 発信者がBot音声を聞ける。
- 連続発話、割り込み、無音、切断時にprocessが落ちない。

## 8. リスクと判断ポイント

### 8.1 AudioMediaPortのframe形式

`AudioMediaPort`がPCM frameを渡す場合、OpenAIへ`g711_ulaw` passthroughできない。MVPではPCM16 pathを許容し、後で最適化する。

### 8.2 sample rate mismatch

PJSIP側はPCMU 8 kHzで、OpenAI側の音声形式設定によっては24 kHz PCMが必要になる可能性がある。この場合、resamplingが必要になる。

### 8.3 callback threadでの重い処理

PJSIP media callback内でWebSocket送信や重い変換を行うと、音声途切れの原因になる。callbackではqueue投入またはqueue取得だけに限定する。

### 8.4 backpressure

OpenAI WebSocket、RTP pacing、ネットワーク状態がずれるとqueueが詰まる。bounded queueとdrop policyを初期から入れる。

### 8.5 native実装への移行

Java `AudioMediaPort`で性能や形式制御が不足した場合、custom PJMEDIA portが必要になる。この判断はStep 1からStep 4の結果で行う。

## 9. 承認後の最初の実装範囲

承認後、最初に実装する範囲はStep 1に限定する。

具体的には、OpenAI API接続はまだ行わず、実通話中にPJSUA2 Java `AudioMediaPort`でRTP由来のaudio frameを観測できるかだけを確認する。

このStep 1が通った後、Step 2以降に進む。

## 10. Step 1 実装内容

Step 1として、以下を実装した。

- `Pjsua2AudioBridgePort`を追加。
- `Pjsua2AudioBridgePort`はPJSUA2 Javaの`AudioMediaPort`を継承する。
- port formatはPCM 8 kHz / mono / 16-bit / 20 msとして作成する。
- `Pjsua2Call.onCallMediaState()`でactiveなaudio mediaを検出する。
- call側の`AudioMedia`から`Pjsua2AudioBridgePort`へ`startTransmit()`する。
- `onFrameReceived()`でframe数、byte数、frame type、前回callbackからの経過時間をログ出力する。
- call切断時に`stopTransmit()`し、bridge portをdeleteする。

この段階では、OpenAI APIへの送信、outbound RTP返送、queue処理はまだ行わない。

期待ログ:

```text
Attached PJSUA2 audio bridge: callId=..., sessionId=..., mediaIndex=...
Observed inbound audio frame: sessionId=..., callId=..., frames=1, bytes=..., type=..., deltaMs=0
Observed inbound audio frame: sessionId=..., callId=..., frames=50, bytes=..., type=..., deltaMs=...
```

検証手順:

```sh
scripts/run-pjsua2-local.sh config/gateway.local.yaml
```

その後、外部SIP端末からINVITEを送り、通話中に発話する。

確認観点:

- `Attached PJSUA2 audio bridge`が出る。
- `Observed inbound audio frame`が継続的に出る。
- frame byte数とcallback間隔から、実際のframe形式とptimeを判断する。

## 11. Step 2 実装内容

Step 2として、PJSUA2 `AudioMediaPort`で観測したinbound audio frameを、アプリケーション内のbounded queueへ投入する処理を実装した。

追加・変更内容:

- `AudioFrame`を拡張し、以下を保持するようにした。
  - session ID。
  - 音声方向。現時点では`INBOUND`のみ利用。
  - sequence number。
  - payload。
  - codec。
  - sample rate。
  - duration。
  - capture timestamp。
- `AudioQueue`を追加し、`ArrayBlockingQueue`ベースのbounded queueを実装した。
- `AudioBridge`にinbound queueを追加した。
- `AudioBridge.enqueueInboundPcm16()`を追加し、media callbackからqueueへframeを投入できるようにした。
- queueが満杯の場合はframeをdropし、drop数をログ出力する。
- `Pjsua2AudioBridgePort.onFrameReceived()`ではpayload copyとqueue投入だけを行う。
- 既存のframe観測ログにqueue depthを追加した。

現時点の形式仮定:

- `AudioMediaPort`側はPCM 8 kHz / mono / 16-bit / 20 ms。
- queue上のcodec表現は`pcm16`。
- OpenAI API送信はまだ行わない。

期待ログ:

```text
Audio queue accepted frame: queue=inbound, offered=1, dropped=0, depth=1
Observed inbound audio frame: sessionId=..., callId=..., frames=50, bytes=..., type=..., deltaMs=..., queueDepth=...
```

確認観点:

- 通話中に`Audio queue accepted frame`が出る。
- queue depthが増える。
- OpenAI送信側が未実装のため、長時間通話ではqueueが満杯になりdrop logが出る可能性がある。
- dropが出てもprocessが落ちない。

## 12. Step 3 実装内容

Step 3として、inbound audio queueを消費し、OpenAI Realtime APIへ音声chunkを送信する処理を実装した。

追加・変更内容:

- `RealtimeClient`にaudio forwarding workerを追加した。
- `GatewayApp.start()`で`AudioBridge`初期化後に、`RealtimeClient.startAudioForwarding()`を開始する。
- forwarding workerは`AudioBridge.inboundQueue()`からframeを取り出す。
- call session IDごとに`RealtimeSession`を作成し、OpenAI Realtime WebSocketへ接続する。
- WebSocket接続先は`wss://api.openai.com/v1/realtime?model=...`とする。
- 認証は`Authorization: Bearer <openai.apiKey>` headerを利用する。
- 接続後に`session.update`を送信し、Realtime sessionの入力音声形式を設定する。
- `input_audio_buffer.append` eventでBase64化したaudio chunkを送信する。
- server VADを利用し、初期実装では明示的な`input_audio_buffer.commit`は送信しない。
- OpenAI側から返る主要eventをログ出力する。
- queue上のPCM16 8 kHz frameを、OpenAI送信直前にPCM16 24 kHzへ単純3倍upsampleする。
- sessionは最後のframe送信から一定時間経過した場合にidle closeする。

現時点の形式:

- PJSUA2側: PCMUでRTP negotiation。
- Java queue側: PCM16 8 kHz / mono / 20 ms。
- OpenAI Realtime入力側: PCM16 24 kHz / mono。
- OpenAI Realtime出力側: PCM16 24 kHz / mono。

期待ログ:

```text
Started OpenAI Realtime audio forwarding worker
OpenAI Realtime WebSocket connected: sessionId=...
Opened OpenAI Realtime session: sessionId=..., model=gpt-realtime, inputRateHz=24000
Received OpenAI Realtime event: sessionId=..., type=session.created
Received OpenAI Realtime event: sessionId=..., type=session.updated
Forwarded inbound audio frame to OpenAI Realtime: sessionId=..., frames=1
Received OpenAI Realtime event: sessionId=..., type=input_audio_buffer.speech_started
Received OpenAI Realtime event: sessionId=..., type=input_audio_buffer.speech_stopped
```

確認観点:

- 通話開始後、最初のaudio frame到着時にOpenAI Realtime WebSocketが接続される。
- `session.created`および`session.updated`が返る。
- inbound queueが消費され、Step 2で見られたqueue overflowが継続しない。
- `input_audio_buffer.speech_started`や`speech_stopped`が返れば、OpenAI側で入力音声が受理されていると判断できる。
- API key、model、音声形式、rate limit、network failureなどのerrorは`OpenAI Realtime WebSocket error`または`Received OpenAI Realtime event: type=error`で確認する。

未実装:

- OpenAI音声出力のRTP返送。
- response audio deltaのoutbound queue投入。
- barge-inや応答キャンセル制御。
- 高品質resampling。現時点では動作確認優先の単純upsample。
