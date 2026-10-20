---
type: added
area: notifications
platforms:
  - android
pull_request: TBD
rollout: full
---

## zh-CN

没有 Google Play 服务的 Android 设备（如华为、鸿蒙 NEXT 卓易通）无法接收推送。现在“后台保持连接”默认开启：Raft 会以一条常驻的静默通知“Raft 正在接收消息”在后台保持连接，继续发送消息通知。设置中可关闭该选项，并可通过“允许后台运行”关闭电池优化。需要已开启通知权限和消息通知。Google Play 版本不包含此功能。

## en

Android devices without Google Play services (for example Huawei, or HarmonyOS NEXT's 卓易通) cannot receive push notifications. "Keep connection in background" is now on by default there: Raft keeps its connection open in the background with a silent, persistent "Raft is receiving messages" notification, so message notifications keep arriving. Turn it off in Settings, and use "Allow running in background" to turn off battery optimization. Requires notification permission and message notifications to be on. The Google Play build does not include this.
