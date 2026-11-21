---
type: fixed
area: composer
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

修复宽屏和大屏键盘操作下，用方向键或 Tab 停在顶栏、侧栏或辅助栏按钮上时，自动打开会话或关闭侧边讨论串会抢走键盘焦点的问题；等待聚焦期间如果用户已点击或切到其他控件，待执行的自动聚焦会自动取消。

## en

Fixed keyboard focus being stolen by automatic conversation focus or closing a side thread on large screens when navigating controls in the top bar, sidebar, or supporting panes; pending automatic focus now cancels if you tap, type, or move focus elsewhere while it settles.

## ja

大画面でのキーボード操作中にトップバーやサイドバー、補助パネルのコントロールへフォーカスしている際、会話の自動オープンやサイドスレッドを閉じたタイミングでフォーカスが入力欄へ奪われる問題を修正しました。待機中に別の操作やフォーカス移動が行われた場合、保留中の自動フォーカスはキャンセルされます。
