---
type: fixed
area: network
platforms:
  - ios
  - ohos
pull_request: 1544
rollout: release
---

## zh-CN

修复 iOS 与 HarmonyOS 应用进入后台后，旧网络请求可能在恢复时继续占用加载状态的问题。应用级前后台切换现在会结束并取消旧代请求，重复的页面、窗口或短暂系统打断不会产生误切换；结果未确认的写请求仍不会自动重放。

## en

Fixed stale network requests potentially keeping loading state alive after iOS or HarmonyOS returned from the background. Application-level visibility changes now retire and cancel requests from the previous generation without treating page, window, or brief system interruptions as process transitions. Writes whose outcome is unknown are still never replayed automatically.
