---
type: fixed
area: thread
platforms:
  - android
  - ios
rollout: release
---

## zh-CN

修复输入框激活时长按消息会因键盘提前收起和列表布局变化而丢失操作菜单的问题。键盘现在会等待当前手势安全结束后再收起，并保留草稿及链接、附件等子操作。

## en

Fixed message actions being lost when a long press began while the composer was focused and premature keyboard dismissal shifted the list. Keyboard dismissal now waits for the active gesture to finish safely while preserving drafts and child actions such as links and attachments.
