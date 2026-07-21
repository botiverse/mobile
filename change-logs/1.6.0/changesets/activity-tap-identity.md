---
type: fixed
area: activity
platforms:
  - android
  - ios
  - ohos
pull_request: 1365
rollout: full
---

## zh-CN

修复 Activity 列表在实时刷新或条目重排期间，点击一条动态却偶发打开另一条动态的问题。列表变化与点击重叠时，现在只会打开用户按下的同一条动态，无法安全确认时则取消本次点击。

## en

Fixed Activity occasionally opening a different item from the one tapped when realtime refreshes or row reordering overlapped the gesture. The app now opens only the item originally pressed, or safely cancels the tap when that identity can no longer be confirmed.
