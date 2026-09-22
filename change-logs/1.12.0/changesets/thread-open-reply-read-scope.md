---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

修复从消息下方「N 条回复」入口打开 thread 时，客户端对整个 thread 频道执行 read-all、把未渲染的新回复也一并标为已读的问题。现在只按实际渲染到的 seq 推进已读游标。

## en

Fixed an issue where opening a thread via the "N replies" chip marked the entire thread channel as read, clearing unread rows that were never rendered. The read cursor now advances only to the latest rendered seq.
