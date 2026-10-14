# Realtime translation開発への引き継ぎ

作成日: 2026-10-15（日本時間）

## この引き継ぎの位置付け

ユーザーは本スレッドの目的であるGPT-Live対応を完了とし、次のRealtime translation追加を別スレッドで進めると指定した。本書は次の開発開始に必要な情報をまとめる。translationの仕様・実装は未着手であり、GPT-Live対応の完了を、実API・実通話・RHEL検証の完了とは区別する。

次のスレッドでは本書を優先して読み、`docs/experimental-handoff.md`はStableからExperimental開始時の経緯・共通合意として参照する。

## Gitと開始地点

- Repository: https://github.com/keimei-yoritaka/telephony-openai-gw
- 作業ディレクトリ: `/Users/keimei/workspace/Study/022.TelephonyOpenAI_GW`
- 作業ブランチ: `codex/experimental`
- GPT-Live実装: `c5ce0e1`（GPT-Live単体の音声会話backendを追加）
- voice文書追加: `59c2461`（RealtimeとGPT-Liveのvoice設定一覧を文書化）
- 引き継ぎ作成前のHEAD: `59c2461`。この後に引き継ぎ文書のコミットが追加される。
- 上記2コミットは`origin/codex/experimental`へpush済み。作成時点のローカル追跡参照とのずれはないが、今回新たなfetchは実施していない。
- 開始時点でGit管理対象の未コミット変更はない。未追跡の`bin/`は既存ユーザー資材であり、削除・一括stageしない。
- Stableは`v0.1.0`、起点コミットは`22e599213d878f8dd9bb5981aa240606511cadf6`。このスレッドでは`main`へのマージ、Stableタグ変更、新規リリースを行っていない。
- 新スレッドではbranch、status、HEAD、remoteを再確認する。translation用の別ブランチ名は合意していないため、勝手に作成せず、現行の`codex/experimental`を前提に開始する。

## 最初に読むファイル

1. `AGENTS.md`: 日本語、機密情報の除外、設計書更新ルール。
2. 本書と`docs/experimental-handoff.md`: 現行状態と作業上の合意。
3. `docs/system-design.md`、`docs/diagrams/telephony-openai-gw-design.drawio`: 現行仕様。図はRealtime / Liveの両シーケンスを含む。
4. `docs/gpt-live-implementation-plan.md`: 実装内容と検証範囲。delegation / 業務toolはユーザー指定で除外。
5. `docs/voice-settings.md`: RealtimeとLiveのvoiceキー・対応一覧・変更反映方法。
6. `README.md`、`config/gateway.live.example.yaml`、`docs/deployment-guide.md`、`docs/rhel-update-patch-guide.md`: 起動と配置。差分反映ガイド後半は古いmulti-session更新の記録を含む。

## 現行の構成と再利用候補

Java 21 / PJSUA2 Java bindingでUDP/IPv4のSIP/RTP、PCMU / G.722を扱う。OpenAI向けは24kHz PCM。固定スロットごとに最大1通話で、音声queueは通話別に分離する。開発はmacOS、デモ運用はRHEL 8.10 / EC2、実行ユーザーは`telephonygw`。

| ファイル・クラス | 現行の責務 |
| --- | --- |
| `config/GatewayConfig` / `GatewayConfigLoader` | `session.<slotId>`とドット付きキーの読み込み、backend別検証 |
| `openai/VoiceSessionManager` | スロット別factory、通話worker、開始・終了・再試行、開始中終了の登録防止 |
| `openai/VoiceSession` | open、入力PCM、挨拶、idle判定、closeの共通interface |
| `openai/RealtimeSession` | 現行RealtimeのWebSocket・VAD・音声・transcript・barge-in |
| `openai/LiveSession` | GPT-Liveの開始確認・継続音声・transcript・usage・終了確認 |
| `openai/LiveAudioDecoder` / `Pcm16Resampler` | delta境界のPCM保持、20ms frame化、sample rate変換 |
| `openai/LiveTranscriptAssembler` | caller / assistantの独立した表示segment集約 |
| `media/AudioBridge` / `AudioQueue` | 通話別queue、backend別入力方針、終了後のqueue再生成防止 |
| `src/pjsua2/.../Pjsua2AudioBridgePort` | native callbackでのPCM入出力、開始bufferとRTP再生計測 |
| `monitor` package / `src/main/resources/monitor/app.js` | HTTP / SSE、確定transcriptとLive snapshotの表示・履歴統合 |
| `src/check/java/.../VoiceIntegrationCheck.java` | 実APIを呼ばないsession・音声・競合検証 |
| `src/test/js/monitor-check.cjs` | DOM stubによるsnapshot・履歴競合検証 |

