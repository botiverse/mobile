---
type: fixed
area: thread
platforms:
  - android
pull_request: 0
rollout: full
---

## zh-CN

修复安卓上在消息较少的讨论串里快速甩动时出现的大片空白：列表会冲进一段没有消息的区域，停下时还会跳一下。现在列表在到达最早的消息时会立即停住。同时恢复了讨论串列表两端的回弹效果：滑到最顶或最底时可以继续往外拉一点，松手会自动弹回。

## en

Fixed large blank areas when fast-flinging a short thread on Android: the list could run past the oldest message into an empty region and then jump when it settled. It now stops at the oldest message. The thread list also bounces again at both ends: you can pull slightly past the top or bottom and it springs back.

