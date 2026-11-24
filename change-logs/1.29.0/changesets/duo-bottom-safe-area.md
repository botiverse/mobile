---
type: fixed
area: layout
platforms:
  - ios
pull_request: TBD
rollout: full
---

## zh-CN

修复 iPhone Duo 展开横屏时，会话底部 Join channel 按钮没有保留底部安全区的问题。侧栏操作区使用导航控制器的顶部/侧边 inset 时，页面自身的底部 inset 不再被覆盖；底部按钮和输入区现在会避开系统导航条。

## en

Fixed the missing bottom safe area behind the Join channel button on an unfolded iPhone Duo in landscape. The page's bottom inset is now preserved when the side-action layout adopts the navigation controller's top and side insets, so bottom actions and the composer stay clear of the system navigation bar.

## ja

iPhone Duo の展開横向きで、Join channel ボタンの下部セーフエリアが失われる問題を修正しました。サイドアクション表示でナビゲーションコントローラーの上下左右のインセットを採用しても、ページ側の下部インセットを上書きしなくなり、下部のボタンと入力欄がシステムナビゲーションバーと重ならなくなりました。
