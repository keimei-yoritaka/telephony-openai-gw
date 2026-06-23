# G.722対応 実装計画

## 目的

現行のSIP/RTP codecはPCMU固定である。今後、G.722を追加し、G.722を優先しつつPCMUへfallbackできるようにする。

OpenAI Realtime APIとの境界は引き続きPCM16を利用し、RTP payloadのG.722 decode/encodeはPJSIP/PJMEDIAに任せる方針とする。

## 現状

- `sip.codec`は`PCMU`のみ許可している。
- PJSUA2起動時に`PCMU/8000`のみpriority 255、それ以外をpriority 0にしている。
- `Pjsua2AudioBridgePort`は`8000Hz / 20ms / 320bytes`のPCM16固定で動作している。
- OpenAI Realtime sessionは`pcm16 24000Hz`で入出力している。
- resamplerは整数倍変換のみ対応しており、`8000Hz <-> 24000Hz`は扱えるが、`16000Hz <-> 24000Hz`は扱えない。

## 基本方針

G.722対応では、アプリ自身でG.722 RTP payloadを直接decode/encodeしない。

```text
RTP G.722 / PCMU
  -> PJSIP/PJMEDIA codec
  -> PJSUA2 AudioMediaPort PCM16
  -> OpenAI Realtime PCM16 24000Hz
  -> PJSUA2 AudioMediaPort PCM16
  -> PJSIP/PJMEDIA codec
  -> RTP G.722 / PCMU
```

この方式により、SIP/SDP/RTP codec処理はPJSIPに寄せ、Gateway本体はPCM16 audio bridgeとsample rate変換に集中する。

## 実装ステップ

### Step 1: G.722 codec利用可否の確認

- PJSUA2起動時に`codecEnum2()`を実行し、利用可能codec一覧をINFOログへ出す。
- `G722`と`PCMU`が利用可能かを明示的にログへ出す。
- codec policyはまだPCMU固定のまま維持する。
- 実SIP環境で起動し、PJSIP buildにG.722 codecが含まれているか確認する。

状態: 実装済み。

### Step 2: codec設定モデルの拡張

- `sip.codec`固定から、複数codec指定へ拡張する。
- 初期案:

```yaml
sip:
  preferredCodec: G722
  codecs: G722,PCMU
```

- 既存の`codec: PCMU`設定との互換性を残す。
- 設定検証では`G722`、`PCMU`のみ許可する。
- 現行の簡易YAML loaderは配列記法を扱わないため、`codecs`はカンマ区切り文字列とする。

状態: 実装済み。`sip.codec`は後方互換用に維持し、`sip.preferredCodec`と`sip.codecs`を追加した。PJSUA2 codec priority制御はまだPCMU固定のまま維持する。

### Step 3: PJSUA2 codec priority制御の汎用化

- `preferPcmuCodec()`を廃止または汎用化する。
- `G722`をpriority 255、`PCMU`をfallback priorityに設定する。
- 設定に含まれないcodecはpriority 0にする。
- 200 OK SDPでG.722が選択されることを確認する。

状態: 実装済み。`sip.preferredCodec`をpriority 255、`sip.codecs`内のfallback codecを低いpriorityに設定し、その他codecはpriority 0にする。example設定は`preferredCodec: G722`、`codecs: G722,PCMU`へ変更した。

### Step 4: 通話ごとのmedia format対応

- 実際にnegotiatedされたcodecまたはPJSUA2 media formatを確認する。
- `PCMU`時は`8000Hz / 20ms / 320bytes`を維持する。
- `G722`時はPJSUA2 conference bridgeで扱うPCMのsample rateを確認し、想定どおりなら`16000Hz / 20ms / 640bytes`へ切り替える。
- G.722のSDP表記は慣例的に`G722/8000`だが、実音声はwidebandである点に注意する。

状態: 実装済み。`Call.getStreamInfo()`からnegotiated codec名とclock rateを取得し、`G722`時はAudioMediaPortを`16000Hz / 20ms / 640bytes`、PCMU時は`8000Hz / 20ms / 320bytes`で作成する。`sip_media_format`と`rtp_audio_bridge_attached`にcodecとsample rateを出力する。

### Step 5: resampler拡張

- `16000Hz <-> 24000Hz`に対応するPCM16 resamplerを追加する。
- 初期実装は線形補間でよいが、音質が不足する場合は品質改善を検討する。
- 既存の`8000Hz <-> 24000Hz`も同じ実装で扱えるようにする。

状態: 実装済み。PCM16 resamplerを線形補間ベースに変更し、整数倍以外の`16000Hz <-> 24000Hz`にも対応した。OpenAI応答音声はAudioBridgeに保持したsession sample rateへ変換してからRTP側queueへ投入する。

### Step 6: 実通話検証

- 外部SIP phoneからG.722とPCMUを含むSDP offerを送る。
- Gatewayの200 OK SDPでG.722が選択されることを確認する。
- AIへの音声転送、AI音声のRTP返送、会話モニターUI、`CALL_TRANSCRIPT`が正常に動くことを確認する。
- G.722非対応端末ではPCMUへfallbackすることを確認する。

状態: 未実施。実SIP環境での確認待ち。

### 追加対応: OpenAI session作成競合の抑止

実通話ログ上、同一call sessionに対してOpenAI Realtime WebSocketが短時間に2本作成され、片方が即時closeされる可能性が見つかった。

原因は、通話開始時の初期挨拶用スレッドと、RTP受信後のaudio forwarding workerが同時に`sessionFor()`へ入り、`sessions.putIfAbsent()`より前にそれぞれWebSocketをopenできてしまう構造にあった。

対策として、call session ID単位のlockを追加し、同じsession IDでは`openSession()`を同時実行しないようにした。これにより、同一call sessionに対するOpenAI Realtime sessionは1本だけ作成される。

状態: 実装済み。実通話ログで`openai_websocket_connected`と`openai_realtime_session_opened`が同一session IDにつき1回だけ出ることを確認する。

## リスク

- PJSIP buildにG.722 codecが含まれていない可能性がある。
- PJSUA2 `AudioMediaPort`がG.722時にどのsample rateでcallbackするかは実測確認が必要。
- `16000Hz <-> 24000Hz`変換品質が音声品質に影響する。
- codec priority変更により、既存PCMU端末との相互接続へ影響する可能性がある。

## 現時点の判断

G.722対応は可能と判断する。ただし、最初にPJSUA2 codec一覧でG.722利用可否を確認し、その結果を見てからSDP/codec policy変更へ進む。
