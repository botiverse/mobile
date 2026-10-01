---
type: improved
area: tasks
platforms:
  - android
  - ios
  - ohos
pull_request: 2456
rollout: full
---

## zh-CN

Tasks 面板改为分页加载：首屏与后续翻页都只取摘要数据，打开面板更快、占用内存更低；滚动到列表底部会自动加载下一页，已加载的任务不会因为翻页而丢失或重复。

## en

The Tasks panel now loads in pages: both the first screen and later pages fetch summary data only, so the panel opens faster and uses less memory. Scrolling to the end loads the next page automatically, and already-loaded tasks are neither dropped nor duplicated.
