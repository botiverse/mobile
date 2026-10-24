---
type: fixed
area: performance
platforms:
  - android
pull_request: 3663
rollout: full
---

## zh-CN

冷启动时不再先加载网页组件再显示首屏，应用打开更快，慢设备上也不易出现“应用无响应”。

## en

On a cold start the first screen no longer waits for the web view engine to load, so the app opens sooner and is less likely to show "app isn't responding" on slow devices.
