# Experimental開発への引き継ぎ

作成日: 2026-10-13

## 開始地点

- Repository: https://github.com/keimei-yoritaka/telephony-openai-gw
- Stableリリース: `v0.1.0`
- Stableコミット: `22e599213d878f8dd9bb5981aa240606511cadf6`
- Stable Release: https://github.com/keimei-yoritaka/telephony-openai-gw/releases/tag/v0.1.0
- Experimental作業ブランチ: `codex/experimental`
- `codex/experimental`は`v0.1.0`を起点として作成した。新機能の内容はまだ指定されておらず、実装は開始していない。
- Stable統合PR: https://github.com/keimei-yoritaka/telephony-openai-gw/pull/1
- GitHubの公開範囲はリリース時の確認ではpublicだった。AGENTS.mdのprivate記載は初期状態の方針であり、現状の公開範囲と区別する。

## 最初に読む文書

1. `AGENTS.md`: 日本語文書、機密情報の除外、設計書更新ルール。
2. `docs/system-design.md`: 現行実装のシステム設計。
3. `docs/diagrams/telephony-openai-gw-design.drawio`: 構成、クラス、通話、音声queue、OpenAIイベントの図。
4. `README.md`: 設定、起動、ログ、モニターの概要。
5. `docs/releases/v0.1.0.md`: Stableの機能と検証範囲。
6. `docs/deployment-guide.md`と`docs/rhel-update-patch-guide.md`: macOS/RHELの導入・更新手順。

## 現行構成

- Java 21アプリケーション。C/C++でアプリを実装せず、PJSUA2 Java bindingを使用する。
- 開発環境はmacOS、デモ運用環境はRHEL 8.10 on AWS EC2。サーバの実行ユーザーは`telephonygw`、管理作業は`sudo`。
- SIP/RTPはUDP/IPv4。PCMUとG.722をサポートし、OpenAI側は24kHz PCMで接続する。
- `gateway.sessionIds`で固定スロットを定義する。各スロットはSIP transport、Registration、OpenAI設定を独立して持ち、最大1通話を扱う。SIP通話とOpenAI sessionは1:1。
- 設定は`session.<slotId>`セクションの`openai.voice`等、ドット付きキーで記載する。一般的な多段ネストYAMLとは異なるため、ローダーと設定例を参照する。
- RTPは設定範囲の偶数ポートを使用する。NATの公開IP検出はREGISTER応答のVia received/rportを利用する。STUN/ICEを前提としない。
- 音声queueは通話ごとに分離。capacityはシステム共通で、inbound既定500、outbound既定10000。
- `openai.cancelResponseOnUserSpeech`でbarge-in、`openai.dropInputAudioWhileAssistantSpeaking`でAIのRTP送話中の入力破棄を切り替える。入力破棄有効時は送話開始時にinbound queueをクリアする。
- VADはスロット別。既定は`semantic_vad`と`eagerness: low`。`server_vad`のthreshold、prefix padding、silence durationは設定可能。
- モニターはJava内蔵HTTP serverとSSEで配信する。発信者バブルは右、AIは左。セッション名・履歴選択に対応し、履歴の既定深さは10。スマホ幅では上部横スクロールのセッション一覧を表示する。

## 作業上の合意

- 文書とユーザー向け説明は日本語。コード内コメントは英語でもよい。
- 新機能の要件が提示されたら既存実装を確認し、大きい変更は日本語の実装計画をファイルに保存してユーザー承認後に実装する。
- Experimentalの変更は`codex/experimental`で行う。`main`へのマージやStableタグの変更、新たなリリースは別途ユーザーの指示を受ける。
- 仕様変更時は`docs/system-design.md`とdraw.io図を同じ変更単位で更新する。
- モニター画面のローカルプレビューはユーザーから依頼された場合に実施する。
- コミット・GitHub同期を継続して行う。新規スレッド開始時にはbranch、status、remoteの状態を再確認する。
- `config/gateway.local.yaml`はGit管理外。デモ用途でSIP passwordの平文指定を許可しているが、API key・credential・実接続先をコミットやツール出力へ含めない。
- 未追跡の`bin/`が存在する。既存のユーザー資材として扱い、削除や一括stageをしない。
- native library、SWIG生成物、build成果物、`apl.log`等のログはコミットしない。

## 起動・検証

```sh
scripts/check-config.sh config/gateway.example.yaml
scripts/check-config.sh config/gateway.pjsua2.example.yaml
scripts/run-pjsua2-local.sh config/gateway.local.yaml
```

`run-pjsua2-local.sh`はJavaソースをコンパイルして通常起動する。`run-pjsua2-startup-check.sh`は起動確認後に終了するため、実通話の待ち受けには使用しない。`compile-java.sh`は存在しない。

PJSIP/PJSUA2 native部分はOSごとのbuild scriptで構築する。macOSのnative libraryをRHELへ転用しない。OpenH264警告は音声のみの構成では無害と文書化済み。

実通話の検証はユーザーが行い、`apl.log`を指定されたときに確認する。ログは検証日時・対象通話を確かめて読む。`GW_EVENT`で主要イベント、`CALL_TRANSCRIPT`で会話内容を追える。

## 検証範囲と残る確認事項

- 開発中にユーザーが通話、AI音声、複数スロット、同時通話、モニターを確認した。
- Stableリリース準備では設定例2種類の読み込み、PJSUA2込みJavaコンパイル、draw.io XML、差分チェックが成功した。
- Stableリリース時の新たな実通話試験・RHEL再試験は実施していない。
- 最新のスマホCSSは静的差分確認のみで、ローカルプレビューや端末での再確認は未実施。検証済みと断定しない。
- OpenAI応答待ち時間が長いケースは既知の課題。原因がすべて外部サービスにあると断定せず、必要に応じてqueueや送信状態を調査する。
- 実行設定やOpenAI API仕様は変更され得るため、新機能に関係する部分は現時点のコードと公式資料で再確認する。

## 新規スレッドの初回プロンプト

```text
telephony-openai-gwのexperimental開発をこのスレッドで進めます。
作業ディレクトリは /Users/keimei/workspace/Study/022.TelephonyOpenAI_GW です。

AGENTS.mdとdocs/experimental-handoff.mdを読み、続けてdocs/system-design.md、README.md、必要なソースを確認してください。
Stableはv0.1.0で、作業ブランチはcodex/experimentalです。まずgit statusとbranchを確認してください。
文書・説明は日本語でお願いします。仕様変更時には設計書とdraw.io図も更新してください。
mainへのマージ・Stableリリース変更はまだ行わないでください。

今回試したい新機能は以下です。
[ここに機能の目的、希望する動作、制約を記載]

まず要件と疑問点を整理してください。大きな変更の場合は実装計画をMarkdownに保存して提示し、私の承認後に実装へ進んでください。
```
