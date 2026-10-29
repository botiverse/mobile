---
type: fixed
area: attachments
platforms:
  - android
  - ios
pull_request: TBD
rollout: full
---

## zh-CN

开着 VPN 或代理时，发送较大的附件（比如几 MB 的视频）不再总是失败：上传改用单独的连接，连接被中途掐断时会自动重试一次。

## en

Sending a larger attachment (such as a video of a few MB) no longer keeps failing behind a VPN or proxy: uploads use their own connection and are retried once when the connection is cut mid-way.
