---
type: fixed
area: ui
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
优化列表拖拽、浮层点击与消息列表动效：高刷屏拖拽排序边缘自动滚动速度与 60Hz 保持一致；消息长按涟漪严格跟随动画收尾而不再被慢速动画倍率提前打断；浮层与菜单外点击关闭不再有 150ms 延迟空窗，同时不会因松开长按手指误关；动态刷新率屏幕（LTPO/VRR）在滚动与页面切换降频时不再误报掉帧。

## en
Improved drag auto-scroll, popover dismissal, and thread list motion: edge auto-scroll during drag reordering now moves at a consistent physical speed across 60/90/120 Hz displays; message press ripples wait for the fade animation to finish instead of cutting off early under slower system animation scales; tapping outside popovers and menus dismisses immediately without a 150 ms dead window while still ignoring the release of a long-press gesture; and variable-refresh-rate (LTPO) displays no longer misreport downclocked scroll or navigation frames as jank.

## ja
ドラッグ時の端自動スクロール、ポップオーバーのタップ解除、スレッド一覧の動作を改善しました。60/90/120Hz 端末で並べ替え時の自動スクロール速度を一定に保ち、メッセージ押下リップルがシステムアニメーション倍率の変更で途中終了しないようにしました。また、メニュー外タップで 150ms 待ちなく即座に閉じつつ長押し解除による誤閉鎖を防ぎ、可変リフレッシュレート端末でのスクロール・画面遷移時の誤ったフレーム落ち判定を解消しました。
