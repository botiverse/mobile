---
type: fix
area: activity
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复从「动态」列表点击进入有新消息的频道、跨服频道或私聊时，首次进入可能命中旧预热快照而看不到新消息、必须返回再进入才出现的问题：现已为频道与私聊同步纳入预热新鲜度账本，在实时新消息到达或动态列表刷新推进时立即失效旧预热快照。

## en

Fixed an issue where opening a channel, joint channel, or DM from Activity after a new message arrived could consume a stale prewarmed snapshot on the first visit and require going back and re-entering to see the new message: channel and DM rows now participate in the thread freshness ledger and immediately invalidate stale prewarms on live messages and Activity refreshes.

## ja

アクティビティから新着メッセージのあるチャンネル・共有チャンネル・DM を開いた際、初回表示で古いプリウォームスナップショットが再利用されて最新メッセージが見えず、一度戻って入り直す必要があった不具合を修正しました。チャンネルおよび DM 行もプリウォーム鮮度台帳の対象とし、リアルタイム新着や一覧更新時に古いキャッシュを即時無効化します。
