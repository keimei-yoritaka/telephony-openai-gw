# 別の翻訳API追加への引き継ぎ

作成日: 2026-11-11（日本時間）

## 目的と今回の合意

ユーザーは既存OpenAI Realtime translationを現状のまま維持し、別の翻訳APIの追加を新規スレッドで進めると指定した。本書はその開始に必要な状態・合意・確認事項をまとめる。追加APIのサービス名、モデル、URL、認証方式、接続方式、設定キーはまだ指定されていない。OpenAI製や特定ベンダーと推測して実装しない。

過去の`realtime-translation-handoff.md`はGPT-Live完了時点の記録で、「translation未着手」とある。現在は実装済みのため、新規作業では本書を優先する。

## Gitと作業環境

- 作業ディレクトリ: `/Users/keimei/workspace/Study/022.TelephonyOpenAI_GW`
- Repository / origin: `https://github.com/keimei-yoritaka/telephony-openai-gw.git`
- 作業ブランチ: `codex/experimental`。別ブランチ名は未合意。
- 文書作成前のHEAD: `215c75d8b21983caf3100a17a9694b5fb06526c9`（専用Realtime translation backendとRHEL適用手順を追加）。この後に引き継ぎ文書のコミットが追加される場合がある。
- `215c75d`は前の作業でGitHubへpushし、GitHub APIでも反映を確認済み。
- 今回の文書作成開始時点では管理対象ファイルに未コミット変更なし。未追跡の`bin/`のみ存在する。削除・変更・一括stageしない。
- 文書作成開始時のローカル追跡参照とのahead / behindは`0 / 0`。文書同期前にも`origin/codex/experimental`をfetchした。新スレッドではGit状態を再確認する。
- Stableは`v0.1.0`。この作業でmainへのマージ、タグ変更、新規リリースは行っていない。
- 開発はmacOS、検証先はRHEL 8.10 / EC2。Java 21、実行ユーザーは`telephonygw`。

## 最初に読むファイル

1. `AGENTS.md`と本書: 日本語、機密情報除外、作業上の合意。
2. `README.md`、`docs/system-design.md`、`docs/diagrams/telephony-openai-gw-design.drawio`: 現行仕様。draw.ioはTranslation Sequenceを含む7ページ。
3. `docs/realtime-translation-implementation-plan.md`: OpenAI専用translationの計画・実装結果・過去のオフライン検証記録。
4. `config/gateway.translation.example.yaml`: Realtime / Live / translationの3方式混在例。
5. `docs/rhel-update-patch-guide.md`: 冒頭の「Realtime translation ExperimentalをGitで適用する場合」。後半のmulti-session手順は過去の記録。
6. `docs/gpt-live-implementation-plan.md`、`docs/voice-settings.md`、`docs/deployment-guide.md`: 既存Liveと運用の詳細。

## 現在のbackendと維持する動作

`session.<slotId>`内の`openai.backend`で以下を選択する。設定loaderはドット付きキーを扱う独自の簡易YAMLであり、一般的な多段ネストを追加してもそのまま読めない。

| backend | 用途・設定 |
| --- | --- |
| `realtime`（未指定時） | 既存の音声AI応対。`openai.realtimeModel` / `openai.voice`。Realtime用VAD・barge-in・送話中入力破棄を適用 |
| `live` | GPT-Live単体の全二重音声AI応対。`openai.liveModel` / `openai.liveVoice`。送話中も入力継続。delegation / 業務toolの実行なし |
| `translation` | OpenAI専用`gpt-realtime-translate`。継続翻訳。送話中も入力継続。自発的な挨拶・AI応対なし |

各スロットは最大1通話。通話ごとの音声queueは分離される。SIP/RTPはUDP/IPv4、PCMU / G.722。GatewayはPBXや別通話間ブリッジを実装していない。既存translationの用途は「入力を翻訳し、同じ電話へ音声を返す検証構成」であり、二者間の双方向電話通訳ではない。

新APIの追加は既存3方式を維持した独立backendを基本案とする。ただし選択値・設定prefix・package分割は新APIの仕様を読んで計画で提案する。既存の`openai.backend`配下に他社APIを収めるか、providerを導入するかは未確定。大規模な共通化を先に実施しない。

## OpenAI translationの実装概要

```yaml
session.session-3:
  openai.backend: translation
  openai.apiKey: ${OPENAI_API_KEY}
  openai.translationModel: gpt-realtime-translate
  openai.translationTargetLanguage: en
  openai.translationSourceTranscriptEnabled: true
  openai.translationTranscriptionModel: gpt-realtime-whisper
  openai.transcriptLoggingEnabled: false
```

上記は抜粋。SIP等を含む完全な例は`config/gateway.translation.example.yaml`。

