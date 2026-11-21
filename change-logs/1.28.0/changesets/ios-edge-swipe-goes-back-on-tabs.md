---
type: fixed
area: navigation
platforms:
  - ios
pull_request: 5204
rollout: full
---

## zh-CN

修复了 iOS 上在带标签页的页面（比如 Agent 资料里的「动态 › 原始日志」）从屏幕左边缘右滑时，切到了别的标签而没有返回上一页的问题。现在从左边缘起手的右滑无论快慢都会返回，从页面中间横滑仍然切换标签。

## en

Fixed an iOS issue where swiping right from the left edge of a page with tabs (such as an agent's Activity › Raw Log) switched tabs instead of going back. A swipe from the left edge now always goes back, fast or slow; swiping across the middle of the page still switches tabs.

## ja

iOS でタブのあるページ（エージェントのプロフィールの「アクティビティ › Raw Log」など）で画面の左端から右へスワイプすると、前のページに戻らずにタブが切り替わってしまう問題を修正しました。左端からのスワイプは速さに関係なく常に前のページに戻り、ページ中央での横スワイプでは引き続きタブを切り替えられます。
