---
type: fixed
area: notifications
platforms: [ios]
rollout: full
---

## zh-CN

修复 iOS 端应用图标角标与通知中心撤回不准确的多项原生根因：
- 前台活跃状态下不再允许 `willPresent` 或延迟到达的静默 `sync`/`retract` 推送用过时的 APNs `badge` 覆盖应用角标（当前正打开的会话不再返回 `[.badge]`，前台通知不再覆盖由应用内状态维护的角标）；从非活跃态（如下拉通知中心）返回前台时自动对齐应用内角标。
- 将共享层 `RaftConversationReadHost` 接入 iOS，本地阅读会话（打开会话、标记已读、全部已读、标记完成）或点击通知时立即清除该会话在通知中心中的所有同组历史通知并写入本地撤回水位线，防止乱序迟到的旧推送再次弹出。
- 修复任务会话推送的可见会话匹配逻辑，按 `taskConversationChannelId`、`conversationId`、`route` 与 `raftCategory` 识别任务会话，避免正在查看任务会话时仍弹出系统通知或误匹配父频道。
- 退出登录或会话失效注销远程推送时同步清零应用图标角标、清空已投递通知及本地撤回水位线，避免残留幽灵角标与旧账号通知。

## en

Fixed iOS native app icon badge and Notification Center retraction accuracy root causes:
- Stopped foreground `willPresent` and delayed priority-5 background `sync`/`retract` pushes from overwriting the OS icon badge with stale APNs badge counts while the app is active (visible-conversation alerts now suppress cleanly with `[]`, foreground alerts omit `.badge`, and transient inactive-to-active transitions reconcile any badge drift against the foreground app badge without reapplying pre-background counts).
- Wired `RaftConversationReadHost` on iOS and updated notification tap handling so reading a conversation locally or tapping a notification immediately removes all delivered sibling Notification Center entries for that conversation group and records a local retract watermark to suppress late out-of-order alerts.
- Fixed visible-conversation matching for task conversation pushes using `taskConversationChannelId`, `conversationId`, `route`, and `raftCategory` (with `ConversationKind.Task` support in `RaftVisibleConversationRegistry`) so active task conversations stay silent and never false-match their parent channel.
- Cleared the OS icon badge (`0`), delivered Notification Center notifications, and persisted retract watermarks when remote notifications are disabled on sign-out or session invalidation.

## ja

iOS のアプリアイコンバッジおよび通知センター取り消しの精度に関する根本原因を修正しました：
- アプリがフォアグラウンドでアクティブな間、`willPresent` や遅延到着したバックグラウンドの `sync`／`retract` プッシュが古い APNs バッジ値でアプリアイコンバッジを上書きしないようにしました（表示中の会話は `[]` で完全に抑制し、フォアグラウンド通知から `.badge` を除外。一時的な非アクティブ状態からの復帰時はフォアグラウンドの最新バッジと再整合します）。
- 共有層の `RaftConversationReadHost` を iOS に接続し、アプリ内で会話を既読にした際や通知をタップした際に、同一会話グループの配信済み通知を通知センターから即時一括削除し、遅延到着した旧通知を抑制するローカル撤回ウォーターマークを記録するようにしました。
- タスク会話プッシュの表示中会話判定に `taskConversationChannelId`、`conversationId`、`route`、`raftCategory`（および `RaftVisibleConversationRegistry` の `ConversationKind.Task` 対応）を使用し、閲覧中のタスク会話で通知が鳴ったり親チャンネルと誤一致したりしないようにしました。
- サインアウトやセッション失効に伴うリモート通知無効化時に、アイコンバッジ（`0`）、通知センターの配信済み通知、およびローカル撤回ウォーターマークをクリアし、ゴーストバッジや前アカウントの通知が残らないようにしました。
