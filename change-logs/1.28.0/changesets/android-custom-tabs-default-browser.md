---
type: changed
area: browser
platforms: [android]
rollout: full
---

## zh-CN

在 Android 上点击普通外部网页链接时，若系统默认浏览器支持 Custom Tabs，会直接以默认浏览器的 Custom Tabs 打开（保留浏览器登录态且点返回即可回到 Raft），不支持时自动回退到默认浏览器；「应用」入口和应用卡片仍保持在应用内 WebView 打开。

## en

On Android, ordinary web links now open in Custom Tabs using your default browser when supported (keeping your browser sessions and returning straight to Raft), falling back to the default browser otherwise; App entries and App cards still open in the in-app WebView.

## ja

Android で通常の外部 Web リンクを開く際、デフォルトブラウザが Custom Tabs に対応していればその Custom Tabs で開くようになり（ログイン状態を維持したまま Raft にすぐ戻れます）、非対応の場合はデフォルトブラウザにフォールバックします。アプリ入口とアプリカードは引き続きアプリ内 WebView で開きます。
