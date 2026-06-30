# RHELデモ環境への差分反映手順

## 目的

既存のRHEL 8.10デモ環境へ、開発環境で作成したアプリケーション更新を反映する手順を整理する。

本手順は、すでに以下が完了している環境を対象とする。

- `/opt/telephony-openai-gw`へRepositoryが配置済み
- `telephonygw` userが作成済み
- PJSIP/PJSUA2 Java bindingがRHEL上でbuild済み
- `telephony-openai-gw.service`がsystemdに登録済み
- `config/gateway.local.yaml`がRHEL環境用に作成済み

## 今回の更新内容

対象commit:

```text
437ab00 Support independent multi-session gateway slots
```

主な変更:

- 複数セッションスロット対応
- スロットごとのSIP transport/account/registration対応
- スロットごとのOpenAI/Bot設定対応
- inbound/outbound audio queueのセッション分離
- `media.inboundQueueCapacity` / `media.outboundQueueCapacity`設定追加
- OpenAI inbound音声転送workerのセッション別化
- `config/gateway.local.yaml`の設定形式変更

## RHELへ持っていくもの

### 必須

- Repository差分
  - GitHub branch/commit
  - または `git format-patch` で作成したpatch file
  - または `git archive` で作成した更新済みsource archive
- RHEL用に更新した `config/gateway.local.yaml`

### 持っていかないもの

- `.deps/`配下のmacOS build成果物
- `build/`配下のcompile成果物
- `apl.log`などのlog file
- `.env`
- OpenAI API keyを含むcommit対象ファイル
- 実SIP passwordを含むcommit対象ファイル

`config/gateway.local.yaml`はgit管理外の実行設定であり、デモ用途ではSIP passwordを平文で記載してよい。ただし、転送時の扱いには注意する。

## 推奨方式

既存RHEL環境にgit clone済みで、GitHubへ今回のbranch/commitをpushできる場合は、GitHub経由の更新を推奨する。

GitHubへpushできない、またはRHEL側からGitHub private repositoryへアクセスできない場合は、patch file転送方式を使う。

## 方式A: GitHub branch/commitから更新する

### 開発環境で実施

今回のbranchをGitHubへpushする。

```sh
git push origin codex/multi-session-support
```

mainへmerge済みの場合は、RHEL側では`main`をpullするだけでよい。merge前にRHELへ先行反映する場合は、RHEL側で`codex/multi-session-support`をcheckoutする。

### RHEL側で実施

EC2へSSH接続したOS userで作業する。root権限が必要な操作には`sudo`を付ける。

```sh
cd /opt/telephony-openai-gw
sudo systemctl stop telephony-openai-gw
```

設定ファイルを退避する。

```sh
sudo -u telephonygw cp config/gateway.local.yaml config/gateway.local.yaml.bak-$(date +%Y%m%d%H%M%S)
```

mainへmerge済みの場合:

```sh
sudo -u telephonygw git fetch origin
sudo -u telephonygw git checkout main
sudo -u telephonygw git pull --ff-only origin main
```

merge前branchを先行適用する場合:

```sh
sudo -u telephonygw git fetch origin
sudo -u telephonygw git checkout codex/multi-session-support
sudo -u telephonygw git pull --ff-only origin codex/multi-session-support
```

## 方式B: patch fileを転送して適用する

RHEL側からGitHubへ接続しない場合、開発環境でpatch fileを作成してRHELへ転送する。

### 開発環境で実施

今回の変更は、`ae83a3c`の次のcommit `437ab00`として作成されている。

```sh
git format-patch --stdout ae83a3c..437ab00 > telephony-openai-gw-multi-session.patch
scp telephony-openai-gw-multi-session.patch user@rhel-host:/tmp/
```

### RHEL側で実施

```sh
cd /opt/telephony-openai-gw
sudo systemctl stop telephony-openai-gw
sudo -u telephonygw cp config/gateway.local.yaml config/gateway.local.yaml.bak-$(date +%Y%m%d%H%M%S)
sudo -u telephonygw git apply --check /tmp/telephony-openai-gw-multi-session.patch
sudo -u telephonygw git apply /tmp/telephony-openai-gw-multi-session.patch
```

RHEL側のRepositoryがgit管理されており、履歴として残したい場合:

```sh
sudo -u telephonygw git add .
sudo -u telephonygw git commit -m "Apply multi-session gateway update"
```

patch適用に失敗した場合は、RHEL側のsourceが開発環境の前提commitとずれている可能性がある。`git log --oneline -5`で現在のcommitを確認し、GitHub経由更新またはarchive差し替え方式へ切り替える。

