---
type: improved
area: sync-core
platforms:
  - android
  - ios
  - ohos
pull_request: 1172
rollout: canary
---

## zh-CN

提升消息实时同步的稳定性与一致性：在受控范围内先完成规范化持久化，再更新会话界面，降低重连、消息更新和状态恢复时出现重复、乱序或状态不一致的风险。

## en

Improved real-time message sync stability and consistency by committing normalized state before updating conversation surfaces in a controlled rollout, reducing the risk of duplicate, out-of-order, or inconsistent state during reconnects, message updates, and recovery.
