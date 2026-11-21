---
type: fixed
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: 5262
rollout: full
---

## zh-CN

修复 iOS 各类底部弹窗与侧边抽屉弹出时叠加缩放淡入动画的问题：底部与侧边滑出面板现在只执行平滑的跟手滑入与同步遮罩淡入，并遵循系统的「减弱动态效果」设置。同时修复下拉关闭 Ottie 助理面板滑出屏幕后短暂拦截点击的问题。

## en

Fixed bottom sheets and side drawers on iOS stacking an extra zoom-and-fade window transition on top of their slide animation; sliding panels now fade their scrim in sync with the slide and honor Reduce Motion. Dragging the Ottie assistant panel down to close also dismisses immediately once off-screen instead of briefly blocking taps.

## ja

iOS のボトムシートやサイドドロワーが開く際にズーム＋フェード遷移がスライドアニメーションに重なっていた問題を修正し、背景のフェード同期と「視差効果を減らす」設定に対応しました。また、Ottie アシスタントパネルを下へドラッグして閉じた直後に一時的にタップが反応しない問題も解消しました。
