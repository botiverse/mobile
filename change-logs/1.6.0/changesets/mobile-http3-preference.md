---
type: added
area: mobile-network
platforms:
  - android
  - ios
  - ohos
pull_request: 1235
rollout: general
---

## zh-CN

新增设备本地“启用 HTTP/3”设置。Android 与 iOS 默认关闭，开启后新请求会使用支持 HTTP/3 的 curl 网络通道；HarmonyOS 默认开启，也可关闭 HTTP/3 偏好而继续使用兼容的 curl 通道。网络不可用 HTTP/3 时会安全回落到 HTTP/2 或 HTTP/1.1，并保留真实协议诊断。

## en

Added a device-local “Enable HTTP/3” preference. Android and iOS keep it off by default and route new requests through the HTTP/3-capable curl transport when enabled. HarmonyOS enables the preference by default and can turn it off while retaining its compatible curl transport. When HTTP/3 is unavailable, requests can safely fall back to HTTP/2 or HTTP/1.1 while preserving truthful protocol diagnostics.
