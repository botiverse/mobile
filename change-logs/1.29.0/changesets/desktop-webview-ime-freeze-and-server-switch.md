---
type: fixed
area: desktop
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复了 macOS 桌面版在打开内嵌网页（Artifact、设置内嵌页、应用页等）时使用中文输入法或拖拽缩放窗口会卡死的问题：所有原生 WebView 阻塞调用与点击聚焦均已移出 AWT 主线程，不再与 macOS 主线程互相等待。同时修复了打开内嵌网页时切换服务器不会重新加载新服务器页面的问题，切换服务器后网页会立即以新服务器的上下文和地址重新加载。

## en

Fixed a freeze on the macOS desktop app when typing with a Chinese IME or resizing the window while an embedded web page (Artifact, Settings web page, or Apps page) is open: all blocking native WebView calls and focus clicks now run off the AWT event thread so it never deadlocks waiting on the macOS main thread. Also fixed switching servers while an embedded web page is open so the page reliably reloads for the newly selected server.

## ja

macOS デスクトップ版で埋め込み Web ページ（Artifact、設定の Web ページ、アプリページなど）を開いたまま中国語 IME で入力したりウィンドウをリサイズしたりするとフリーズする問題を修正しました。ネイティブ WebView のブロッキング呼び出しとフォーカス処理をすべて AWT イベントスレッド外に移し、macOS メインスレッドとのデッドロックを防いでいます。また、埋め込み Web ページを開いた状態でサーバーを切り替えてもページが再読み込みされない問題も修正しました。
