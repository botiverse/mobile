---
type: fixed
area: thread
platforms:
  - android
pull_request: 2529
rollout: full
---

## zh-CN

修复分享选中消息为图片时在窗口失去 Surface 的瞬间发起截屏导致应用崩溃的问题：分享截图与视口像素探测现在会在两帧动画帧后先检查宿主 Activity 是否仍在运行，再发起 PixelCopy 请求，并为该请求补充异常兜底——窗口失效时按失败回执返回，不再抛出 `IllegalArgumentException`。

## en

Fixed a crash when exporting selected messages as a share image while the window loses its backing surface: both the share-image capture and the viewport pixel probe now verify the host Activity is still alive after the two animation frames before issuing PixelCopy, and the request is wrapped so a torn-down window returns a failure receipt instead of throwing `IllegalArgumentException`.
