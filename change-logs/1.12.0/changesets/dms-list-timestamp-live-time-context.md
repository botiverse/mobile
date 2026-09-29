---
type: fix
area: home
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

修复私信列表会话行时间戳直接显示为未格式化原始 ISO 字符串（如 `2026-09-25T05:07:23.669Z`）的问题。确保私信页面与会话行正确继承带实时参考锚点的 `SlockTimeContext`，保证当天展示时分、跨天展示日期，并在无可用环境上下文时自动回落到系统时钟完成时间格式化。

## en

Fixed an issue where Direct Message conversation rows displayed raw unformatted ISO timestamp strings (e.g. `2026-09-25T05:07:23.669Z`). Direct message pages and rows now properly inherit an anchored live `SlockTimeContext` with real-time day and clock references, formatting same-day timestamps as clock times, past days with localized dates, and safely anchoring against system wall-clock time if ambient context is unavailable.