上表のJavaクラスは、特記しない限り`src/main/java/com/example/telephonygw/`配下にある。

## Realtime / Liveの違いと維持すべき仕様

- `openai.backend`は`realtime`（省略時）または`live`。Realtimeは`openai.realtimeModel` / `openai.voice`、Liveは`openai.liveModel` / `openai.liveVoice`を使用する。
- Live既定は`gpt-live-1`、`marin`、`openai.liveStartBufferFrames: 2`。キー末尾は`Frames`。開始bufferは発話速度の設定ではない。
- `bot.systemInstructions`と`bot.initialGreeting`は両backend共通。Liveの速度・口調・発音などはinstructionsで調整する運用を説明済み。専用のspeed / accent / pitchキーはGatewayへ追加していない。
- LiveはGPT-Live単体の音声会話。Responses backend、委譲処理の実行、業務toolを追加しないというユーザー合意がある。translation追加にこれらが必要と推測して拡張しない。
- LiveへRealtimeのVAD / response.create / response.cancelを流用しない。LiveはAI送話中も入力を継続し、Realtime用の入力破棄・barge-in閾値を適用しない。
- Liveの音声完了や確定ターンを示すeventを捏造しない。字幕は話者別の表示segmentとして`final: false`のsnapshotを更新する。API item IDと表示segment IDを区別する。
- Liveは会話の間だけで15秒idle closeしない。再接続は新sessionとして最近のテキスト（最大20件・2000文字）を渡す。完全な音声文脈復元は保証しない。
- 通話終了時はqueueを停止し、別workerで最大15秒のLive終了確認を行う。最終usageは確認済みか未確認かを区別する。

## 検証済みの範囲と未検証事項

このスレッドで確認済み:

- 設定例3種類（`gateway.example.yaml`、`gateway.pjsua2.example.yaml`、`gateway.live.example.yaml`）の読み込み。
- Javaのオフライン検証41項目。PCM分割、8kHz/16kHz変換、開始前入力、開始中終了、送信backlog、終了timeout、旧callback等。
- モニターのsnapshot更新、同時話者、重複event、履歴取得・選択変更の競合。
- Gradleの`clean check`、PJSUA2込みJavaコンパイル、native binding読み込み確認、JS/shell構文、draw.io XML、差分チェック。
- voice文書のRealtime 10種類 / Live 31種類の列挙、Realtime実装との一致、ローカル参照リンク。

未検証またはユーザーから結果報告を受けていない事項:

- GPT-Liveの実API権限・音声疎通、日本語の自然さ、実通話・同時通話。
- このExperimental版のRHEL実機動作。
- ブラウザの外観・端末上の表示。UIはDOM stubによる検証のみ。
- instructionsによる速度・アクセント・抑揚の実際の効果。

ユーザーにRHEL適用手順を提示済みだが、実施・成功したと断定しない。GPT-Live対応の開発目的はユーザー判断で完了しているため、次の作業はtranslationを中心に進める。

## 次スレッドで整理するRealtime translation要件

追加したい機能名以外の要件はまだ確定していない。次スレッドでは公式のRealtime translation API / 対応モデル・接続方式を調査し、既存RealtimeやGPT-Liveに翻訳指示を与える方式と区別して提案する。現時点でtranslationのmodel名、endpoint、event、voice、設定キーを確定しない。

