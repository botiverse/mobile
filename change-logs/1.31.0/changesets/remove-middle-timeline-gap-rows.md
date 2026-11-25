---
type: fixed
area: thread
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

移除了会话时间线中夹在两条消息之间的中间加载/缺口提示行：无论是线上还是离线、普通进入还是定位消息进入、频道/私聊/任务还是回复串，时间线现在始终只展示单一连续的消息片段，不再跨过未验证的本地缓存缺口拼接多段孤立消息；加载中与「离线状态下无法加载更早的消息」/「会话开始」状态只出现在时间线最外侧边界。

## en

Removed mid-timeline gap and loading rows between messages across all conversation surfaces (online and offline, unanchored and anchored opens, channels/DMs/tasks and reply threads). Conversation timelines now always render a single contiguous slice of messages without bridging across unverified local cache gaps, keeping loading, offline-unavailable, and beginning-of-conversation states strictly at the outer timeline boundaries.

## ja

すべての会話画面（オンライン・オフライン、通常表示・メッセージ位置指定表示、チャンネル／DM／タスクおよび返信スレッド）において、メッセージの間に挿入される途中の読み込み／ギャップ行を廃止しました。タイムラインは未検証のローカルキャッシュ欠損をまたいで断片を結合せず、常に単一の連続したメッセージ範囲のみを表示し、読み込み中・オフライン時はこれ以前のメッセージを読み込めない旨・「会話の始まり」表示はタイムラインの外側境界のみに統一しました。
