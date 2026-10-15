# Realtime translation 調査・実装計画と検証記録

作成日・公式仕様確認日: 2026-10-15（日本時間）

## 位置付けと開始状態

本書は追加方式の比較と実装案であり、現行仕様の変更や実装承認を意味しない。対象言語・方向・利用形態・出力要件は2026-10-15のユーザー回答を反映済み。2026-10-15に、専用backendを既存Realtime / GPT-Liveと同列の設定選択肢として追加する前提で実装承認を受領した。

- 作業ディレクトリ: `/Users/keimei/workspace/Study/022.TelephonyOpenAI_GW`
- branch: `codex/experimental`
- HEAD: `0077e41f0fc1d19da4de869124b5d9baf1f5feac`
- origin（fetch / push）: `https://github.com/keimei-yoritaka/telephony-openai-gw.git`
- 開始時のstatus: 未追跡の`bin/`のみ。管理対象ファイルに未コミット変更なし。
- 今回はfetchを行っておらず、リモート最新状態との一致は未確認。
- `bin/`、機密設定、native成果物は変更・stageしない。mainへのマージ、Stableタグ変更、新規リリースは行わない。

確認対象: `AGENTS.md`、translation / experimental引き継ぎ、`README.md`、`docs/system-design.md`、draw.ioの既存6ページ、設定loader、voice session管理、Realtime / Live、音声bridge / decoder、PJSUA2音声callback、モニターevent / snapshot / UI。

## 最新公式仕様の要点

### 専用translation API

