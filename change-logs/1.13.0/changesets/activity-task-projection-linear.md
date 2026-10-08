---
type: fixed
area: activity
platforms:
  - android
  - ios
  - ohos
pull_request: 0
rollout: full
---

## zh-CN

修复在任务很多的服务器上进入“动态”时界面卡住几秒的问题：刷新动态时给条目匹配任务状态的计算从随任务数平方增长改为线性，不再长时间占住动态数据。

## en

Fixed the Activity tab freezing for several seconds on servers with many tasks. Matching task status to Activity rows now scales linearly with the number of tasks instead of quadratically, so a refresh no longer holds Activity data for seconds.