## 方式C: 更新済みarchiveを転送して差し替える

RHEL側にgit履歴を残さない運用の場合は、更新済みsource archiveを転送して差し替える。

### 開発環境で実施

```sh
git archive --format=tar.gz --output=telephony-openai-gw-update.tar.gz codex/multi-session-support
scp telephony-openai-gw-update.tar.gz user@rhel-host:/tmp/
```

### RHEL側で実施

```sh
cd /opt/telephony-openai-gw
sudo systemctl stop telephony-openai-gw
sudo cp config/gateway.local.yaml /tmp/gateway.local.yaml.bak-$(date +%Y%m%d%H%M%S)
sudo rm -rf /opt/telephony-openai-gw.new
sudo mkdir -p /opt/telephony-openai-gw.new
sudo tar -xzf /tmp/telephony-openai-gw-update.tar.gz -C /opt/telephony-openai-gw.new
sudo cp /tmp/gateway.local.yaml.bak-* /opt/telephony-openai-gw.new/config/gateway.local.yaml
sudo chown -R telephonygw:telephonygw /opt/telephony-openai-gw.new
sudo mv /opt/telephony-openai-gw /opt/telephony-openai-gw.prev-$(date +%Y%m%d%H%M%S)
sudo mv /opt/telephony-openai-gw.new /opt/telephony-openai-gw
```

この方式では、既存の`.deps/pjproject`やPJSUA2 build成果物も退避側へ移動するため、後続のPJSIP buildを必ず再実行する。

## gateway.local.yamlの更新

今回の更新では設定形式が変わるため、既存の単一セッション形式の`config/gateway.local.yaml`はそのまま使えない。

`config/gateway.pjsua2.example.yaml`を参考に、RHEL環境用の`config/gateway.local.yaml`を作成する。

最小例:

```yaml
gateway:
  sessionIds: session-1,session-2

session.session-1:
  name: 一番窓口
  sip.backend: pjsua2
  sip.bindAddress: 0.0.0.0
  sip.port: 5062
  sip.transport: UDP
  sip.ipVersion: IPv4
  sip.codec: PCMU
  sip.preferredCodec: G722
  sip.codecs: G722,PCMU
  sip.publicContactAddress: ""
  sip.rtpPortStart: 40000
  sip.rtpPortEnd: 41000
  registration.domain: example.com
  registration.userName: 2000
  registration.password: replace-with-password
  registration.sipAddress: sip:2000@example.com
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
  bot.systemInstructions: "あなたはsession-1の電話応対を行うAIアシスタントです。回答は短くしてください。"
  bot.initialGreeting: "こちらは一番窓口です。ご用件をお話しください。"

session.session-2:
  name: 二番窓口
  sip.backend: pjsua2
  sip.bindAddress: 0.0.0.0
  sip.port: 5064
  sip.transport: UDP
  sip.ipVersion: IPv4
  sip.codec: PCMU
  sip.preferredCodec: G722
  sip.codecs: G722,PCMU
  sip.publicContactAddress: ""
  sip.rtpPortStart: 41002
  sip.rtpPortEnd: 42000
  registration.domain: example.com
  registration.userName: 2100
  registration.password: replace-with-password
  registration.sipAddress: sip:2100@example.com
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
  bot.systemInstructions: "あなたはsession-2の電話応対を行うAIアシスタントです。回答は短くしてください。"
  bot.initialGreeting: "こちらは二番窓口です。ご用件をお話しください。"

logging:
  level: INFO

media:
  inboundQueueCapacity: 500
  outboundQueueCapacity: 10000

openai:
  cancelResponseOnUserSpeech: false

monitor:
  enabled: true
  bindAddress: 127.0.0.1
  port: 8080
  maxEvents: 500
  sessionHistoryDepth: 10
```

注意:

- `session.<slotId>.sip.port`はスロットごとに異なる値にする。
- `session.<slotId>.name`はモニター画面に表示する名前として指定する。
- `session.<slotId>.registration.*`はスロットごとに個別設定する。
- `session.<slotId>.sip.rtpPortStart` / `rtpPortEnd`はスロット間で重複しない範囲を推奨する。
- EC2 Security Groupとfirewalldで、利用するSIP/RTP/RTCP portを許可する。
- `media.inboundQueueCapacity` / `media.outboundQueueCapacity`は全セッション共通値。queue自体は通話セッションごとに作成される。

## RHEL上でのbuildと確認

source更新後は、まず設定ファイルを確認し、その後PJSIP/PJSUA2 Java bindingをbuildする。`check-pjsua2-java.sh`はbuild成果物である`libpjsua2.so`とPJSUA2 Java binding classを確認・実行するscriptなので、`build-pjsip-rhel.sh`の後に実行する。