- 入力言語はAPIの自動検出。目的言語は必須。`en`で英語、`ja`で日本語。`openai.inputTranscriptionLanguage`はtranslationに送らない。
- 専用WebSocketは`/v1/realtime/translations`。`session.created`→`session.update`→`session.updated`の目的言語確認後に開始する。
- 20msの入力PCMを24kHz / mono / PCM16LEへ変換する。内部queueは100 frame、開始60ms後から20ms clockで消費し、不足分を無音補完する。10 frameを200msで送信する。
- 出力の可変長PCMを`StreamingAudioDecoder`で保持・20ms frame化し、通話sample rateへ戻す。開始bufferは2 frame、出力gap 100msで端数をflushする。これらは設定キーではなくコード内の固定値。
- 原文と翻訳は独立した全文snapshot。4秒または512文字を目安にアプリ側segmentを作り、API確定turnとは扱わない。`sourceEventId`と表示segment IDを区別し、`elapsed_ms`を重複判定に使わない。
- モニターeventの`transcriptKind`は`source` / `translation` / `status`、`targetLanguage`で目的言語を表す。既存Realtime / Live eventはこれらを空にできる。
- 切断時は入力停止・受理済み端数flush・`session.close`。最大15秒、残字幕と`session.closed`を受信する。SIP切断後に音声再生やqueue再生成をしない。
- 失敗時は新sessionとして15秒cooldownで再試行。過去音声・会話を再送しない。Liveの再接続時テキスト履歴注入とは異なる。

### 直前のAPI調査とユーザー判断

以下は前の会話で調べた時点の記録であり、2026-11-11現在のAPI仕様を再調査した結果ではない。

