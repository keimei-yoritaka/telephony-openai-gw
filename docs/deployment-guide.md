# デプロイメントガイド

## 目的

本ドキュメントは、Telephony OpenAI Gatewayを以下の2環境で並行して扱うためのモジュール構成とデプロイメント手順を整理する。

- 開発・検証環境: macOS
- 顧客向けデモ環境: RHEL 8.10

SIP/RTP、PJSUA2 Java binding、OpenAI Realtime APIの基本構成は両環境で同一とし、OS差分はnative library build、service管理、firewall設定に閉じ込める。

## 共通モジュール構成

Repositoryに含めるモジュール:

- Java source: `src/main/java/`
- PJSUA2連携source: `src/pjsua2/java/`
- ブラウザUI resource: `src/main/resources/`
- 設定例: `config/*.example.yaml`
- 起動・確認script: `scripts/`
- systemd unit例: `deploy/systemd/`
- 設計・運用文書: `docs/`

Repositoryに含めないモジュール:

- PJSIP/PJSUA2 native build成果物: `.deps/`
- 実環境設定: `config/gateway.local.yaml`、`/etc/telephony-openai-gw/gateway.yaml`
- secret: `OPENAI_API_KEY`、SIP password、private certificate
- log: `apl.log`、systemd journal export、temporary file

PJSIP/PJSUA2 native成果物は、各OS上で以下へ生成する。

```text
.deps/pjproject/pjsip-apps/src/swig/java/output/
```

native library名はOSにより異なる。

- macOS: `libpjsua2.jnilib`
- RHEL/Linux: `libpjsua2.so`

## macOS開発環境

### 前提

- macOS Apple Silicon
- Command Line Tools
- Homebrew
- Java 21
- SWIG

### 依存tool導入

```sh
scripts/bootstrap-macos-deps.sh
```

`bootstrap-macos-deps.sh`はHomebrew経由でSWIGを確認・導入する。Java 21は手元のJDKを利用する。

### PJSIP/PJSUA2 Java binding build

```sh
scripts/build-pjsip-macos.sh
```

クリーン再build:

```sh
PJSIP_CLEAN=1 scripts/build-pjsip-macos.sh
```

version指定:

```sh
PJSIP_VERSION=2.17 scripts/build-pjsip-macos.sh
```

### 起動確認

```sh
scripts/check-config.sh
scripts/check-pjsua2-java.sh
scripts/run-pjsua2-startup-check.sh config/gateway.pjsua2.example.yaml
```

`scripts/check-pjsua2-java.sh`実行時にOpenH264 native libraryが見つからない警告が出ても、最後に`PJSUA2 Java binding check completed.`が出ていれば確認成功として扱う。本Gatewayは音声のみを扱いvideo codecを使わないため、OpenH264警告は無害である。

実SIP環境で待ち受ける場合:

```sh
scripts/run-pjsua2-local.sh config/gateway.local.yaml
```

ログを保存して要約イベントだけ確認する場合:

```sh
scripts/run-pjsua2-local.sh config/gateway.local.yaml > apl.log 2>&1
grep 'GW_EVENT' apl.log
```

## RHEL 8.10デモ環境

既存RHELデモ環境へアプリケーション差分を反映する場合は、初回導入手順ではなく[RHELデモ環境への差分反映手順](rhel-update-patch-guide.md)を参照する。

### 前提

- AWS EC2上のRHEL 8.10 x86_64
- EC2へSSH接続できるOS userを利用する。root userで直接作業せず、root権限が必要な操作は`sudo`で実行する。
- Red Hat subscriptionが有効で、BaseOS/AppStream repositoryを利用できる
- outbound HTTPSでOpenAI APIへ接続できる
- SIP registrarへUDP 5060で到達できる
- RTP用UDP 40000-41000を必要範囲で送受信できる。RTCP用にRTP port + 1も使うため、firewall/NATでは41001まで許可する。
- EC2 Security Groupで、SIP用UDP 5060およびRTP/RTCP用UDP 40000-41001のInboundを必要な送信元から許可する。
- 会話モニターUIを外部ブラウザから確認する場合のみ、TCP 8080を必要な送信元IPから許可する。認証なしのデモ機能のため、`0.0.0.0/0`へ公開しない。

### サーバへ配置する資材

