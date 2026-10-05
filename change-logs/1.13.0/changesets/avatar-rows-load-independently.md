---
type: fixed
area: shared
platforms:
  - android
  - ios
  - ohos
pull_request: 0
rollout: full
---

## zh-CN

修复服务器头像等图片头像可能一直显示黑块的问题：同一头像出现在多处时，每处都会自行加载，不再等待另一处先显示；加载完成前显示服务器首字母。

## en

Fixed image avatars (such as server avatars) that could stay black: when the same avatar appears in several places, each one now loads on its own instead of waiting for another to show first, and the server's initial shows until the image arrives.
