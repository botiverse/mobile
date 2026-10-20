---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 3204
rollout: full
---

## zh-CN

修复快速滑动时点击"回到底部"后列表又被惯性带离底部的问题：点击后会先停止正在进行的惯性滚动。

## en

Tapping "Back to bottom" during a fast fling now stops the fling first, so the list no longer drifts back off the bottom.
