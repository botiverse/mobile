---
type: added
area: tasks
platforms:
  - android
  - ios
  - ohos
pull_request: 2484
rollout: full
---

## zh-CN

Tasks 面板的看板视图支持按状态独立翻页：每一列各自维护自己的加载位置，滚动到某一列底部时只加载该列的下一页，列与列之间互不影响；短列或空列不会触发多余的加载。

## en

The Tasks board view now pages independently per status: each column keeps its own load position, so reaching the bottom of a column loads only that column's next page without affecting the others; short or empty columns never trigger extra loads.
