---
type: fixed
area: tasks
platforms:
  - android
  - ios
  - ohos
pull_request: 1366
rollout: full
---

## zh-CN

修复对话流中的任务编号引用在任务数据已加载时仍重复请求网络、导致打开缓慢的问题；连续点击不同任务引用时，较早的查询结果也不会再覆盖最新选择。

## en

Fixed task-number references in conversations making redundant network requests and opening slowly even when task data was already loaded. Older lookup results also no longer override the latest task-reference tap.
