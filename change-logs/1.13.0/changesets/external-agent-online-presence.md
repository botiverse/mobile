---
type: fixed
area: members
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

外部 Agent 的状态不再固定显示「External」。与网页一致：最近 2 分钟内收到过该 Agent 的活动（`agent:seen`）就显示为「在线」（绿色），否则显示「上次活跃 {时间}」。成员页与详情页共用同一条判断，数据来自 `GET /agents*` 的 `lastSeenAt` 和实时 `agent:seen` 事件，不新增请求。

## en

External agents no longer render a hard-coded "External". Matching the web: an agent is shown **online** (green) while `agent:seen` arrived within the last 2 minutes, otherwise "Last active {time}". The members list and detail page share the same rule, sourced from `lastSeenAt` on `GET /agents*` and realtime `agent:seen` — no extra requests.
