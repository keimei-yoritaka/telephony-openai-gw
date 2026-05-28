# Telephony OpenAI Gateway

SIP/RTPによる電話音声とOpenAIの音声Botを接続する軽量Gatewayを構築するプロジェクトです。

初期方針では、JavaアプリケーションからPJSUA2 Java bindingを利用し、SIP Registration、UDP/IPv4のSIP/RTP、PCMU音声、OpenAI Realtime API連携を実装します。

## 現在の状態

- 要件・実装計画を整理済みです。
- GitHub Repositoryは`telephony-openai-gw`としてprivateで管理しています。
- Javaアプリケーション雛形を作成済みです。
- SIP backendは`placeholder`と`pjsua2`を設定で切り替えられます。
- PJSUA2 backendでは、macOS上でEndpoint起動、UDP/IPv4 SIP transport作成、AccountConfigによるRegistration開始、停止まで確認済みです。
- 現時点のmedia、OpenAI連携はplaceholder実装です。

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

現時点ではPJSUA2 Java bindingをまだ組み込んでいないため、SIP RegistrationやOpenAI Realtime接続は実際には行わず、placeholderとしてログ出力します。

PJSUA2 Java bindingのbuild確認:

```sh
scripts/bootstrap-macos-deps.sh
scripts/build-pjsip-macos.sh
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

## 主要ドキュメント

- [要件・実装計画](docs/requirements-and-implementation-plan.md)
- [PJSUA2 Java Binding 調査メモ](docs/pjsua2-java-notes.md)
- [Agent向け指示](AGENTS.md)
