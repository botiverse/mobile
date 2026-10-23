---
type: fixed
area: activity
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

修复在“动态”列表中上下滚动时容易误触发左右滑动的问题。现在只有明显的横向滑动（横向移动超过约 16 到 24dp，且至少是纵向移动的两倍）才会滑动该行；一旦这次触摸偏向纵向，后续同一次触摸不会再变成横向滑动。屏幕边缘的返回手势不受影响。

## en

Fixed Activity rows swiping sideways while you scroll up or down. A row now only slides on a clear horizontal swipe (about 16 to 24dp sideways, and more than twice the vertical movement). Once a touch leans vertical, the rest of that touch never turns into a row swipe. The edge back gesture is unchanged.
