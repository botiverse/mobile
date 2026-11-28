---
type: added
area: notifications
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

新增移动端角标准确度与通知落地可观测性上报：
- 每次切回前台或冷启动后，在首个前台同步完成时上报一次 `mobile.badge.reconciled`（100% 采样，仅移动端，不携带 `delivery_id`），记录前台同步前角标 `icon_badge_before`、按 §1.4 计算的跨服务器未读总计 `activity_total`、有符号差值 `delta`、最近一次写入角标的来源 `last_badge_source`（`alert` / `sync` / `retract` / `app`）与耗时 `last_badge_age_ms`、启动类型 `launch`（`cold` / `warm`）以及同步成功状态 `sync_ok`。
- 为 `mobile.push.opened` 新增 5 项落地与时效属性（`landing_unread`、`landing_load_ok`、`push_age_ms`、`read_before_open`、`badge_at_open`）：点击瞬间采集角标、撤回状态与推送时长，并延迟至目标会话或 Activity 首次加载完成且尚未推进已读位置前捕获 `landing_unread` 后再上报；若加载失败、超过 8 秒超时、应用退至后台或被新推送点击覆盖，则立即以 `landing_load_ok=false` 刷出且不丢事件。

## en

Added mobile badge accuracy and notification landing telemetry:
- Emits `mobile.badge.reconciled` once per foreground (100% sampled, mobile-only, without `delivery_id`) after the first foreground sync settles, reporting pre-reconcile `icon_badge_before`, cross-server §1.4 `activity_total`, signed `delta`, `last_badge_source` (`alert` | `sync` | `retract` | `app`), `last_badge_age_ms`, `launch` (`cold` | `warm`), and `sync_ok`.
- Enriched `mobile.push.opened` with `landing_unread`, `landing_load_ok`, `push_age_ms`, `read_before_open`, and `badge_at_open`: tap-time values are snapshotted immediately on tap and emission defers until the destination conversation or Activity first load settles (capturing `landing_unread` before read advancement), with an 8-second timeout and immediate `landing_load_ok=false` flush on load failure, backgrounding, or superseded taps so opens are never dropped.

## ja

モバイルのバッジ精度および通知遷移先の可観測性テレメトリを追加しました：
- フォアグラウンド復帰・コールドスタートごとに最初の同期完了時に `mobile.badge.reconciled` を 1 回送信します（100% サンプリング、モバイル専用、`delivery_id` なし）。再計算前の `icon_badge_before`、§1.4 に基づくクロスサーバー未読合計 `activity_total`、符号付き差分 `delta`、最終バッジ更新元 `last_badge_source`（`alert` / `sync` / `retract` / `app`）と経過時間 `last_badge_age_ms`、起動種別 `launch`（`cold` / `warm`）、同期成否 `sync_ok` を記録します。
- `mobile.push.opened` に `landing_unread`、`landing_load_ok`、`push_age_ms`、`read_before_open`、`badge_at_open` の 5 属性を追加しました。タップ時点でバッジ数・撤回有無・経過時間を記録し、遷移先の会話または Activity の初回ロード完了時（既読位置更新前）に `landing_unread` を取得してから送信します。ロード失敗・8 秒タイムアウト・バックグラウンド移行・別通知タップによる上書き時は即座に `landing_load_ok=false` でフラッシュし、開封イベントを取りこぼしません。
