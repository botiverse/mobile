---
type: improved
area: network
platforms:
  - android
  - ios
pull_request: 3614
rollout: full
---

## zh-CN

网络栈统一：上传、下载、图片和接口请求都走同一套系统网络连接，断网恢复后更快重新连上。设置中的"实时连接使用新网络栈（curl）"开关已移除。

## en

One network stack for everything: uploads, downloads, images and requests share the same platform connection and recover faster after the network drops. The "realtime over the new network stack (curl)" setting has been removed.
