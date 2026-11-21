---
type: fixed
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

修复了提示条（Toast）在切换页面、键盘弹出或收起，以及底部标签栏重新出现时位置来回跳动的问题：切页和跨窗口交接时保持既有高度与剩余显示时长且不重播滑入动画，并与输入框跟随同一套键盘高度；同时补齐底部抽屉被调用方拒绝关闭时的拖拽状态清理。

## en

Fixed toasts jumping during page navigation, keyboard show/hide, and bottom tab bar return: toasts now stay clear of the new page immediately, keep their held height and remaining timeout across window handoffs without replaying the slide-in animation, and track the same live keyboard height as the composer. Also clears drawer drag-dismiss state when a caller declines dismissal.

## ja

ページ切り替え・キーボードの開閉・ボトムタブバー再表示時にトーストの位置が跳ねる問題を修正しました。遷移直後から新しいページのボトム要素を避け、ウィンドウ引き継ぎ時も保持高さと残り表示時間を維持してスライドインを再再生せず、入力欄と同じキーボード高さに追従します。あわせて、ドロワーのドラッグ終了時に呼び出し側が閉じない場合のドラッグ状態クリアも補完しました。
