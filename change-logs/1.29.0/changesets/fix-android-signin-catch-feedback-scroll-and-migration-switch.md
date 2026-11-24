---
type: fixed
area: browser
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

- 修复华为等 Android WebView 在原生「用 Raft 登录」拦截授权跳转后仍补发 `onPageStarted`/`onReceivedError` 回调，可能误拉起外部浏览器或报错的问题；
- 反馈列表点进详情再返回时保留原来的滚动位置（仅切换筛选标签时回到顶部）；
- 迁移记录页在手机窄屏下将「图表 / 表格」切换固定在工具栏右侧，不再跟着筛选条横向滚出视野。

## en

- Fixed Android WebViews (such as Huawei WebView 114) firing spurious `onPageStarted`/`onReceivedError` callbacks after native "Log in with Raft" intercepts an OAuth redirect, preventing unintended external-browser handoffs or load errors.
- Preserved scroll position in the Feedback tickets list when opening a ticket detail and navigating back (resetting only when switching filter tabs).
- Pinned the Migration Log chart/table view switch to the trailing edge of the toolbar on narrow phone screens so filter chips scroll independently beneath it.

## ja

- ネイティブの「Raft でログイン」が認証リダイレクトを捕捉した後に一部 Android WebView で発生する後続の `onPageStarted`/`onReceivedError` 通知を抑止し、外部ブラウザへの誤遷移やエラー表示を防止しました。
- フィードバック一覧で詳細を開いて戻ったときに元のスクロール位置を保持するようにしました。
- 狭いスマホ画面の移行履歴ツールバーで、フィルター項目だけを横スクロールさせ、右側の表示切替（チャート／表）を固定表示するようにしました。
