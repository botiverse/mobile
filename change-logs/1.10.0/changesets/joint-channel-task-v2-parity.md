---
type: added
area: tasks
platforms:
  - android
  - ios
  - ohos
pull_request: 2156
rollout: full
---

## zh-CN

联合频道任务对齐：同一任务在多个 server 的本地频道可见时，现在折叠为唯一任务事实 —— Activity 中同一封任务消息只显示一行，打开时进入当前 server 的本地频道；系统通知也按消息合并为一条。任务携带单调版本号：过期更新会被忽略，版本冲突时拒绝写入并重新拉取，避免旧数据覆盖新数据。认领、修改状态等操作始终作用于当前选中的 server。

## en

Joint-channel task parity: when the same task is visible through several servers' local channels, it now folds into one canonical task fact — Activity shows a single row per task message and opens it in the current server's local channel, and system notifications coalesce into one per message. Tasks carry monotonic revisions: stale updates are ignored, and conflicting updates at the same revision refuse to write and trigger a refetch instead, so older data cannot overwrite newer data. Claiming a task or changing its status always applies to the currently selected server.
