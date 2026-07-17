---
type: fixed
area: composer
platforms:
  - android
  - ios
  - ohos
pull_request: 1161
rollout: full
---

## zh-CN

修复 joint channel 中直接输入 `@` 时只显示当前服务器成员的问题。候选列表现在仍优先展示当前服务器成员，同时会直接展示另一侧服务器的频道成员，无需先输入关键词搜索。

## en

Fixed the bare `@` suggestion list in joint channels showing only members from the current server. Suggestions still prioritize the current server while directly including channel members from the connected server, without requiring a search query first.
