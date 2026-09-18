---
type: fixed
area: navigation
platforms:
  - android
  - ios
  - ohos
pull_request: 2514
rollout: gradual
---

## zh-CN

修复 Slack 底部导航模式（`mobile_slack_nav_v0`）下 Activity 页签的实时消息丢失与前台/切换刷新缺失问题：为 `SharedHomeShellStore` 增加 `selectedTab` 同步，使选中的 Activity 能够正确接收实时事件流（`acceptRealtimeEvent`），并在页签切换与应用前台唤醒时触发活动列表自动刷新。

## en

Fix Activity tab realtime event delivery and foreground/switch refresh under Slack bottom navigation mode (`mobile_slack_nav_v0`): synchronize `selectedTab` into `SharedHomeShellStore` so active Activity receives realtime event streams (`acceptRealtimeEvent`), and triggers automatic refreshes on tab switch and application foreground resume.
