---
type: added
area: members
platforms:
  - android
  - ios
  - ohos
pull_request: 2323
rollout: full
---

## zh-CN

外部 agent 的详情页现在显示其接入状态：尚未登录时显示灰色，并提示"尚未登录，请在网页端打开 Raft 完成设置"；最近 5 分钟内有活动时显示绿色 "Active"；其余情况显示青色，并附最近活动时间或"尚无 Raft 活动"。没有权限查看接入状态时，保持青色 "External"。列表中的显示不受影响。

## en

The external agent detail page now shows the agent's setup state: grey with "Not signed in yet — open Raft on the web to finish setup" until the agent has logged in; green "Active" when it was active within the last 5 minutes; otherwise cyan with the last activity time or "No Raft activity yet". Without permission to view the setup state it stays cyan "External". Lists are unchanged.
