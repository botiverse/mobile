---
type: fixed
area: browser
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
修复了在应用内浏览器（如 Stamp 审批页或设置页）中点击 POST 表单授权按钮、以及服务端 302 跳转至第三方登录页期间顶部加载条不显示导致页面看似无响应的问题；同时确保通过脚本或直链发起的 GitHub 授权与 GitHub App 安装流程保留在应用内当前标签页完成，并补充脱敏的应用内浏览器主帧导航诊断日志。

## en
Fixed an issue where submitting a main-frame POST form or following a server redirect inside the in-app browser (such as GitHub authorization buttons on Stamp) left the top loading bar hidden during the round trip, making the button appear unresponsive. Also ensured non-redirect third-party OAuth and GitHub App installation navigations stay inside the Raft App's in-app tab instead of routing out to an isolated child page or system browser, and added sanitized main-frame navigation breadcrumbs for in-app browser diagnostics.

## ja
アプリ内ブラウザ（Stamp の承認・設定ページなど）で POST フォーム送信やサーバーからの 302 リダイレクト中に上部の読み込みバーが表示されず、ボタンが反応しないように見える不具合を修正しました。あわせて、リダイレクトを伴わないサードパーティ OAuth や GitHub App インストール遷移をアプリ内タブのまま完了できるようにし、クエリを含まない診断用ナビゲーションログを追加しました。