- 専用translationには固定voice選択、custom prompt、用語辞書・発音ガイドの公開設定がないと説明した。
- 原文transcriptionの有効化・モデル、目的言語、noise reductionが公開項目。noise reductionは`near_field` / `far_field` / `null`だが、Gatewayの設定キーは未実装でAPIへも未指定。
- ユーザーはvoice選択の相談後、「translationは今のままでよい」と回答した。GPT-Live翻訳への置き換え、TTS追加、noise reduction設定追加は承認・実装していない。
- 別APIを追加したい理由・voice要件は明言されていない。固定voiceが必須と推測しない。
- 公式参照先: [Translation guide](https://developers.openai.com/api/docs/guides/realtime-translation)、[Client events](https://developers.openai.com/api/reference/resources/realtime/translation-client-events)、[Cookbook](https://developers.openai.com/cookbook/examples/voice_solutions/realtime_translation_guide)。次の実装に必要な仕様は最新の各サービス公式資料で確認する。

## 再利用候補と注意点

Javaファイルは特記しない限り`src/main/java/com/example/telephonygw/`配下。

| 対象 | 責務・次の作業での注意 |
| --- | --- |
| `config/GatewayConfig` / `GatewayConfigLoader` | backend別の設定・検証。既存設定の互換性を保つ |
| `openai/VoiceSession` / `VoiceSessionManager` | factory、通話開始終了、forwarder、健全性・再試行。API固有の終了eventを流用しない |
| `openai/RealtimeSession` / `LiveSession` / `TranslationSession` | 既存3方式のプロトコル。単なるmodel差し替えで他APIへ接続できるとは考えない |
| `openai/StreamingAudioDecoder` / `Pcm16Resampler` | PCM delta境界保持・20ms frame化・sample rate変換。旧名`LiveAudioDecoder`は廃止済み |
| `openai/TranslationTranscriptAssembler` | 原文・翻訳の独立snapshot、event ID重複対策、再接続世代 |
| `media/AudioBridge` / `AudioQueue` | backend名と継続音声方針、通話別queue。新backendをLiveと偽装登録しない |
| `src/pjsua2/.../Pjsua2AudioBridgePort` | native PCM callbackとRTP再生。新APIにnative変更が必要かを判断する |
| `monitor` package / `src/main/resources/monitor/app.js` | SSE・履歴・字幕種別表示。履歴取得とSSE競合、重複snapshotに注意 |
| `src/check/java/.../VoiceIntegrationCheck.java` / `TranslationIntegrationCheck.java` | 実API不要の音声・接続・終了・異常系検証 |
| `src/test/js/monitor-check.cjs` | DOM stubによるモニター検証 |

既知の小さな未修正事項: `TranslationSession`の接続状態文言には「日本語から」が固定で残る。実際の翻訳先は設定値に従い、この表示は入力言語を制限しない。新API追加と一緒に勝手に改修せず、必要なら計画に含める。

## 検証結果とユーザー報告

実装時にエージェントが確認した過去の結果:

- 既存Java検証41項目＋translation専用54項目、Gradle checkが成功。
- 設定例4種類、モニターJS、PJSUA2込みコンパイルとnative binding読込、draw.io XML・文書リンク・差分チェックが成功。
- 今回の引き継ぎ作成で実APIや実通話の再検証は行っていない。

ユーザー報告:

- RHELへの適用案内・GitHubへのpush後、ユーザーは「日→英の翻訳は正常に動作しました」と報告した。これは実環境での日→英の成功報告として引き継ぐ。
- 試験時のcodec、同時通話数、長時間試験、字幕の詳細、RHEL上の正確なcommit / 設定は提示されていないため、全項目の合格とは扱わない。
- 英→日は`translationTargetLanguage: ja`で利用できる実装だと説明済みだが、成功報告はまだない。
- GPT-Liveの実通話・品質、ブラウザ外観、負荷・長時間・異常時の実機検証は、報告がない範囲を検証済みとしない。

## 次スレッドで確認する要件と進め方

1. 追加APIのサービス名・公式ドキュメントURL・製品名。未定なら候補選定から行うか確認する。
2. 翻訳対象言語と方向、1通話への返送か別通話中継か。既存translationと同じ用途を候補として提案し、確定扱いしない。
3. 翻訳音声・原文字幕・翻訳字幕、固定voiceや話速・用語辞書の必要性。
4. streamingか発話単位か。音声翻訳APIなのか、STT→テキスト翻訳→TTSを組む必要があるのかを区別する。
5. 認証、RHELからの到達性、対応音声形式、遅延、同時接続、終了・再接続、料金・利用条件。
6. 既存3方式と共存する設定形式・差分を提案し、日本語の実装計画をMarkdownへ保存・提示して承認後に実装する。過去のOpenAI translation承認は新APIの承認として扱わない。

delegation / 業務toolは不要。仕様変更時は実装と同じ変更単位で`docs/system-design.md`とdraw.ioを更新する。日本語文書・機密除外・GitHub継続管理を守る。mainへのマージ、Stableタグ変更、新規リリースは別途指示があるまで行わない。

## 検証・適用の入口

```sh
scripts/check-config.sh config/gateway.example.yaml
scripts/check-config.sh config/gateway.pjsua2.example.yaml
scripts/check-config.sh config/gateway.live.example.yaml
scripts/check-config.sh config/gateway.translation.example.yaml
scripts/check-voice.sh
node src/test/js/monitor-check.cjs
gradle --no-daemon check
```

Gson 2.13.2は`java-deps-classpath.sh`でchecksum確認して取得する。Gradleはsettings側にMaven Centralを設定済み。cleanと直接compile scriptは同時実行しない。前回は`.deps/gradle-cache`を`GRADLE_USER_HOME`としてオフラインGradle検証に利用し、制限環境のnative起動エラーには実行許可を得て対応した。

RHELでは既存ソース・実行設定を退避し、`codex/experimental`をfast-forward更新する。提供systemd unitは`/etc/telephony-openai-gw/gateway.yaml`を読むが、実環境の`ExecStart`を確認してから設定先を決める。通常の待ち受けは`run-pjsua2-local.sh`。startup-checkは起動後に終了する。macOSのnative成果物はRHELへ持ち込まない。

`config/gateway.local.yaml`、API key、SIP credential、実接続先、ログをcommit・出力しない。モニター外観プレビューはユーザー依頼時に行う。

## 新規スレッドの初回プロンプト

```text
telephony-openai-gwのExperimental開発で、既存とは別の翻訳APIを追加したいです。
作業ディレクトリは /Users/keimei/workspace/Study/022.TelephonyOpenAI_GW です。

まずgit status、branch、HEAD、remoteを確認し、AGENTS.mdとdocs/additional-translation-api-handoff.mdを読んでください。
続けてREADME.md、docs/system-design.md、draw.io、必要なソースを確認してください。
作業ブランチはcodex/experimental、説明と文書は日本語でお願いします。

既存realtime、GPT-Live（live）、OpenAI専用translationの機能を維持してください。
OpenAI translationの日→英はユーザー側で動作確認済みです。既存translationは現状維持とし、新APIは独立した選択肢として追加する方向で検討してください。
delegationと業務tool連携は不要です。

追加したいAPI：［サービス名・製品名・公式ドキュメントURL。未定なら「候補選定から相談」と記載］
今回の用途・希望：［言語と方向、同じ電話への返送／別通話への中継、必要な音声・字幕・voice設定など。未定は未定と記載］

まず追加APIの最新公式仕様を調査し、既存構成への追加方法・影響・未確定要件を整理してください。
専用の音声翻訳APIと、STT・テキスト翻訳・TTSの組合せ方式を区別し、対応していない機能を推測で補わないでください。
日本語の実装計画をMarkdownに保存して提示し、私の承認後に実装へ進んでください。
仕様変更時はdocs/system-design.mdとdocs/diagrams/telephony-openai-gw-design.drawioも同じ変更単位で更新してください。
mainへのマージ、Stableタグ変更、新規リリースは行わないでください。未追跡bin/や機密設定は変更・コミットしないでください。
```