RHELサーバでは、まず本Repository一式を`/opt/telephony-openai-gw`へ配置する。以降の`bootstrap-rhel-deps.sh`、`build-pjsip-rhel.sh`、`run-pjsua2-local.sh`はこのRepository内のscriptとして実行する。

以下のサーバ側commandは、EC2へSSH接続したOS userで実行する。`/opt`や`/etc`への書き込み、user作成、service登録などroot権限が必要な操作は`sudo`を付ける。

配置対象:

- Repository source一式
- `scripts/`配下の導入・build・起動script
- `config/gateway.pjsua2.example.yaml`
- `deploy/systemd/telephony-openai-gw.service`
- `docs/`配下の運用文書

配置しないもの:

- `config/gateway.local.yaml`
- `.env`
- OpenAI API key
- SIP password
- `.deps/`配下のmacOS build成果物
- `apl.log`などのlog file

配置方法は、以下のいずれかを選ぶ。初回導入では、`bootstrap-rhel-deps.sh`実行前にgitが未導入でも使える「GitHub archiveをcurlで取得する場合」を推奨する。

#### GitHub archiveをcurlで取得する場合

RHELサーバに`curl`と`unzip`が導入済みである前提とする。private repositoryのため、GitHub tokenまたはfine-grained personal access tokenを事前に用意する。tokenには本Repositoryのcontents read権限が必要。

```sh
export GITHUB_TOKEN=replace-with-github-token
id telephonygw >/dev/null 2>&1 || sudo useradd --system --home-dir /opt/telephony-openai-gw --shell /sbin/nologin telephonygw
curl -L \
  -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -o /tmp/telephony-openai-gw.zip \
  https://github.com/keimei-yoritaka/telephony-openai-gw/archive/refs/heads/main.zip
sudo rm -rf /opt/telephony-openai-gw
sudo mkdir -p /opt/telephony-openai-gw
sudo rm -rf /tmp/telephony-openai-gw-unpack
sudo unzip -q /tmp/telephony-openai-gw.zip -d /tmp/telephony-openai-gw-unpack
sudo cp -a /tmp/telephony-openai-gw-unpack/telephony-openai-gw-main/. /opt/telephony-openai-gw/
sudo chown -R telephonygw:telephonygw /opt/telephony-openai-gw
rm -f /tmp/telephony-openai-gw.zip
sudo rm -rf /tmp/telephony-openai-gw-unpack
unset GITHUB_TOKEN
```

#### GitHubからcloneする場合

この方法は、RHELサーバにgitが導入済みである場合、または`bootstrap-rhel-deps.sh`実行後に利用する。private repositoryのため、GitHub credentialまたはdeploy keyを事前に用意する。

```sh
id telephonygw >/dev/null 2>&1 || sudo useradd --system --home-dir /opt/telephony-openai-gw --shell /sbin/nologin telephonygw
sudo rm -rf /opt/telephony-openai-gw
sudo git clone https://github.com/keimei-yoritaka/telephony-openai-gw /opt/telephony-openai-gw
sudo chown -R telephonygw:telephonygw /opt/telephony-openai-gw
```

#### archiveを転送する場合

開発端末またはCI環境でarchiveを作成し、RHELサーバへ転送する。RHELサーバ側にはgitは不要。archiveにはsecretやlogを含めない。

```sh
git archive --format=tar.gz --output=telephony-openai-gw.tar.gz main
scp telephony-openai-gw.tar.gz user@rhel-host:/tmp/
```

RHELサーバ側で展開する。

```sh
id telephonygw >/dev/null 2>&1 || sudo useradd --system --home-dir /opt/telephony-openai-gw --shell /sbin/nologin telephonygw
sudo rm -rf /opt/telephony-openai-gw
sudo mkdir -p /opt/telephony-openai-gw
sudo tar -xzf /tmp/telephony-openai-gw.tar.gz -C /opt/telephony-openai-gw
sudo chown -R telephonygw:telephonygw /opt/telephony-openai-gw
```

以降のcommandは、明示がない限りRepository rootで実行する。

```sh
cd /opt/telephony-openai-gw
```

### OS package導入

EC2へSSH接続したOS userで以下を実行する。package導入はscript内部で`sudo dnf install ...`を実行する。

```sh
scripts/bootstrap-rhel-deps.sh
```

導入対象:

- `git`
- `gcc`
- `gcc-c++`
- `make`
- `autoconf`
- `automake`
- `libtool`
- `swig`
- `java-21-openjdk-devel`

