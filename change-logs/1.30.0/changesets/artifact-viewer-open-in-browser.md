---
type: improved
area: artifacts
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

在 Artifact 查看器恢复「在浏览器中打开」入口（同时在嵌入工具栏菜单暴露 `hostMenu=open-in-browser`，并在原生兜底顶栏右上角提供外部链接按钮）。iOS 端点击时直接唤起系统外部浏览器（不再使用覆盖全屏且不共享登录态的 `SFSafariViewController`）；当从会话中全屏打开 Artifact 并切换至浏览器时，会自动关闭查看器，切回 Raft 时直接回到原会话。

## en

Restored "Open in browser" in the Artifact viewer (exposing `hostMenu=open-in-browser` to the embedded toolbar and adding a top-right external-link button in the fallback native header). On iOS, this hands off directly to the external system browser instead of an in-app `SFSafariViewController` so Safari sessions are shared and Raft stays unobstructed. When opened as a standalone page from a conversation, the viewer automatically dismisses on browser handoff so switching back to Raft lands directly in the conversation.

## ja

Artifact ビューアーに「ブラウザで開く」アクションを復元しました（埋め込みツールバーへ `hostMenu=open-in-browser` を公開し、フォールバックのネイティブヘッダー右上にも外部リンクボタンを配置）。iOS ではアプリ内の `SFSafariViewController` ではなくシステムの外部ブラウザへ直接引き渡し、会話から単体ページとして開いた場合はブラウザ移行時にビューアーを自動で閉じるため、Raft に戻るとすぐ元の会話に戻れます。
