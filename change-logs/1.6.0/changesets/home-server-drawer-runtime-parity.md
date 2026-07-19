---
type: fixed
area: home
platforms:
  - android
rollout: canary
---

## zh-CN

修复多服务器抽屉首次打开卡顿、安全区与页面 header 底边不对齐、私信头像缺失、分区无法折叠以及会话行无法打开的问题；抽屉改为复用 Kuikly 的可滑动基础能力并由 AppShell 统一托管，可从任意主页面打开。新增持久化的“只看未读”过滤，并继续复用首页现有的服务器、分区、频道和私信行样式与交互。

## en

Fixed first-open stalls, safe-area/page-header misalignment, missing direct-message avatars, non-collapsible sections, and non-opening conversation rows in the multi-server drawer. The drawer now reuses Kuikly's movable drawer primitive at the AppShell root so any primary page can share it, adds a persisted unread-only filter, and retains the existing Home server, section, channel, and DM row styling and interaction contracts.