```sh
cd /opt/telephony-openai-gw
sudo -u telephonygw scripts/check-config.sh config/gateway.local.yaml
sudo -u telephonygw scripts/build-pjsip-rhel.sh
sudo -u telephonygw scripts/check-pjsua2-java.sh
```

`build-pjsip-rhel.sh`は既存の`.deps/pjproject`がある場合、差分buildになる。archive差し替え方式で`.deps`を失った場合、またはPJSIP bindingに不整合がある場合は再buildされる。

`check-pjsua2-java.sh`で以下のエラーが出る場合は、PJSIP buildが未実行、失敗、またはarchive差し替えで`.deps`が失われている状態である。先に`sudo -u telephonygw scripts/build-pjsip-rhel.sh`を実行する。

```text
PJSUA2 Java binding classが見つかりません。先に利用OS向けのPJSIP build scriptを実行してください。
```

`check-pjsua2-java.sh`で以下のOpenH264警告が出ても、最後に`PJSUA2 Java binding check completed.`が出ていれば確認成功として扱う。本Gatewayは音声のみを扱いvideo codecを使わないため、OpenH264 native libraryが無いことは問題ではない。

```text
Failed to load native library openh264
java.lang.UnsatisfiedLinkError: no openh264 in java.library.path: ...
This could be safely ignored if you don't use OpenH264 video codec.
PJSUA2 Java binding check completed.
```

Java compileを明示的に確認する場合:

```sh
sudo -u telephonygw javac -encoding UTF-8 \
  -cp .deps/pjproject/pjsip-apps/src/swig/java/output \
  -d build/classes \
  $(find src/main/java src/pjsua2/java -name '*.java' -print)
```

## systemd service更新

service fileに変更がある場合のみ、再配置する。

```sh
sudo cp /opt/telephony-openai-gw/deploy/systemd/telephony-openai-gw.service /etc/systemd/system/telephony-openai-gw.service
sudo systemctl daemon-reload
```

今回のmulti-session更新では、通常はservice fileの再配置は不要。ただし、RHEL環境のservice fileが古い場合は再配置する。

## 起動

```sh
sudo systemctl start telephony-openai-gw
sudo systemctl status telephony-openai-gw
```

ログ確認:

```sh
sudo journalctl -u telephony-openai-gw -f
```

要約ログだけ確認:

```sh
sudo journalctl -u telephony-openai-gw --since "10 minutes ago" | grep 'GW_EVENT'
```

起動時に確認するイベント:

```text
GW_EVENT event=audio_bridge_initialized inboundQueueCapacity=500 outboundQueueCapacity=10000
GW_EVENT event=openai_audio_forwarding_enabled
```

通話時に確認するイベント:

```text
GW_EVENT event=openai_audio_forwarder_started sessionId=... slotId=session-1
GW_EVENT event=audio_queue_created sessionId=... direction=inbound ... capacity=500
GW_EVENT event=audio_queue_created sessionId=... direction=outbound ... capacity=10000
GW_EVENT event=openai_response_done ... responseDroppedFrames=0
GW_EVENT event=openai_audio_forwarder_stopped sessionId=...
```

## 切り戻し

GitHub/git方式の場合:

```sh
cd /opt/telephony-openai-gw
sudo systemctl stop telephony-openai-gw
sudo -u telephonygw git log --oneline -5
sudo -u telephonygw git checkout <previous-good-commit-or-branch>
sudo systemctl start telephony-openai-gw
```

archive差し替え方式の場合:

```sh
sudo systemctl stop telephony-openai-gw
sudo mv /opt/telephony-openai-gw /opt/telephony-openai-gw.failed-$(date +%Y%m%d%H%M%S)
sudo mv /opt/telephony-openai-gw.prev-* /opt/telephony-openai-gw
sudo chown -R telephonygw:telephonygw /opt/telephony-openai-gw
sudo systemctl start telephony-openai-gw
```

## 反映後の確認観点

- 2つのSIP accountがRegistration成功すること。
- `session-1`と`session-2`へ別々に発信できること。
- 2セッション同時通話で、双方に`openai_audio_forwarder_started`が出ること。
- `audio_queue_frame_dropped`が出ないこと。
- `openai_output_audio_frame_dropped`が出ないこと。
- `openai_response_done`の`responseDroppedFrames=0`であること。
- 通話終了時に`openai_audio_forwarder_stopped`が出ること。
- 切断時に`clearedOutboundFrames`が残る場合は、queue overflowではなく未再生のAI音声が残っている状態として扱う。
