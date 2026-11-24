---
type: fixed
area: ui
platforms:
  - ios
rollout: full
---

## zh-CN

优化 iOS 端 Toast 提示的展示层级：改为全窗口共用单一透明穿透覆盖层，连续返回或侧滑返回多个页面时 Toast 稳定停留在原地，不再逐页消失再重新出现，同时仍可点击操作按钮、拖动关闭且不遮挡页面其余区域的交互。

## en

Moved iOS toasts to a single transparent pass-through overlay window per scene so toasts stay steady across back navigation and swipe-back gestures instead of disappearing and reappearing on every popped page, while preserving action taps, swipe-to-dismiss, and pass-through touches everywhere else.

## ja

iOS のトースト表示をシーン単位の透過オーバーレイウィンドウへ統合し、連続して画面を戻る際やスワイプバック時にページごとにトーストが消えて再表示される現象を解消しました。ボタン操作やスワイプによる閉じる操作、トースト外のタッチ透過もそのまま維持されます。
