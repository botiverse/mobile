---
type: fixed
area: home
platforms:
  - android
  - ios
  - ohos
pull_request: 2461
rollout: gradual
---

## zh-CN

修复私信列表在后台网络刷新时最新消息预览被偶发重置的问题：通过双层工作区与用户身份隔离，保证网络静默刷新时平滑保留已有的实时消息预览，避免列表项闪烁或预览内容跳变。

## en

Fixed an issue where latest message previews in the Direct Messages list could unexpectedly reset during background network refreshes: introduced dual-layer workspace and principal isolation to smoothly preserve existing realtime message previews during background sync, preventing list flickering and preview state loss.
