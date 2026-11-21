---
pull_request: 5289
type: improved
area: ui
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

在 Android 和桌面端打开应用内暂无原生页面的 Raft 网页（如消息与 Artifact 中的网页链接、动作卡片「在网页中打开」、Artifact 回退页以及升级引导页）时，现在直接在带登录态的应用内页面加载，无需重新登录，并可在顶栏一键转到外部浏览器打开；iOS 上 Artifact 回退页同步支持登录态打开，同时退出登录会清理持久化 WebView 登录态。

## en

Opening Raft web pages without a native screen on Android and desktop—including Raft web links in messages and Artifacts, action card web links, Artifact fallbacks, and upgrade pages—now loads inside an authenticated in-app view without asking you to sign in again, with a header button to open in your default browser whenever needed; on iOS, Artifact fallback pages open authenticated in-app, and signing out clears persistent WebView auth storage.

## ja

Android およびデスクトップでネイティブ画面のない Raft ウェブページ（メッセージや Artifact 内のリンク、アクションカードのウェブリンク、Artifact のフォールバックページ、アップグレード案内）を開く際、再ログイン不要の認証済みアプリ内画面で表示されるようになり、ヘッダーからいつでも既定のブラウザで開けます。iOS でも Artifact のフォールバックページが認証済みで開かれ、サインアウト時に永続的な WebView 認証ストレージをクリアします。
