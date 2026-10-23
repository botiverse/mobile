---
type: improved
area: agents
platforms:
  - android
  - ios
pull_request: 0
rollout: full
---

## zh-CN

把 Agent 迁移到另一台计算机时，目标计算机改为始终展开的列表，可以直接比较：每台计算机下显示该 Agent 运行时账户的剩余额度（例如"Claude Max · 5 小时 剩 72% · 本周 剩 40%"，已用完的窗口以橙色标出），以及剩余内存、剩余磁盘和已有的 Agent 数量；离线的计算机会标为"离线"。点每行末尾的 ⓘ 可以查看完整用量、内存和磁盘详情。计算机超过 5 台时，列表上方会出现搜索框，搜索时已选中的计算机始终显示在最上面。较旧版本的 Computer 不上报内存，这时只显示磁盘和 Agent 数量。

## en

When you move an agent to another computer, the target computers are now an always-open list you can compare at a glance: each computer shows the remaining quota of the agent's runtime account there (such as "Claude Max · 5h 72% left · Week 40% left", with a used-up window in orange), its free memory, free disk and how many agents it has; an offline computer says "Offline". The ⓘ at the end of a row opens its full usage, memory and disk. With more than 5 computers a search box appears above the list, and the chosen computer stays at the top while you search. Older Computers do not report memory; their line shows disk and agents only.
