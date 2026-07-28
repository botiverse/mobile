---
type: improved
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 1669
rollout: full
---

## zh-CN

添加或移除表情回应、修改任务状态时，芯片会立即显示目标状态，不再用等待动画遮住操作结果；请求失败时仍会回到服务器确认的状态。

## en

Reaction and task-status chips now show the intended state immediately instead of covering the result with a loading spinner. A failed request still returns to the server-confirmed state.
