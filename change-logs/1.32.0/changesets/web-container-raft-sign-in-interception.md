---
type: fixed
area: browser
platforms: [android, ios]
rollout: full
---

## zh-CN
修复了新网页容器（`mobile_web_container_v1`）下应用内 Raft 快捷登录在首跳重定向和点击「Sign in with Raft」两条路径均失败的问题：为初始加载链记录首跳 URL 并在 Android `onPageStarted` 保留导航链上下文，同时补齐主框架被宿主拦截后的取消态抑制（覆盖 Android `doUpdateVisitedHistory`/回调与 iOS KVO/provisional 状态同步），避免已被原生快捷登录接管的授权链接再次拉起系统浏览器导致授权中断或白屏。

## en
Fixed in-app Raft Quick Sign-In failing under the new web container (`mobile_web_container_v1`) on both initial-launch redirects and button-triggered OAuth handoffs: initial loads now record the launch URL as the first navigation hop, Android `onPageStarted` preserves the navigation chain context, and host-intercepted authorize navigations suppress trailing `doUpdateVisitedHistory`, `onPageStarted`, commit/finish/error, and iOS KVO/provisional callbacks so the system browser is no longer launched on top of the native sign-in sheet.

## ja
新しい Web コンテナ（`mobile_web_container_v1`）でアプリ内 Raft クイックサインインが初回リダイレクト時とボタン押下時の両方で失敗する不具合を修正しました。初回ロード時の起動 URL を最初のホップとして記録し Android の `onPageStarted` でナビゲーションチェーンを引き継ぐとともに、ホストが捕捉した認可 URL の後続コールバック（Android の `doUpdateVisitedHistory`/コミット/エラーおよび iOS の KVO/provisional 同期）を抑制し、システムブラウザの誤起動や白画面を防止します。
