---
type: fixed
area: app
platforms:
  - android
pull_request: TBD
rollout: full
---

## zh-CN

修复开启“多个应用窗口”后切回一个已在后台打开过窗口的服务器时，会先白屏只显示 Raft 图标约两秒的问题：切换服务器打开或复用的窗口不再显示启动画面，直接显示应用的框架和内容。

## en

Fixed a white screen with only the Raft icon for about two seconds when switching back to a server whose window was already open in the background with Multiple app windows on: a window opened or reused by a server switch no longer shows the launch screen and goes straight to the app's frame and content.

## ja

「複数のアプリウィンドウ」をオンにして、バックグラウンドでウィンドウを開いていたサーバーに戻ると、Raft アイコンだけの白い画面が約 2 秒表示される問題を修正しました。サーバー切り替えで開く・再利用するウィンドウは起動画面を表示せず、すぐにアプリの枠と内容を表示します。
