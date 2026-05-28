# Telephony OpenAI Gateway

SIP/RTPによる電話音声とOpenAIの音声Botを接続する軽量Gatewayを構築するプロジェクトです。

初期方針では、JavaアプリケーションからPJSUA2 Java bindingを利用し、SIP Registration、UDP/IPv4のSIP/RTP、PCMU音声、OpenAI Realtime API連携を実装します。

## 現在の状態

- 要件・実装計画を整理中です。
- GitHub Repositoryは`telephony-openai-gw`としてprivateで管理する予定です。
- 実装前にPJSUA2 Java bindingでmedia frame accessが可能か技術spikeを行います。
- Javaアプリケーション雛形を作成済みです。
- 現時点のSIP、media、OpenAI連携はplaceholder実装です。

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

## 主要ドキュメント

- [要件・実装計画](docs/requirements-and-implementation-plan.md)
- [PJSUA2 Java Binding 調査メモ](docs/pjsua2-java-notes.md)
- [Agent向け指示](AGENTS.md)
