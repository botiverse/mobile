---
type: fixed
area: diagnostics
platforms:
  - android
  - ios
  - ohos
pull_request: 2711
rollout: full
---

## zh-CN

修复提交反馈时部分已记录日志可能尚未送达诊断系统的问题，确保日志按接收顺序完成派发。

## en

Fixed feedback collection finishing before some accepted logs reached diagnostics, preserving their delivery order.
