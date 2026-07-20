---
type: fixed
area: activity
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

修复 Activity 已加载多页内容后，实时更新、已读状态或前台刷新可能让列表突然回到加载状态并截断后续内容的问题。刷新现在会保留已加载深度和列表位置，并合并短时间内的重复刷新。

## en

Fixed realtime, read-state, or foreground refreshes making Activity return to loading and truncate previously loaded pages. Refreshes now preserve the loaded depth and list position while coalescing short bursts of duplicate work.
