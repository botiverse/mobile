---
type: fixed
area: app
platforms:
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

电脑版（macOS）在打开 Artifact 时输入中文或调整窗口大小，不会再卡住不动：窗口现在直接运行在系统自己的事件循环上。打开 Artifact 时显示加载状态，不再先闪出一个白块、再空白一段时间。打开账单、应用、管理等网页设置页时切换工作空间，页面会按新的工作空间重新加载。

## en

On desktop (macOS), the app no longer freezes while you type Chinese or resize the window with an Artifact open: the window now runs on the system's own event loop. Opening an Artifact shows a loading state instead of a white block and then a blank frame. With a web settings page (Billing, Apps, Administration and the like) open, switching servers now reloads it for the new server.

## ja

デスクトップ版（macOS）で、Artifact を開いたまま中国語を入力したりウィンドウのサイズを変えたりしても、アプリが固まらなくなりました。ウィンドウはシステム自身のイベントループで直接動くようになりました。Artifact を開くと、白い四角や空白の枠ではなく読み込み中の表示が出ます。請求、アプリ、管理などのウェブ設定ページを開いたままワークスペースを切り替えると、新しいワークスペースの内容で読み込み直します。
