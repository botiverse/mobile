---
type: fixed
area: activity
platforms:
  - android
  - ios
pull_request: 3610
rollout: full
---

## zh-CN

修复在“动态”列表中上下滚动时容易误触发左右滑动的问题。现在只有明显的横向滑动（横向移动超过约 16 到 24dp，且至少是纵向移动的两倍）才会滑动该行；一旦这次触摸偏向纵向，后续同一次触摸不会再变成横向滑动。屏幕边缘的返回手势不受影响。

往左滑会在行尾露出一排按钮：完成，以及这一行能用的取消关注或静音。滑过一半松手，按钮会停在打开状态，点哪个就执行哪个；点别处、滚动或往回滑就收起，同时只开一行。一直拉到底仍然直接完成，Android 有一次轻微触感。不再支持右滑。撤销提示保持 8 秒。

## en

Fixed Activity rows swiping sideways while you scroll up or down. A row now only slides on a clear horizontal swipe (about 16 to 24dp sideways, and more than twice the vertical movement). Once a touch leans vertical, the rest of that touch never turns into a row swipe. The edge back gesture is unchanged.

Swiping a row left now uncovers a row of buttons at its end: Done, plus Unfollow or Mute when the row supports them. Release past halfway and the buttons stay open to tap; tapping elsewhere, scrolling or swiping back closes them, and only one row is open at a time. Pulling all the way across still marks it done right away, with one light tick on Android. Right swipe is gone. The Undo toast stays 8 seconds.
