---
type: fixed
area: app
platforms:
  - android
pull_request: 4573
rollout: full
---

## zh-CN

修复开启“多个应用窗口”后切换服务器可能白屏的问题：切换到一个已经打开过窗口的服务器时，那个窗口不会再一直白屏、要把应用切到后台再回来才显示。新窗口在载入时也会先显示应用的框架（标题栏、底部标签栏和列表占位），不再是一整片空白。

## en

Fixed a white screen when switching servers with Multiple app windows on: switching to a server whose window was already open no longer leaves that window white until the app goes to the background and back. While a new window loads it now shows the app's frame (the header, the tab bar and placeholder rows) instead of a blank page.

## ja

「複数のアプリウィンドウ」をオンにしてサーバーを切り替えたときに画面が白くなる問題を修正しました。すでにウィンドウを開いていたサーバーに切り替えても、アプリを一度バックグラウンドにして戻すまでそのウィンドウが白いままになることはなくなりました。新しいウィンドウの読み込み中も、真っ白な画面ではなくアプリの枠（ヘッダー、タブバー、リストのプレースホルダー）が表示されます。
