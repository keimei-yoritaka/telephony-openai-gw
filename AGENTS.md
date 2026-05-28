# Agent向け指示

## プロジェクトの言語ルール

- Markdown文書、計画メモ、要件定義、設計文書、READMEなど、人が読むためのプロジェクト文書は日本語で記載する。
- ソースコード内のコメントは英語で記載してもよい。
- 識別子、package名、class名、method名、file名、protocol名、API名、log field名は英語のままでよい。
- 既存文書を更新する場合、ユーザーが明示的に別言語を指定しない限り、日本語を主言語として維持する。

## GitHub Repository管理ルール

- 本プロジェクトはGitHub Repositoryで継続管理する前提とする。
- Repository名は`telephony-openai-gw`とする。
- Repositoryの公開範囲は初期状態ではprivateとする。
- Repositoryに含める文書、設定例、script、source codeは、後から履歴を追跡できる粒度で変更する。
- 機密情報、API key、password、SIP credential、private certificate、実環境の接続先情報はcommitしない。
- 機密値が必要な設定は、`.env.example` や `config/*.example.yaml` にplaceholderとして記載する。
- generated native binaries、build artifacts、log、temporary filesは原則としてcommitしない。
- PJSIPのnative libraryやSWIG生成物は、必要性が明確になるまでRepositoryには含めず、build scriptと手順で再現する。
- GitHub Repository URL、branch運用、commit/PR運用は、ユーザー確認後に確定する。
- GitHub作成・pushには`gh`コマンドを利用する。`gh auth status`でtoken invalidとなる場合は、ユーザーによる再認証後にRepository作成とpushを実施する。
