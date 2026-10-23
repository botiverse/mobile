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

滑动时露出的操作层现在有两种样式：未达到松手即执行的距离时为柔和色，达到后变为实心色；手指退回阈值以内会变回柔和色。文案缩短为“完成 / 取消关注 / 静音”（长按菜单保持完整名称），Android 在变为实心的瞬间有一次轻微触感。撤销提示统一保持 8 秒。

## en

Fixed Activity rows swiping sideways while you scroll up or down. A row now only slides on a clear horizontal swipe (about 16 to 24dp sideways, and more than twice the vertical movement). Once a touch leans vertical, the rest of that touch never turns into a row swipe. The edge back gesture is unchanged.

The action revealed behind a swiped row now has two looks: soft until releasing would apply the action, then solid; moving the finger back under that distance returns it to soft. Its label is short (Done / Unfollow / Mute; the press-and-hold menu keeps the full names), Android gives one light tick when it turns solid, and the Undo toast stays 8 seconds for both directions.
