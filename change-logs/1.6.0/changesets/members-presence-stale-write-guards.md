---
type: fixed
area: members
platforms:
  - android
  - ios
  - ohos
pull_request: 1205
rollout: full
---

## zh-CN

修复在线状态被过期数据回写覆盖的问题：陈旧的在线/离线事件不会再顶掉更新的状态，成员在线点与状态栏更准确。

## en

Fixed presence state being overwritten by stale writes: outdated online/offline events no longer clobber newer state, making member presence indicators more accurate.
