---
type: changed
area: members
platforms:
  - android
  - ios
  - ohos
pull_request: 3148
rollout: full
---

## zh-CN

"删除 Agent"现在是对齐 Web 的"归档 Agent"：操作与文案改为归档，且当 Agent 在 Computer 上有工作区时，归档确认会提供"留在 Computer 上"或"直接删除工作区"两个选项——请求携带与 Web 相同的 `workspace` 字段（`DELETE /agents/:id` body）。已归档 Agent 的资料页显示"已归档"状态（只读）。

## en

Deleting an agent is now archiving it, matching the web Agent Archive dialog: the action and its copy read "Archive", and when the agent has a workspace on a Computer the confirmation offers "keep it on the Computer" or "delete the workspace" — the request sends the same `workspace` choice (`DELETE /agents/:id` body) the web already sends. Archived agents show the "Archived" state on their read-only profile.
