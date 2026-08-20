---
type: fixed
area: home
platforms:
  - android
  - ios
  - ohos
pull_request: 2209
rollout: full
---

## zh-CN

首页现在会在同一加载代次并行获取频道内容与侧边栏 Pin/排序配置，并在两者完成后一次性呈现，避免普通频道先出现、Pinned 随后补入造成的首屏闪烁。

## en

Home now fetches channel content and sidebar pin/order metadata in parallel within one load generation and publishes them together, avoiding a first-frame flash where ordinary channels appear before Pinned.
