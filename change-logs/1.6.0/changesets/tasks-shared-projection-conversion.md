---
type: fixed
area: tasks
platforms:
  - android
  - ios
  - ohos
pull_request: 1150
rollout: full
---

## zh-CN

消息与任务的相互转换统一走共享投影层，修复转换后偶发状态不一致（任务状态与消息不同步）的问题。

## en

Message-to-task conversion now converges through the shared projection layer, fixing occasional inconsistencies where task status and the underlying message drifted apart.
