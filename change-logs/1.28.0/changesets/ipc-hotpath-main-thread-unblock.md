---
type: fixed
area: performance
platforms: [android, ios, desktop]
pull_request: 0
rollout: full
---

## zh-CN

优化主线程高频阻塞路径，减少页面滚动、前后台切换、下载进度更新、桌面小组件刷新与通知撤回时的掉帧与卡顿：头像渲染等高频路径改用内存缓存的当前环境地址，不再在组合阶段同步读取本地存储；安卓端将网络状态监听与查询、前台通知撤回与回退通知投递、下载进度通知与缓存、后台保活服务检查及最近任务卡片配色更新均移出主线程或改为延后合并执行，并消除无 Play 服务保活分支的无效服务停止调用与无用系统导航模式查询；调试包新增仅打日志的主线程磁盘与慢调用检测以防回归。

## en

Eliminate hot-path main-thread blocks across scrolling, foreground/background transitions, download progress updates, home-screen widget refreshes, and foreground notification retractions: cache the active API environment in memory so hot UI paths no longer read local storage during composition; on Android, remove dead navigation-mode system queries, move connectivity callback registration/queries, foreground notification retraction and fallback posting, download progress notification updates (with channel and launch-intent caching), and keep-alive start checks off the main thread, short-circuit redundant background service stop calls when already stopped, keep widget updates off the main thread, and defer/coalesce recents task color updates; add a log-only debug StrictMode guardrail to catch future regressions.

## ja

スクロール、フォアグラウンド／バックグラウンド切替、ダウンロード進捗更新、ホーム画面ウィジェット更新、フォアグラウンドでの通知取り消し時におけるメインスレッドのブロックを解消しました。現在の API 環境をメモリキャッシュして描画ホットパスでの同期ストレージ読み取りを排除し、Android では不要なナビゲーションモード参照を削除、ネットワーク状態監視・問い合わせ、通知取り消しとフォールバック投稿、ダウンロード進捗通知（チャンネルと起動インテントのキャッシュ付き）、バックグラウンド維持チェックをメインスレッド外へ移行しました。停止済みサービスの不要な停止呼び出しを省き、ウィジェット更新をバックグラウンドスレッドのまま完了させ、最近使ったアプリカードの背景色反映を遅延・統合するとともに、デバッグビルドにログ出力のみの StrictMode 監視を追加しました。
