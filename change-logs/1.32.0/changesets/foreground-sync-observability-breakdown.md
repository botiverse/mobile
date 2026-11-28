---
type: improved
area: activity
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

为切回前台的增量同步诊断埋点（`activity:foreground_sync_updated`）补齐失败原因码（`failure_code`、`cursor_kept`）、互斥锁等待与快照抖动耗时拆解（`mutex_wait_ms`、`snapshot_jitter_ms`、`pull_duration_ms`）及同步页/重置状态字段，便于定位前台刷新失败与长尾耗时来源。

## en

Adds failure reason codes (`failure_code`, `cursor_kept`), mutex-wait and snapshot-jitter timing breakdowns (`mutex_wait_ms`, `snapshot_jitter_ms`, `pull_duration_ms`), and sync page/reset metadata to the `activity:foreground_sync_updated` foreground-return telemetry event.

## ja

フォアグラウンド復帰時の差分同期計測イベント（`activity:foreground_sync_updated`）に、失敗理由コード（`failure_code`・`cursor_kept`）、ミューテックス待機およびスナップショットジッター時間の内訳（`mutex_wait_ms`・`snapshot_jitter_ms`・`pull_duration_ms`）、ページ数・リセット理由の診断項目を追加しました。
