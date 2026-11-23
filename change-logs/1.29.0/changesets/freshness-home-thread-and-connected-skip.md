---
type: improved
area: realtime
platforms:
  - android
  - ios
  - desktop
rollout: general
---

## zh-CN

对齐新版数据新鲜度规范（#198）：主页、私信、动态与对话页接入统一的新鲜度恢复信号（断线重连和网络恢复后静默补拉消息与卡片状态，不打断滚动或输入），并在实时连接未中断且距上次检查不足 60 秒时跳过重复的切回前台刷新，减少桌面端和移动端频繁切窗带来的无效请求。

## en

Aligned Home, DMs, Activity, and conversation pages with the unified freshness signal (#198): silent catch-up now runs on realtime reconnect and network recovery without interrupting scroll or gestures, and foreground returns within 60 seconds skip redundant refetches when the server's realtime socket stayed continuously connected.
