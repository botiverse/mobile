---
type: fixed
area: shared
platforms:
  - android
pull_request: 2785
rollout: full
---

## zh-CN

Android 上桌面分栏模式打开讨论串面板时，深层布局的放置递归会耗尽 Kuikly 渲染线程默认的 1 MB 栈并导致崩溃；现在该线程使用 8 MB 栈。

## en

On Android, opening a thread pane in the desktop split layout could exhaust the Kuikly render thread's default 1 MB stack during deep layout placement and crash; that thread now runs with an 8 MB stack.
