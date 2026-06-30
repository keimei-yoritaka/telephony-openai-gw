# Telephony OpenAI Gateway

SIP/RTPによる電話音声とOpenAIの音声Botを接続する軽量Gatewayを構築するプロジェクトです。

JavaアプリケーションからPJSUA2 Java bindingを利用し、SIP Registration、UDP/IPv4のSIP/RTP、G.722/PCMU音声、OpenAI Realtime API連携を実装します。

## 現在の状態

- GitHub Repositoryは`telephony-openai-gw`としてprivateで管理しています。
- SIP backendは`placeholder`と`pjsua2`を設定で切り替えられます。
- PJSUA2 backendでは、複数の固定セッションスロットごとにUDP transport、SIP Account、Registrationを作成します。
- 各セッションスロットは最大1コールを受け付け、OpenAI Realtime sessionと1:1で紐付きます。
- OpenAI voice、system instructions、initial greetingはセッションスロットごとに設定できます。
- inbound/outbound audio queueは通話セッションごとに分離し、capacityは`media`設定で共通値として指定できます。
- OpenAIへのinbound音声転送workerは通話セッションごとに起動します。

## ローカル確認

Java 21を利用します。

設定ファイルの読み込み確認:

```sh
scripts/check-config.sh
```

アプリケーション起動:

```sh
scripts/run-local.sh
```

`gradle`または`gradlew`が利用できる場合、`scripts/run-local.sh`はGradle経由で起動します。どちらも無い場合は、外部依存なしの範囲で`javac`によりcompileして起動します。

`config/gateway.example.yaml`はplaceholder backendの複数スロット設定例です。PJSUA2を使う場合は`config/gateway.pjsua2.example.yaml`を雛形にして、スロットごとのSIP Registration情報とOpenAI API keyを実環境値に置き換えてください。

デモ用途の実行設定である`config/gateway.local.yaml`はgit管理外です。このファイルに限り、スロットごとの`registration.password`へSIP passwordを平文で記載して構いません。`config/*.example.yaml`やREADME、設計文書などcommit対象のファイルには、実passwordやAPI keyを書かずplaceholderを使います。

音声queueのcapacityは全セッション共通値として`media`セクションで指定します。queue自体は通話セッションごとに作成されます。

```yaml
media:
  inboundQueueCapacity: 500
  outboundQueueCapacity: 10000
```

PJSUA2 Java bindingのbuild確認:

```sh
scripts/bootstrap-macos-deps.sh
scripts/build-pjsip-macos.sh
scripts/check-pjsua2-java.sh
```

RHEL 8.10向けの依存導入とPJSIP/PJSUA2 Java binding build:

```sh
scripts/bootstrap-rhel-deps.sh
sudo -u telephonygw scripts/build-pjsip-rhel.sh
scripts/check-pjsua2-java.sh
```

PJSUA2 backendの起動確認:

```sh
scripts/run-pjsua2-startup-check.sh
```

この確認では`config/gateway.pjsua2.example.yaml`を使用します。macOSのサンドボックス環境ではUDP bindに権限許可が必要になる場合があります。

PJSUA2 backendで外部INVITEを待ち受ける通常起動:

```sh
scripts/run-pjsua2-local.sh config/gateway.local.yaml
```

`run-pjsua2-startup-check.sh`は起動確認用のため、Registration開始後に停止する。INVITEを待ち受ける場合は`run-pjsua2-local.sh`を利用する。

確認時にOpenH264 native libraryが見つからない警告が出る場合があります。PJSUA2 Java bindingの生成コードがvideo codec用libraryの読み込みを試行するためで、本プロジェクトのMVPではvideoを使わないため現時点では無害です。

## ログ確認

ローカル検証では、PJSIP nativeの詳細ログとアプリケーションログが同じstdout/stderrへ出力されます。全量ログは調査用として残しつつ、通話フローだけを追う場合は`GW_EVENT`を抽出します。

```sh
scripts/run-pjsua2-local.sh config/gateway.local.yaml > apl.log 2>&1
grep 'GW_EVENT' apl.log
```

`GW_EVENT`は`event=... sessionId=...`形式の要約ログです。代表的なイベントは以下です。

- `call_session_created`: 通話セッション作成。
- `sip_call_answered`: INVITEへ200 OK応答。
- `rtp_audio_bridge_attached`: RTP音声ブリッジ接続。
- `openai_initial_greeting_requested`: 初回挨拶をOpenAIへ要求。
- `openai_user_speech_started`: ユーザー発話検出。AI応答を割り込んだ場合は`interruptedResponse=true`。
- `openai_response_latency`: OpenAI応答遅延。`commitToFirstAudioMs`はユーザー音声commitから最初の音声deltaまでの実測値。
- `openai_response_done`: AI応答生成完了。
- `rtp_audio_bridge_closed`: RTP音声ブリッジ終了サマリー。
- `call_session_closed`: 通話セッション終了。

