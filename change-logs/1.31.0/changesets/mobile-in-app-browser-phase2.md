---
type: changed
area: browser
platforms: [android, ios]
rollout: full
---

## zh-CN
移动端内置浏览器升级为右滑全屏入栈体验：普通外部网页链接直接通过系统内置浏览器（Android Chrome Custom Tabs / iOS SFSafariViewController）打开，已安装的 Raft App 通过右向左全屏入栈打开，App 内跨域外链则推入仅显示域名的隔离未登录子页。同时新增右侧边缘磁吸多页悬浮胶囊，支持最多保留 5 个已打开的 App 页面（最近 2 个常驻保活、其余休眠待恢复）并可一键切换或关闭。

## en
Upgraded the mobile in-app browser to a right-to-left full-screen stack push: ordinary external web links now open directly in the system in-app browser (Android Chrome Custom Tabs / iOS SFSafariViewController), while installed Raft Apps push full-screen in-app and cross-origin outbound links inside an App push an isolated, unauthenticated domain-only child page. Added a draggable right-edge magnetic floating tab pill supporting up to 5 open App pages (2 live WebViews in LRU, older pages hibernated) with quick resume and a multi-page switcher sheet.

## ja
モバイルのアプリ内ブラウザを右から左へのフルスクリーンスタック遷移に刷新しました。通常の外部 Web リンクはシステムのアプリ内ブラウザ（Android Chrome Custom Tabs / iOS SFSafariViewController）で直接開き、インストール済みの Raft App はフルスクリーンで開かれ、アプリ内のクロスオリジン外部リンクはドメインのみを表示する未ログイン分離子ページとして開きます。最大 5 ページ（直近 2 ページをライブ保持、残りは休止）を管理できる右端マグネット式フローティングピルとページ切替シートも追加しました。
