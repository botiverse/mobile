---
type: changed
area: apps
platforms:
  - android
  - ios
pull_request: 5536
rollout: full
---

## zh-CN

消息里的第三方链接和 Raft App 现统一在从底部升起的网页面板中打开：默认 88% 高度并在顶部保留变淡显示的原消息行（可上拖全屏或下拉/点击原消息行回到对话并保持页面存活，原消息下显示“页面仍打开”标记），底栏精简为关闭、来源与更多菜单（Raft App 显示图标、名称与已登录身份且不提供“在浏览器打开”），且第三方页面与 Raft App 使用相互隔离的 WebView 存储分区（旧版 Android 不支持多 Profile 时自动回退到系统浏览器）。

## en

Third-party links and Raft Apps now open in a unified bottom-rising mobile web panel: 88% default height with a dimmed 1-line source strip at the top (drag up to fullscreen, drag down or tap the strip to return while keeping the page alive with a "Page still open" badge), a minimal bottom bar (close, host or App identity, overflow menu without "Open in browser" for Raft Apps), and isolated WebView storage partitions for third-party pages (failing closed to the system browser on older Android devices without multi-profile support).

## ja

メッセージ内のサードパーティリンクおよび Raft アプリが、画面下から立ち上がる統一 Web パネルで開くようになりました。デフォルト 88% の高さで上部に元のメッセージを薄く表示し（上スワイプで全画面、下スワイプまたは上部タップでページを保持したまま会話に戻り「ページを開いたまま」バッジを表示）、下バーは閉じる・ドメイン／アプリ名・その他メニューのみに整理されています。サードパーティページと Raft アプリは独立した WebView ストレージを使用します（マルチプロファイル非対応の古い Android ではシステムブラウザーで開きます）。
