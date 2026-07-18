---
type: fixed
area: app-shell
platforms:
  - android
pull_request: 1260
rollout: full
---

## zh-CN

修复 Android 12 及以上版本冷启动等待首页首帧时 Raft 图标消失的问题；等待期间现在会持续显示居中的品牌图标，原有 1.5 秒兜底时间不变。

## en

Fixed the Raft icon disappearing while Android 12+ waits for the first Home frame during cold start. The centered brand icon now remains visible throughout the existing 1.5-second fallback window.