Red HatのOpenJDK 21手順では、RHEL上のJDK導入に`java-21-openjdk-devel`を利用できる。

### PJSIP/PJSUA2 Java binding build

PJSIP/PJSUA2 Java binding buildは、Repository所有userである`telephonygw`として実行する。`/opt/telephony-openai-gw`は`telephonygw`所有のため、EC2へSSH接続したOS userのまま実行すると`.deps/pjproject`や`config_site.h`へ書き込めず、permission errorになる。

```sh
sudo -u telephonygw scripts/build-pjsip-rhel.sh
```

クリーン再build:

```sh
sudo -u telephonygw env PJSIP_CLEAN=1 scripts/build-pjsip-rhel.sh
```

version指定:

```sh
sudo -u telephonygw env PJSIP_VERSION=2.17 scripts/build-pjsip-rhel.sh
```

permission errorが出た場合は、Repository配下の所有者を確認する。

```sh
ls -ld /opt/telephony-openai-gw /opt/telephony-openai-gw/.deps /opt/telephony-openai-gw/.deps/pjproject 2>/dev/null || true
sudo chown -R telephonygw:telephonygw /opt/telephony-openai-gw
```

SWIGで`Unrecognized option -doxygen`が出た場合は、RHELに導入されたSWIGがPJSIPのSWIG Makefileに含まれる`-doxygen` optionに対応していないことが原因。Java binding生成にドキュメント変換は必須ではないため、`build-pjsip-rhel.sh`はSWIGが`-doxygen`非対応の場合にPJSIP配下のSWIG Makefileの`GEN_DOC`を自動で無効化する。最新script取得後に再実行する。

`android/app/src/main/java/org/pjsip/pjsua2/app/MyApp.java`で`CodecInfoVector2`に対するfor-each compile errorが出る場合、または`sample.java`で`MyApp`、`MyCall`、`MyAccount`などが見つからないcompile errorが出る場合は、PJSIP同梱sampleのcompileが原因。Gateway実行にPJSIP sampleは不要であり、必要なのは`org.pjsip.pjsua2`のJava binding本体とnative libraryである。`build-pjsip-rhel.sh`はPJSIPのSWIG Java Makefileからsample compile対象を自動で外すため、最新script取得後に再実行する。

### 実行userと配置

アプリは`/opt/telephony-openai-gw`に配置済みで、専用user `telephonygw`が所有している前提とする。未実施の場合は「サーバへ配置する資材」の手順でRepository一式を配置する。

### 設定ファイル

設定ファイルは`/etc/telephony-openai-gw/gateway.yaml`に配置する。

```sh
sudo mkdir -p /etc/telephony-openai-gw
sudo cp /opt/telephony-openai-gw/config/gateway.pjsua2.example.yaml /etc/telephony-openai-gw/gateway.yaml
sudo chown root:telephonygw /etc/telephony-openai-gw/gateway.yaml
sudo chmod 640 /etc/telephony-openai-gw/gateway.yaml
```

`gateway.yaml`では、SIP domain、user、registrar、bot設定、OpenAI model/voiceを実環境値へ変更する。SIP passwordやOpenAI API keyは直接commitしない。

会話モニターUIを有効にする場合は、`gateway.yaml`に以下を設定する。同一サーバ上のブラウザやSSH port forwardingで確認する場合は`127.0.0.1`のままでよい。EC2外部のブラウザから直接確認する場合は`0.0.0.0`へ変更し、Security Groupで接続元IPを限定する。

```yaml
monitor:
  enabled: true
  bindAddress: 127.0.0.1
  port: 8080
  maxEvents: 500
  sessionHistoryDepth: 10
```

secretは`/etc/sysconfig/telephony-openai-gw`に配置する。

```sh
sudo tee /etc/sysconfig/telephony-openai-gw >/dev/null <<'ENV'
OPENAI_API_KEY=replace-with-openai-api-key
SIP_REGISTRATION_PASSWORD=replace-with-sip-password
ENV
sudo chown root:telephonygw /etc/sysconfig/telephony-openai-gw
sudo chmod 640 /etc/sysconfig/telephony-openai-gw
```

### firewall

EC2 Security GroupでSIP/RTPのInboundを許可したうえで、OS側firewalldを利用している場合はSIP待受portとRTP port rangeも許可する。port番号は環境に合わせて調整する。firewalldを利用していないAMI構成では、この手順は不要な場合がある。

