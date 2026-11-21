---
type: fixed
area: performance
platforms:
  - android
  - ios
  - desktop
pull_request: 5226
rollout: full
---

## zh-CN

修复了动态（Activity）页面或讨论串处于后台被其他页面遮挡时，Token 刷新仍会触发 Artifact 预览页 WebView 预热的问题：隐藏页面不再在主线程偷偷创建并加载预热 WebView，减少从后台返回后滑动讨论串时的主线程卡顿。

## en

Fixed Artifact viewer WebView prewarming running while Activity or a thread is hidden behind another page or the app is backgrounded: a token refresh no longer constructs and loads a hidden prewarm WebView on the main thread, reducing main-thread jank when scrolling after returning from background.

## ja

アクティビティ画面やスレッドが別画面の背後に隠れている間やアプリがバックグラウンドにある間に、トークン更新で Artifact ビューアーの WebView プリウォームが走ってしまう不具合を修正しました。非表示ページがメインスレッドで隠し WebView を生成・読み込みしないようにし、バックグラウンド復帰後のスクロール時の引っかかりを軽減しました。
