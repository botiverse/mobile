---
type: fixed
area: ios
platforms:
  - ios
pull_request: 2212
rollout: full
---

## zh-CN

修复 iOS 从后台或设置页返回后 Kuikly 内容偶发白屏的问题：页面重新挂载到已显示的 pager 时，正确同步页面可见状态，避免内容层被错误保持为隐藏。

## en

Fixed an iOS white-screen issue after returning from the background or Settings. When a Kuikly child is attached to an already-visible pager, its visible state is now synchronized so the content is not left hidden.

## ja

iOS でバックグラウンドや設定画面から戻った際に Kuikly の内容が白くなる問題を修正しました。表示中の pager に子ページを再接続するとき、表示状態を正しく同期して内容が非表示のままにならないようにしました。
