---
type: fixed
area: thread
platforms:
  - ios
  - ohos
pull_request: 1893
rollout: full
---

## zh-CN

在独立打开的对话页面中，即使主应用页面未挂载，远端新消息现在也会继续通过常驻实时连接进入共享消息存储。

## en

In a standalone Thread, remote messages now continue entering the shared message store through the process-lifetime realtime connection even when the main app shell is not mounted.
