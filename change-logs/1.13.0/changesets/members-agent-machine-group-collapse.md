---
type: added
area: members
platforms:
  - android
  - ios
  - ohos
pull_request: 2672
rollout: full
---

## zh-CN

成员页的 Agent 电脑分组现在可以单独收起：点击某台电脑的标题行只折叠这台电脑下的 Agent，保留电脑名和数量，无电脑的分组同样可折叠。展开状态按「当前用户 + 电脑 id」记在本机（与网页侧栏同一 `slock:sidebarCollapsed` 键族），电脑改名后记录仍然生效。默认全部展开。

## en

Agent machine groups on the members page are now individually collapsible: tapping a computer row collapses only that machine's agents while keeping its name and count visible; the no-machine group behaves the same. The collapsed state is remembered on-device, scoped by the current user and machine id (same `slock:sidebarCollapsed` key family as the web sidebar), so it survives machine renames. Groups default to expanded.
