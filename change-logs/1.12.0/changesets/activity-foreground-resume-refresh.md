---
type: fixed
area: activity
platforms:
  - android
  - ios
  - ohos
pull_request: 2515
rollout: full
---

## zh-CN

修复从后台切回应用时「动态/Activity」页面不自动刷新的问题：二级路由打开的动态页现在会在应用回到前台时自动拉取最新内容，之前只能手动下拉刷新。同时放开应用外壳对动态目标页的前台刷新屏蔽。

## en

Fixed the Activity feed not refreshing when returning from background: secondary-route Activity pages now fetch the latest content when the app comes back to the foreground, instead of requiring a manual pull-to-refresh. The app shell's foreground-refresh gate for the Activity destination is lifted as well.
