---
type: fixed
area: attachments
platforms:
  - android
  - ios
pull_request: 0
rollout: full
---

## zh-CN

弱网下打开图片预览更稳：获取图片地址失败时会自动重试，仍失败则保留已显示的缩略图，并提示"无法加载完整图片"，可点"重试"。再次打开同一张图片不再重复请求地址。打开、关闭图片预览也不再让 thread 整页刷新。

## en

Image previews hold up better on weak networks: a failed image-address request is retried automatically, and if it still fails the thumbnail stays on screen with a "Couldn't load full image" notice and a Retry button. Reopening the same image no longer asks for the address again, and opening or closing the preview no longer refreshes the whole thread.
