---
type: fixed
area: notifications
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复多端共享的角标与未读计数同步偏移问题：
- 当 Home 与 Activity 共享已读版本检查点且 Home 先接收 `read_state:updated` 时，Activity 在收到 `Current` 结果时同样执行幂等的远端已读投影并发布未读摘要更新，避免跨设备已读后 Activity 未读数与角标滞留。
- 应用从后台切回前台及前台同步完成时重新向宿主下发计算后的应用角标，避免 Kotlin 侧计数保持 `0 -> 0` 时因 `distinctUntilChanged` 抑制而无法清除系统桌面残留角标。
- 修复多服务器账号下非当前服务器 `serverActivityUnread` 过期问题：在 Activity 标签页或会话详情页切回前台时节流刷新 `/servers/unread-summary`，并在收到非当前服务器的通知撤回、推送、新消息、Done 或已读状态事件时触发合并刷新。
- 修复 Activity 存在未加载分页（`hasMore=true`）时的 `totalUnreadCount` 偏移：页外增量同步更新/删除、页外本地已读确认、页外远端 Done 及页外行插入首页不再漏减或重复累加未读数，并在 `ProcessActivityUnreadStore` 中补齐 `ActivityDoneUpdated`、`NotificationRetracted` 与 `NotificationLevelChanged` 事件的即时摘要刷新。

## en

Fixed shared badge and unread count synchronization drift across platforms:
- `SharedActivityStore.applyPerReceiverReadStateChanged` now runs idempotent remote-read projection on `PerReceiverReadStateFactResult.Current` as well as `Accepted` when Home consumes the shared read-state checkpoint first, clearing Activity unread counts and emitting updated summary facts.
- Warm foreground returns and settled foreground syncs now re-emit `bridge.appBadge` even when Kotlin's computed badge stays `0 -> 0`, ensuring stale OS launcher / SpringBoard badges are reconciled without extra emissions on unrelated recompositions.
- Multi-server `serverActivityUnread` now stays fresh for non-selected servers: warm foreground returns while Home is hidden (Activity tab or pushed conversation) trigger a throttled `/servers/unread-summary` refresh, and non-selected server `NotificationRetracted`, `NotificationPushReceived`, `MessageNew`, `ActivityDoneUpdated`, `NotificationLevelChanged`, and `PerReceiverReadStateChanged` events schedule a coalesced summary refresh.
- Fixed off-page (`hasMore == true`) `totalUnreadCount` drift across incremental `inbox_row` sync upserts/deletes, row re-read inserts, acknowledged off-page read receipts, and off-page remote Done events, preventing double-counting and scheduling authoritative unread-count reconciles; added `ActivityDoneUpdated`, `NotificationRetracted`, and `NotificationLevelChanged` to `ProcessActivityUnreadStore`'s unread-summary triggers.

## ja

プラットフォーム共通のバッジおよび未読件数同期のズレを修正しました：
- Home と Activity が既読バージョンチェックポイントを共有し Home が先に `read_state:updated` を処理した場合でも、`SharedActivityStore` が `Current` 結果に対して冪等なリモート既読投影とサマリー更新を実行し、他端末での既読後に Activity 未読数やバッジが残らないようにしました。
- バックグラウンドからの復帰およびフォアグラウンド同期完了時に Kotlin 側の計算結果が `0 -> 0` のままでも `bridge.appBadge` を再送し、OS アイコン上の古いバッジが確実に解消されるようにしました。
- マルチサーバー環境で非選択サーバーの `serverActivityUnread` が古くなる問題を修正しました。Home が非表示の状態（Activity タブやスレッド画面）からの復帰時に `/servers/unread-summary` をスロットル付きで更新し、非選択サーバーの通知撤回・プッシュ・新着・Done・既読イベント時にも集約リフレッシュを実行します。
- 未読込ページが存在する場合（`hasMore == true`）のページ外 `inbox_row` 同期・行再取得挿入・既読確認・リモート Done による `totalUnreadCount` の二重計上や減算漏れを修正し、`ProcessActivityUnreadStore` の対象イベントに `ActivityDoneUpdated`・`NotificationRetracted`・`NotificationLevelChanged` を追加しました。
