# Realtime / GPT-Liveのvoice設定

公式仕様・実装の確認日: 2026-10-13（日本時間）

本書は、`codex/experimental`で指定するvoiceをbackend別に整理する。公式資料の対応一覧と本Gatewayの設定方法を記載し、全voiceの実API疎通・日本語音声を検証済みとは扱わない。

## 設定キーの違い

| 項目 | Realtime | GPT-Live |
| --- | --- | --- |
| backend | `openai.backend: realtime`（省略可） | `openai.backend: live` |
| model | `openai.realtimeModel` | `openai.liveModel` |
| voice | `openai.voice` | `openai.liveVoice` |
| Gatewayのvoice既定値 | `shimmer` | `marin` |
| 変更を反映する方法 | 設定変更後、Gatewayを再起動し、新しい通話で確認 | 設定変更後、Gatewayを再起動し、新しい通話で確認 |

設定は`session.<slotId>`セクションのドット付きキーに記載する。スロットごとに独立して設定できる。`openai.realtimeModel`だけを変更してもLiveへ切り替わらず、Liveでは`openai.voice`も使用しない。

## Realtimeのvoice

Realtimeの公式ガイドと本Gatewayの設定検証で対応する組み込みvoiceは、次の10種類。

| `openai.voice`の値 | 公式資料・Gatewayで確認できる事項 |
| --- | --- |
| `alloy` | 対応する組み込みvoice。確認したRealtime資料に個別の声質説明はない |
| `ash` | 同上 |
| `ballad` | 同上 |
| `coral` | 同上。既存設定例のsession-1で使用 |
| `echo` | 対応する組み込みvoice。確認したRealtime資料に個別の声質説明はない |
| `sage` | 同上 |
| `shimmer` | 同上。GatewayのRealtime既定値と既存設定例のsession-2で使用 |
| `verse` | 対応する組み込みvoice。確認したRealtime資料に個別の声質説明はない |
| `marin` | 公式ガイドが音声品質の観点で推奨するvoice |
| `cedar` | 公式ガイドが音声品質の観点で推奨するvoice |

対応一覧と`marin` / `cedar`の推奨は[公式Realtime voice options](https://developers.openai.com/api/docs/guides/realtime-conversations#voice-options)による。落ち着き、明るさ、声の高さなどは、この資料に基づいて断定しない。

設定の抜粋:

```yaml
session.session-1:
  openai.backend: realtime
  openai.realtimeModel: gpt-realtime
  openai.voice: coral
```

Realtime APIでは、sessionが一度音声を出力した後にvoiceを変更できない。本Gatewayは実行中の設定再読込を提供していないため、再起動後の新しい通話で反映する。

## GPT-Liveのvoice

LiveのCreate session API referenceには、次の31種類が列挙されている。既定voiceは`marin`。以下の「未記載」は、確認したLive資料に言語・地域・声の印象の個別説明が見当たらなかったことを示す。名前から特徴を推測しない。

| `openai.liveVoice`の値 | 言語プロフィール | 地域による話し方の傾向 | 声の印象・補足 |
| --- | --- | --- | --- |
| `alloy` | 未記載 | 未記載 | Realtimeでも利用可能 |
| `ash` | 未記載 | 未記載 | Realtimeでも利用可能 |
| `ballad` | 未記載 | 未記載 | Realtimeでも利用可能 |
| `beacon` | 英語 | フィリピン | 男性的 |
| `bossa` | ポルトガル語 | ブラジル | 女性的 |
| `brise` | 未記載 | 未記載 | 個別の声質説明は未確認 |
| `cedar` | 未記載 | 未記載 | Realtimeでも利用可能 |
| `cinder` | 英語 | 米国南部 | 男性的 |
| `coral` | 未記載 | 未記載 | Realtimeでも利用可能 |
| `delta` | 英語 | 米国南部 | 女性的 |
| `echo` | 未記載 | 未記載 | Realtimeでも利用可能 |
| `flitz` | 未記載 | 未記載 | 個別の声質説明は未確認 |
| `gleam` | 英語 | 北米 | 女性的 |
| `harema` | 未記載 | 未記載 | 個別の声質説明は未確認 |
| `juni` | 未記載 | 未記載 | 個別の声質説明は未確認 |
| `marin` | 未記載 | 未記載 | Live APIとGatewayの既定値 |
| `meridian` | 英語 | 北米 | 男性的 |
| `nira` | 未記載 | 未記載 | 個別の声質説明は未確認 |
| `noeul` | 未記載 | 未記載 | 個別の声質説明は未確認 |
| `nuri` | 未記載 | 未記載 | 個別の声質説明は未確認 |
| `quartz` | 英語 | オーストラリア | 女性的 |
| `ripple` | 英語 | オーストラリア | 男性的 |
| `sage` | 未記載 | 未記載 | Realtimeでも利用可能 |
| `shida` | 未記載 | 未記載 | 個別の声質説明は未確認 |
| `shimmer` | 未記載 | 未記載 | Realtimeでも利用可能 |
| `sillage` | 未記載 | 未記載 | 個別の声質説明は未確認 |
| `stone` | 英語 | アイルランド | 男性的 |
| `tempo` | ポルトガル語 | ブラジル | 男性的 |
| `verse` | 未記載 | 未記載 | Realtimeでも利用可能 |
| `vesper` | 英語 | イギリス | 男性的 |
| `willow` | 英語 | アイルランド | 女性的 |

対応名と既定値は[公式Live Create session](https://developers.openai.com/api/reference/resources/live/methods/create)、12種類の言語・地域・声の印象は[公式Live voice options](https://developers.openai.com/api/docs/guides/live-conversations#voice-options)を参照する。「女性的」「男性的」は公式のPresentation欄の訳であり、声の印象を表す。

API referenceとSDK別referenceでは列挙内容が異なる場合がある。本表は確認日時のCreate session API referenceを基準とし、将来のモデル・API変更や実アカウントでの利用可能性は接続時に確認する。

設定の抜粋:

```yaml
session.session-2:
  openai.backend: live
  openai.liveModel: gpt-live-1
  openai.liveVoice: marin
  openai.liveStartBufferFrames: 2
```

Live APIではsession開始後にvoiceを変更できない。本Gatewayでは設定変更・再起動後の新しい通話で確認する。完全な混在設定例は[gateway.live.example.yaml](../config/gateway.live.example.yaml)を参照する。

## 設定検証とcustom voiceの扱い

- Realtimeは上記10種類をGatewayの設定読み込み時に検証する。
- Liveはvoice文字列が空でないことをGatewayで検証する。31種類の固定リスト照合は実装しておらず、タイプミスや未対応voiceは実API接続時にエラーとなり得る。設定check成功はAPI利用可能性の確認ではない。
- OpenAI APIにはcustom voice objectによる指定もあるが、本Gatewayはvoiceを文字列として送信する実装のため、custom voice objectの設定には対応していない。

## 日本語の電話応対で比較する方法

表の言語・地域は声のプロフィールであり、日本語の発音品質を保証するものではない。日本語の会話方針は`bot.systemInstructions`に指定し、同じ電話・codec・文面で比較する。

1. 初回挨拶、日時・数字、固有名詞を同じ文面で話させる。
2. 聞き取りやすさ、抑揚、相づち、言い直しへの応対を確認する。
3. PCMUとG.722で比較する場合はcodecごとに評価を分ける。

現在のLive設定例の`marin`を基準に、voiceだけを変更して比較できる。voiceを変えれば応答待ち時間や割り込みが必ず改善するとは扱わない。
