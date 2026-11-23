---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 5544
rollout: full
---

## zh-CN

创建 Agent 和编辑运行时设置的运行时选择器现优先使用各台 Computer 上报的实时能力信息：支持任务中途接新指令的运行时（如 OAR 路线上的 OpenCode、Cursor）不再显示「能力有限」标记并同步更新能力提示；如果推荐运行时在某台 Computer 上确实缺少能力，也会在保留「推荐」标记的同时显示对应提示。连接旧版服务器时自动回退到内置能力表。

## en

Runtime pickers in Create Agent and Edit Runtime Config now prefer each Computer's reported capabilities over the bundled fallback table: runtimes that support mid-turn steering on that Computer (such as OpenCode or Cursor on OAR) clear the Limited badge and update their capability hint, while recommended runtimes keep their Recommended badge and also show a limitation hint when a Computer explicitly reports a missing capability. Older servers continue falling back to the bundled table.
