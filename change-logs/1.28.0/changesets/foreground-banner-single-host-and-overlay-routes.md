---
type: fixed
area: notifications
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

修复主界面内嵌会话页、分栏侧边讨论串或紧凑布局打开 Ottie 助理面板时站内通知横幅重复绘制的问题：同一窗口内由最外层横幅宿主统一渲染单条横幅，并在助理面板整个关闭动画期间持续将横幅保持在顶层。同时修复打开再关闭 Ottie 助理面板或侧边讨论串后当前正在查看的会话误弹通知的问题。

## en

Fixed duplicate in-app notification banners when a conversation is embedded inside the main shell (split-pane, side thread, or compact detail view with the Ottie assistant panel open): a single root banner host now owns each window's banner and keeps it above the drawer through the entire closing slide. Opening and closing the Ottie panel or a side thread also no longer clears the primary open conversation's notification suppression.

## ja

メイン画面に埋め込まれた会話やサイドスレッド、コンパクト表示で Ottie パネルを開いた際にアプリ内通知バナーが二重表示される問題を修正しました。ウィンドウごとに最上位ホストが単一バナーを管理し、パネルが閉じ切るまで最前面に維持します。また、Ottie パネルやサイドスレッドを開閉しても表示中の会話に対する通知抑制が解除されなくなりました。
