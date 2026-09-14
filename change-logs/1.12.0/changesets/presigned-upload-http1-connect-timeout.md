---
type: fixed
area: attachments
platforms:
  - android
  - ios
  - ohos
pull_request: 2491
rollout: full
---

## zh-CN

大附件直传云存储时，现在固定使用 HTTP/1.1 并放宽连接建立时间，避免因自动升级到不受支持的 HTTP/3 或网络稍慢而上传失败。

## en

Large attachment uploads now use HTTP/1.1 for the presigned storage transfer and allow more time to establish the connection, so automatic HTTP/3 upgrade attempts or slightly slow networks no longer cause uploads to fail.
