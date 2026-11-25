---
type: fixed
area: reliability
platforms:
  - android
rollout: full
---

## zh-CN

修复了两项 Android 端可靠性问题：在切换服务器期间锁屏或切到后台导致请求被生命周期回收时，解锁返回后不再误报「无法切换服务器，请重试。」提示并会正常载入目标服务器；打开前台网页（如「设置 > 应用」）时，若后台 Artifact 预热仍在加载中会自动让路中止并限制最长预热时长，避免阻塞前台网页加载超时。

## en

Fixed two Android reliability issues: locking the screen or backgrounding mid-server-switch no longer triggers a false "Could not switch servers. Please try again." toast upon unlock and recovers cleanly onto the target server; opening a foreground web page (such as Settings > Apps) now aborts any still-loading background Artifact WebView prewarm (which also has a safety load timeout) so it cannot starve foreground pages.

## ja

Android の信頼性に関する 2 件の問題を修正しました。サーバー切り替え中に画面ロックやバックグラウンド移行で進行中のリクエストがライフサイクル回収された場合でも、復帰後に「サーバーを切り替えられませんでした」という誤通知を出さずに対象サーバーを正常に読み込みます。また、設定 > アプリなどのフォアグラウンド Web 画面を開く際、バックグラウンドで読み込み中の Artifact 事前ウォームを即時中断しタイムアウト上限を設けることで、フォアグラウンド画面の読み込み停滞を防止します。
