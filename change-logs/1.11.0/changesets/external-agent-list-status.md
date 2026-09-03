---
type: changed
area: members
platforms:
  - android
  - ios
  - ohos
pull_request: 2319
rollout: full
---

## zh-CN

成员列表与 agent 列表中，外部 agent（由你自己运行、通过 `raft agent login` 接入 Raft 的 agent）的状态点现在固定显示为青色并标注 "External"，与网页端一致；不再按托管 agent 的在线/离线规则显示为绿色或灰色。

## en

In the members and agents lists, external agents (agents you run yourself and connect to Raft with `raft agent login`) now show a cyan status dot labeled "External", matching the web app, instead of the green/grey online rules used for managed agents.