```sh
sudo firewall-cmd --permanent --add-port=5060/udp
sudo firewall-cmd --permanent --add-port=40000-41001/udp
sudo firewall-cmd --reload
```

会話モニターUIを外部ブラウザから直接確認する場合のみ、TCP 8080も許可する。認証なしのため、EC2 Security Group側で接続元IPを必ず限定する。

```sh
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload
```

### systemd service

unit例を配置する。

```sh
sudo cp /opt/telephony-openai-gw/deploy/systemd/telephony-openai-gw.service /etc/systemd/system/telephony-openai-gw.service
sudo systemctl daemon-reload
sudo systemctl enable telephony-openai-gw
```

起動:

```sh
sudo systemctl start telephony-openai-gw
```

状態確認:

```sh
sudo systemctl status telephony-openai-gw
sudo journalctl -u telephony-openai-gw -n 200
sudo journalctl -u telephony-openai-gw | grep 'GW_EVENT'
sudo journalctl -u telephony-openai-gw | grep 'CALL_TRANSCRIPT'
```

会話モニターUIを有効化している場合、サーバ上では以下でHTTP応答を確認する。

```sh
curl http://127.0.0.1:8080/api/sessions/latest
curl http://127.0.0.1:8080/
```

手元PCのブラウザからSSH port forwardingで確認する場合:

```sh
ssh -L 8080:127.0.0.1:8080 ec2-user@replace-with-ec2-public-host
```

その後、手元PCのブラウザで`http://127.0.0.1:8080/`を開く。

停止:

```sh
sudo systemctl stop telephony-openai-gw
```

## デモ環境での確認観点

1. 起動直後に`gateway_started`が出る。
2. SIP Registration成功時に`sip_registration_state code=200`が出る。
3. 着信時に`sip_invite_received`、`call_session_created`、`sip_call_answered`が出る。
4. RTP接続時に`rtp_audio_bridge_attached`が出る。
5. 初回挨拶時に`openai_initial_greeting_requested`と`openai_response_done`が出る。
6. ユーザー発話時に`openai_user_speech_started`が出る。
7. 切断時に`rtp_audio_bridge_closed`、`call_session_closed`が出る。
8. `audio_queue_frame_dropped`が頻発しない。
9. 会話内容確認が必要な場合、`CALL_TRANSCRIPT speaker=caller`と`CALL_TRANSCRIPT speaker=assistant`が出る。
10. OpenAI応答遅延を見る場合、`openai_response_latency commitToFirstAudioMs=...`を確認する。初回挨拶などユーザー音声commitがない応答では`-1`になる。
11. 会話モニターUIを有効化している場合、ブラウザ上で発信者発話が右側、AI応答が左側のバブルとして表示される。

## stdout/stderrとログ保管

RHELではsystemd/journaldでstdout/stderrを集約する。PJSIP nativeログとJavaアプリログは同じservice logに出るため、通常確認は`GW_EVENT`を抽出する。

`logging.level: INFO`はデモ運用向けの既定値であり、RTP frame単位、audio queue受理、OpenAI input frame転送、PJSIP SIP message dumpなどの高頻度診断ログは出力しない。詳細調査時のみ`DEBUG`または`TRACE`へ変更する。

会話本文の確認には`CALL_TRANSCRIPT`を抽出する。transcriptには通話内容が含まれるため、デモ環境で保存・共有する場合は、個人情報や顧客情報の扱いに注意する。

長期運用では以下を検討する。

- Javaアプリ要約ログをJSON Linesとして別fileへ出す。
- PJSIP native詳細ログを別fileへ出す。
- `logrotate`またはjournald retention policyで保存期間と容量を制御する。
- secret値がログに出ないことを定期確認する。

## 参照

- Red Hat Documentation: Installing and using Red Hat build of OpenJDK 21 on RHEL  
  https://docs.redhat.com/documentation/red_hat_build_of_openjdk/21/html-single/installing_and_using_red_hat_build_of_openjdk_21_on_rhel/index
- PJSIP Documentation: Building PJSUA2  
  https://docs.pjsip.org/en/2.15.1/pjsua2/building.html
- PJSIP Documentation: Build Instructions with GNU Build Systems  
  https://docs.pjsip.org/en/2.17/get-started/posix/build_instructions.html