最初に確認する論点:

1. 翻訳方向と対象言語。片方向か、双方向の電話通訳か。
2. 利用形態。発信者の音声を訳して同じ通話へ返すのか、別の通話相手へ中継するのか。既存GatewayはPBXや通話間ブリッジを実装していない。
3. 出力。翻訳音声、翻訳字幕、原文字幕の必要性と、モニターでの表示方法。
4. 会話動作。純粋な翻訳か、翻訳に加えてAI応対を行うか。挨拶、言語自動検出、相づち、割り込みの扱い。
5. 既存スロットとの共存、遅延の評価、PCMU / G.722、複数通話、切断・再接続の扱い。

未回答の要件は、調査・現行コード確認を進めつつユーザーに確認する。大きい変更は日本語の計画を保存・提示し、ユーザー承認後に実装する。GPT-Live計画の承認をtranslation実装計画の承認として扱わない。

## 実行・Git・機密情報の注意

```sh
scripts/check-config.sh config/gateway.example.yaml
scripts/check-config.sh config/gateway.pjsua2.example.yaml
scripts/check-config.sh config/gateway.live.example.yaml
scripts/check-voice.sh
node src/test/js/monitor-check.cjs
gradle --no-daemon check
```

Gradleは`settings.gradle`でMaven Centralを設定済み。プロジェクト側へのrepository重複追加はエラーになる。Gson 2.13.2は直接javacするscriptでも必要で、`scripts/java-deps-classpath.sh`がchecksum確認して`.deps/java`へ取得する。jarはcommitしない。

Gradleのcleanと直接コンパイルするscriptは同じ`build/`領域を使うため、同時に実行しない。独自の`GRADLE_USER_HOME`を指定する場合も、cleanで消える`build/`配下に置かない。

実通話待ち受けは`scripts/run-pjsua2-local.sh config/gateway.local.yaml`。startup-checkは起動後に終了する。systemd例の実行設定は`/etc/telephony-openai-gw/gateway.yaml`であり、実環境のExecStartを確認して設定先を判断する。native成果物はOS別にbuildし、macOSの成果物をRHELへ転用しない。

日本語文書、機密値の除外、設計書・draw.ioの同時更新、コミット・GitHub同期の合意を維持する。`config/gateway.local.yaml`、API key、SIP credential、実接続先、ログをコミットやツール出力へ含めない。モニターのローカル外観プレビューはユーザー依頼時に実施する。

## 次スレッドの冒頭プロンプト

```text
telephony-openai-gwのexperimental開発で、Realtime translationの追加を進めたいです。
作業ディレクトリは /Users/keimei/workspace/Study/022.TelephonyOpenAI_GW です。

AGENTS.mdとdocs/realtime-translation-handoff.mdを読み、docs/system-design.md、README.md、必要なソースを確認してください。まずgit status、branch、HEAD、remoteを確認してください。
作業ブランチはcodex/experimentalです。説明と文書は日本語でお願いします。

前スレッドのGPT-Live対応は完了しています。Realtime / Liveの既存機能を維持してください。delegationと業務tool連携は不要です。

まずOpenAIのRealtime translationについて最新の公式仕様を調査し、既存構成への追加方法と影響を整理してください。専用のtranslation APIを使う方式と、既存Realtime / GPT-Liveへ翻訳を指示する方式を区別してください。
対象言語、翻訳方向、利用形態、翻訳音声・原文字幕・翻訳字幕の要件は、提案しながら私に確認してください。未指定の要件を確定扱いしないでください。

日本語の実装計画をMarkdownに保存して提示し、私の承認後に実装へ進んでください。仕様変更時はdocs/system-design.mdとdraw.io図を同じ変更単位で更新してください。
mainへのマージ、Stableタグ変更、新規リリースは行わないでください。未追跡のbin/や機密設定は変更・コミットしないでください。
```
