---
type: fixed
area: home
platforms:
  - android
  - ios
  - ohos
pull_request: 2827
rollout: full
---

## zh-CN

修复服务器头像在图片请求卡住时一直显示黑块的问题：等待超时后头像会自行重新加载。

## en

Fixed server avatars staying black when their image request stalled; the avatar now reloads on its own after a timeout.