`gpt-realtime-translate`は、人の発話を継続的に翻訳する専用モデル。サーバでSIP音声を扱う本GatewayにはWebSocket接続が適する。接続先は`wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate`。通常Realtimeの会話・response lifecycleとは異なり、`response.create`で翻訳を起動しない。出力言語ごとにsessionを用意し、二者間通訳では話者音声を分離して方向ごとに接続する。[公式ガイド](https://developers.openai.com/api/docs/guides/realtime-translation)

入力は24kHz / mono / PCM16LEのbase64。`session.input_audio_buffer.append`で無音も継続送信する。200ms単位の送信が推奨され、短いchunkはサーバ側で蓄積される。設定更新の公開項目は出力言語、原文transcription、入力noise reduction。原文transcriptionは任意であり、翻訳そのものの入力には使われない。[Client events](https://developers.openai.com/api/reference/resources/realtime/translation-client-events)

| 方向 | event | Gatewayでの意味 |
| --- | --- | --- |
| 受信 | `session.created` | sessionの初期設定を確認 |
| 送信 / 受信 | `session.update` / `session.updated` | 目的言語等を設定し、反映を確認 |
| 送信 | `session.input_audio_buffer.append` | 継続入力 |
| 受信 | `session.output_audio.delta` | 可変長PCM音声を蓄積・再生 |
| 受信 | `session.input_transcript.delta` | 任意の原文字幕 |
| 受信 | `session.output_transcript.delta` | 翻訳字幕 |
| 送信 / 受信 | `session.close` / `session.closed` | 入力停止後、残出力を受信して終了 |
| 受信 | `error` | 設定・通信障害の分類 |

字幕deltaは空白を追加せず連結する。`elapsed_ms`は整列用で、同値の複数deltaがあり得るため重複除去キーにしない。出力音声は固定chunk長を仮定せず、`sample_rate` / `channels` / `format`も検証する。公開server eventには発話別の確定字幕・音声完了eventはない。[Server events](https://developers.openai.com/api/reference/resources/realtime/translation-server-events)

出力対応は英語、日本語、スペイン語、ポルトガル語、フランス語、ロシア語、中国語、ドイツ語、韓国語、ヒンディー語、インドネシア語、ベトナム語、イタリア語の13言語。入力は70超の言語を動的に検出する。専用モデルにはcustom promptや固定voice選択の公開パラメーターがなく、入力話者に応じたvoice adaptationを使う。原文字幕のモデル例は`gpt-realtime-whisper`。原文と目的言語が同じ場合は無出力になることがあり、同言語の復唱を保証しない。[公式Cookbook](https://developers.openai.com/cookbook/examples/voice_solutions/realtime_translation_guide)

モデルページでは音声時間課金、function calling非対応とされる。アカウントの利用権限・実際の制限・実課金は未確認。無音送信も含めた費用評価を実機確認項目とする。[モデル仕様](https://developers.openai.com/api/docs/models/gpt-realtime-translate)

### 既存Realtime / GPT-Liveへ翻訳を指示する方式

| 比較項目 | 専用translation | 既存Realtimeへの指示 | 既存GPT-Liveへの指示 |
| --- | --- | --- | --- |
| 接続 | 専用`/v1/realtime/translations` | 現行`/v1/realtime` | 現行`/v1/live/sessions` |
| 目的 | 継続的な純粋翻訳 | 音声assistantに翻訳を依頼 | 全二重の音声assistantに翻訳を依頼 |
| 制御 | 目的言語を設定 | instructionsと既存VAD / response処理 | instructionsとLive独自処理 |
| voice / 挨拶 | 固定voice・挨拶promptを渡さない | 現行設定を使用可能 | 現行設定を使用可能 |
| 導入量 | 専用backendと共通層の拡張 | 主に設定・promptの試行 | 主に設定・promptの試行 |
| 主な評価点 | 翻訳品質・遅延・音声配線 | 逐次翻訳の待ち、質問への誤応答 | 翻訳指示の遵守、重複・相づち |

Realtimeは音声assistantとして会話を管理するAPIであり、専用translationへのmodel名差し替えではない。[Realtime概要](https://developers.openai.com/api/docs/guides/realtime)

GPT-Liveには公式の翻訳専用prompt例がある。発話中の質問・命令も翻訳素材として扱い、応答や実行をせず、重複翻訳を避ける指示が示されている。したがってLiveの翻訳指示方式も有効な比較候補だが、専用APIの機能・制約と同一とは扱わない。Liveは全二重のため、Realtimeと同じ発話待ち方式だと一括して説明しない。[GPT-Liveの翻訳prompt](https://developers.openai.com/api/docs/guides/live-prompting#translation)

提案: 純粋な継続翻訳は専用backendを第一候補とする。AI応対や固定voiceを優先する場合は指示方式を再検討する。既存backendのモデル変更や翻訳動作への一律切り替えは行わない。delegation・業務toolは追加しない。

## 確定要件と実装提案

次の4項目はユーザー回答により確定した。これらの要件選択は実装計画全体の承認とは区別する。

| 論点 | 確定内容 | 実装範囲 |
| --- | --- | --- |
| 言語・方向 | 日本語→英語の片方向 | translationスロットの目的言語を`en`に設定 |
| 利用形態 | 同じ電話へ翻訳音声を返す検証用構成 | 1通話に1 translation session。別通話への中継は対象外 |
| 出力 | 翻訳音声＋原文字幕＋翻訳字幕 | 電話へ英語音声を返し、モニターに原文と翻訳文を表示 |
| 会話動作 | 純粋な翻訳 | 自発的な挨拶・相づち・AI応対を加えない |

実装提案: 専用translation backendを使い、目的言語はスロット固定、入力言語はAPIの自動検出とする。通話中切替やDTMF選択は今回追加しない。Realtime / Live / translationをスロット別に混在可能とし、実環境のスロット割当は実通話準備時に指定する。

専用APIで字幕だけ必要な場合も、公開schemaで音声生成そのものを無効化できるとは確認できていない。Gatewayで受信音声を再生しない方式とし、音声生成・課金の削減を保証しない。原文字幕は音声認識結果であり、人手の正解文ではない。

同じ電話への返送は音声経路の疎通検証には使えるが、別言語の二者をつなぐ電話通訳の完成を意味しない。スピーカーフォンの回り込みは端末・経路を含めて評価し、翻訳送話中の入力破棄で対処しない。

## 既存構成への影響と実装案

以下は確定した片方向・同じ電話への返送・音声と両字幕を実現する計画。本計画に基づく実装開始は承認済み。

### 設定とbackend選択

- `openai.backend: translation`と`TranslationSession implements VoiceSession`を追加する案。
- 設定キー案: `openai.translationModel`（既定`gpt-realtime-translate`）、`openai.translationTargetLanguage`（必須）、`openai.translationSourceTranscriptEnabled`（既定`true`）、`openai.translationTranscriptionModel`（既定`gpt-realtime-whisper`）。今回のtranslationでは翻訳音声を再生する。字幕専用モードの切替キーは追加しない。
- 設定例の目的言語は`en`とする。入力は自動検出であり、日本語以外をGateway側で拒否する機能は追加しない。
- 固定voice、VAD、maxOutputTokens、bot instructions / greetingをtranslation APIへ送らない。translationではbot項目を不要にできるよう、現在の全backend共通の必須検証を分岐する。
- 設定loaderのドット付きキー方式とbackend未指定時のRealtimeを維持する。既存設定例3種類を維持し、placeholderだけのtranslation設定例を追加する。
- `selectedModel` / `selectedVoice`と利用箇所を点検し、translationがRealtime設定へfall-throughしないようにする。warm-upの現在の`!isLive()`判定もRealtimeの明示判定へ変更する。

### 接続・入力・再接続

- 現行Java HTTP WebSocketとGsonを再利用する案。SDK導入は必須にしない。
- `session.created`→設定送信→`session.updated`確認を開始条件にし、開始前入力を通話別の上限付きbufferで扱う。接続・送信のtimeout、送信順序、backlog超過、errorを明確にする。
- 8kHz / 16kHzの20ms PCMを24kHzへ変換し、原則10 frameをまとめて200msとして送信する。callbackの遅延・入力欠損・無音を区別し、既に到着している無音を二重挿入しない。
- native callbackが無音中にもframeを供給するか確認し、不足時にのみtranslation専用の時計・無音補完を設ける。無音補完と実frameの順序、catch-upによる大量送信、queue overflow時の時間ずれをテストする。
- 現在の「入力queueが空なら待つ」workerにtranslation向けtickが必要か検証する。Realtime / Liveの入力タイミングは維持する。
- translationは無発話だけで15秒idle closeしない。切断時は新sessionとして再接続し、再接続間の欠落を表示する。既存Liveの会話履歴注入を転用しない。音声の再送による二重翻訳を避ける。

### 音声queue・再生・終了

- `AudioBridge.configureSessionBackend(..., boolean live, ...)`とPJSUA2側の二分岐を、backendまたは明示的な音声処理方針へ拡張する。translationをLiveと偽装登録しない。
- translationでは送話中も入力を継続し、Realtimeの入力破棄・barge-in・`response.cancel`を適用しない。
- 可変長出力をPCM sample境界で保持し、20msへ組み直して8kHz / 16kHzに変換する。`StreamingAudioDecoder` / `Pcm16Resampler`の再利用は、Live固有のgap flushをtranslationへ持ち込まない形で検討する。
- 出力の開始buffer・gap時の残frame再生をtranslationの方針として定義する。queueが空でもAPIの翻訳完了とは扱わない。異常なformatや音声サイズは停止・通知する。
- 通話終了時は入力を停止し、端数入力を一度だけflushして`session.close`を送る。残字幕・音声を受信し`session.closed`を待つ。待機には上限を設け、確認済み終了とtimeout / transport切断を区別する。
- SIP切断後の音声は再生しない。終了後callbackのqueue再生成を拒否する既存保護を維持する。APIの残出力受信と電話への再生完了を区別する。

### 原文・翻訳字幕とモニター

- 現在の`ConversationEvent`は`speaker=caller/assistant`のみ。互換性を維持しつつ、字幕種別（原文 / 翻訳）、backend、目的言語を識別する追加情報を検討する。翻訳文をAI応対と誤認させない表示にする。
- 原文と翻訳を独立した表示segmentとして集約する。`sourceEventId`による重複対策、再接続世代ごとのsegment ID、履歴 / SSEの最新snapshot優先を実装する。
- `elapsed_ms`が欠ける場合も処理し、原文と翻訳を同じ時刻だからと一対一対応させない。API確定turnを捏造せず、表示区切りと確定状態を区別する。
- 原文字幕の有効化とログ保存を分離し、モニター利用のために機密ログを常時増やさない。既存Realtimeの確定字幕とLive snapshotの扱いを維持する。
- API設定schemaのtranscriptionは`model`を公開する一方、server event例には`language`も現れる。例だけを根拠に既存の入力言語キーをtranslationへ送信しない。

## 実装の進め方と受入確認

1. ユーザー回答の反映は完了。本計画の実装承認を受領済み。
2. 設定・factory・音声方針を拡張し、既存Realtime / Liveの回帰確認を行う。
3. translation WebSocket、PCM入出力、無音継続、終了・再接続を実装する。
4. 字幕・モニターと診断ログを追加する。
5. 各仕様変更と同じ変更単位で`docs/system-design.md`と`docs/diagrams/telephony-openai-gw-design.drawio`を更新する。図は構成・クラス・音声queue・通話フローを更新し、`OpenAI Translation Sequence`ページを追加する。README、設定例、運用手順も同期する。

オフライン検証:

- 既存設定例3種類と新例の読込、未指定backendの互換性、不正言語・不要設定の検証。
- mock WebSocketによる開始確認、送信順序、エラー、timeout、開始中切断、重複close、古いcallback、再接続、backlog上限。
- 200ms組立・無音継続・欠損・端数flush、可変長 / 奇数byte出力、8kHz / 16kHz変換、同時スロット分離。
- 翻訳音声の再生中も入力が届くこと、既存Realtimeのbarge-in / 入力破棄とLiveの継続入力が維持されること。
- 同値`elapsed_ms`の別delta、原文と訳文の非同期更新、再接続世代、履歴 / SSE競合をJava / JS検証へ追加。
- 既存のJava / JS検証、Gradle check、PJSUA2込みコンパイル、XML・差分チェック。cleanと直接compileを並列実行しない。

実API・実通話検証（オフライン成功と区別）:

- ユーザーが権限・実環境を準備したうえで、承認された言語方向を確認する。PCMU / G.722、複数通話、長い無音、切断・再接続を対象とする。
- 数字、名前、電話番号、専門語、日英混在、質問・命令を含む発話で、翻訳内容を二言語で確認する。同言語入力の無出力も評価する。
- 入力音声時刻、最初の訳音声受信、RTP再生、字幕到着、queue滞留を別々に測る。Realtimeのcommit起点の指標を流用しない。遅延の合格値は実測を見てユーザーと合意する。
- 回り込み・欠落・重複とRHEL 8.10の動作を確認する。実API権限・品質・実通話・RHELを未実施のまま検証済みと記載しない。

## 承認の経緯

計画作成段階では本書のみを保存し、コード変更を行わなかった。その後ユーザーが言語・用途・出力を選択し、専用translation backendをRealtime / GPT-Liveと同列に設定選択できる前提で実装開始を承認した。実環境の機密設定は変更しない。


## 実装結果（2026-10-15）

承認された範囲で`openai.backend: translation`を追加した。専用の`TranslationSession`と`TranslationTranscriptAssembler`、3方式混在の`config/gateway.translation.example.yaml`を追加。Realtime / Liveの音声処理を維持し、Liveのdecoderは`StreamingAudioDecoder`へ共通化した。

実装上の具体化:

- 入力は内部100 frameのqueueと20ms clockを使い、開始60ms後から不足分だけ無音補完する。10 frameを200ms単位で送信する。1秒超のclock停止は失敗とする。遅れて到着した音声は既送信無音の置換ではなく、順に送信するため実機で遅延を評価する。
- 翻訳の目的言語は`en`を明示指定する。原文字幕は既定有効、字幕モデルは`gpt-realtime-whisper`。固定voice・bot設定は不要。字幕専用モード切替は今回追加していない。
- 共通bridgeをbackend名と継続音声方針で管理し、Realtimeの入力破棄やキャンセルをtranslationへ適用しない。translationの再生開始は2 frame、出力gap 100msで端数をflushする。
- モニターeventに`transcriptKind`と`targetLanguage`を追加し、既存eventのconstructorとUI更新を維持した。原文・翻訳・接続状態を表示する。字幕は4秒または512文字を目安にアプリ側で区切り、確定turnにはしない。
- 通信失敗は新sessionへの再接続対象とする。入力が来ない場合も健全性を監視し、既存の15秒cooldownで再試行する。切断時に失われた音声は復元しない。
- 終了時は受理済み内部入力をflushし、最長15秒残字幕を受信する。SIP切断後の音声は再生しない。終了確認とtimeoutを区別する。
- README、現行設計書、draw.io（既存ページ更新＋Translation Sequence追加）を同じ変更で更新した。

実APIの権限、翻訳品質、実通話、同時実通話、RHEL実機、ブラウザ外観は未検証。オフライン検証の結果は次項に記録する。


### 検証記録

- `scripts/check-voice.sh`: 既存41項目とtranslation専用54項目が成功。translationは3方式設定、目的言語検証、音声の200ms組立、8kHz / 16kHz出力、可変長境界、字幕種別・重複、終了drain、timeout、開始中切断、遅れて接続するsocket、送受信backlog、APIエラー、字幕無効化、自動clockの無音送信を確認した。
- `scripts/check-config.sh`: 既存3種類と`gateway.translation.example.yaml`の計4種類が成功。
- `node src/test/js/monitor-check.cjs`: 既存のsnapshot / 履歴競合に加え、原文・翻訳ラベルと重複表示抑止が成功。JS構文確認も成功。
- PJSUA2のJava bindingをclasspathへ含め、`src/main/java`と`src/pjsua2/java`の全ソースをコンパイルした。native bindingの読み込み確認も成功。既知のOpenH264警告は音声のみの検証には影響しなかった。
- draw.ioの7ページをXMLとして解析し、ページ内cell IDの一意性、文書内ローカルリンク、差分の空白チェックを確認した。ブラウザ・draw.ioの外観確認は未実施。
- `gradle --offline --no-daemon check`: 最終状態で成功（既存41項目＋translation54項目）。既定cacheではnativeサービスを起動できなかったため、既存の`.deps/gradle-cache`と制限外の実行許可を使用した。

ユーザーの追加指示により、コード・設定例・文書を`codex/experimental`へコミットし、GitHubへpushしてRHELから取得できる形で管理する。RHEL用のGit適用手順も差分反映ガイドに追加した。mainへのマージ、タグ変更、リリースは行わない。未追跡`bin/`と機密設定は変更していない。