会話内容の確認には`CALL_TRANSCRIPT`を抽出します。

```sh
grep 'CALL_TRANSCRIPT' apl.log
```

発信者側の音声認識結果は`speaker=caller`、OpenAI側の音声応答transcriptは`speaker=assistant`として出力されます。発信者側transcriptionはスロットごとの`openai.transcriptLoggingEnabled`で有効化され、`openai.inputTranscriptionModel`と`openai.inputTranscriptionLanguage`でmodelと言語ヒントを指定します。transcriptには通話内容が含まれるため、logの保存・共有時は個人情報や機密情報の扱いに注意してください。

アプリケーション側のログレベルは`config/*.yaml`の`logging.level`で指定します。対応値は`TRACE`、`DEBUG`、`INFO`、`WARN`、`WARNING`、`ERROR`です。

`INFO`はデモ運用向けの既定値です。通話開始/終了、Registration、OpenAI response、会話transcript、警告/エラーは出力しますが、RTP frame単位、audio queue受理、OpenAI input frame転送、PJSIP SIP message dumpなどの高頻度診断ログは出力しません。これらを確認する場合は`logging.level: DEBUG`または`TRACE`を指定します。

## OpenAI応答中の割り込み

`openai.cancelResponseOnUserSpeech: true`を指定すると、アプリがAI音声を送話中でも、発信者側の発話開始をOpenAI Realtime APIが検知した時点でAI音声の送出をキャンセルします。キャンセル時はRTP送信用のOutbound audio queueをクリアし、OpenAIへ`response.cancel`を送信します。

デフォルト値は`false`です。短い相づちや環境音でAI音声が止まりやすくなる副作用があるため、デモ内容に合わせて有効化してください。有効化した場合も、以下の条件を満たした場合だけキャンセルします。

```yaml
openai:
  cancelResponseOnUserSpeech: true
  bargeInMinSpeechMs: 600
  bargeInMinRmsDb: -35.0
  bargeInGraceMsAfterAssistantStarts: 500
```

- `bargeInMinSpeechMs`: `speech_started`後、この時間以上発話が継続してからキャンセル候補にする。
- `bargeInMinRmsDb`: 入力PCMフレームのRMS音量がこの値以上の場合だけキャンセルする。
- `bargeInGraceMsAfterAssistantStarts`: AI音声の送出開始直後、この時間内はキャンセルしない。

## 会話モニターUI

`monitor.enabled: true`の場合、ブラウザから会話モニターを確認できます。

```sh
open http://127.0.0.1:8080/
```

会話モニターの発話イベント保持数は`monitor.maxEvents`、右側のセッション選択リストに残す最近のセッション数は`monitor.sessionHistoryDepth`で指定します。`monitor.sessionHistoryDepth`のデフォルト値は`10`です。
右側のセッション選択リストには`session.<slotId>.name`を表示します。未指定の場合は`slotId`を表示します。

RHEL EC2で外部ブラウザから確認する場合は、`monitor.bindAddress: 0.0.0.0`を指定し、security groupで接続元IPを制限してください。認証なしのデモ機能のため、広く公開しないでください。

### stdout/stderr運用について

現時点ではstdout/stderrへ出す構成を維持します。systemd、container、CIでは標準出力集約が扱いやすく、PJSIP nativeログも同じプロセスから出るため、まずは起動元でファイル化またはjournaldへ集約する方針が単純です。

本番運用で長期間ログを保持する段階では、JavaアプリログをJSON Linesまたは専用file appenderへ分離し、PJSIP nativeログとは別ファイルに分ける構成を検討します。その場合も、SIP/RTP障害調査ではPJSIP詳細ログが必要になるため、完全に捨てずにrotation対象として保持します。

## 主要ドキュメント

- [システム設計書](docs/system-design.md)
- [要件・実装計画](docs/requirements-and-implementation-plan.md)
- [デプロイメントガイド](docs/deployment-guide.md)
- [RHELデモ環境への差分反映手順](docs/rhel-update-patch-guide.md)
- [PJSUA2 Java Binding 調査メモ](docs/pjsua2-java-notes.md)
- [RTP / OpenAI 音声ブリッジ実装計画](docs/rtp-media-bridge-implementation-plan.md)
- [会話モニターUI 要件・設計メモ](docs/conversation-monitor-requirements-and-design.md)
- [Agent向け指示](AGENTS.md)
