# Telephony OpenAI Gateway

SIP/RTPによる電話音声とOpenAIの音声Botを接続する軽量Gatewayを構築するプロジェクトです。

初期方針では、JavaアプリケーションからPJSUA2 Java bindingを利用し、SIP Registration、UDP/IPv4のSIP/RTP、PCMU音声、OpenAI Realtime API連携を実装します。

## 現在の状態

- 要件・実装計画を整理中です。
- GitHub Repositoryは`telephony-openai-gw`としてprivateで管理する予定です。
- 実装前にPJSUA2 Java bindingでmedia frame accessが可能か技術spikeを行います。

## 主要ドキュメント

- [要件・実装計画](docs/requirements-and-implementation-plan.md)
- [Agent向け指示](AGENTS.md)

