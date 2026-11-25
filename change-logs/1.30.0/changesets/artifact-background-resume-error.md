---
type: fixed
area: artifacts
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复从后台切回正在查看的 Artifact 时直接提示「无法加载此页面 / 请检查网络连接后重试。」的问题：Android 端在应用处于后台时发生 WebView 渲染进程被系统回收（`onRenderProcessGone`）时不再立刻在后台重建并加载页面，而是延后到切回前台时自动恢复（同时对已停放的保活 WebView 直接释放而非后台重载）；WebView 加载超时看门狗同步感知前台生命周期，后台停留期间暂停 15 秒超时计时与内容探测，避免后台耗时误判为网络异常。

## en

Fixed Artifact viewer immediately showing a bogus "Could not load this page / Check your connection and try again." error when returning from the background. On Android, `onRenderProcessGone` while backgrounded now destroys the dead WebView and defers recovery/reload until the window returns to the foreground (and drops parked keep-alive WebViews cleanly instead of reloading them in the background). The WebView load watchdog also observes lifecycle state and pauses its 15-second stall countdown and content probing while backgrounded so background dwell never trips a false network error.

## ja

バックグラウンドから Artifact 表示へ戻った際に「ページを読み込めませんでした / 接続を確認して、もう一度お試しください。」と誤表示される不具合を修正しました。Android でバックグラウンド中に WebView のレンダリングプロセスが回収された場合（`onRenderProcessGone`）、バックグラウンドで即座に再読み込みせずフォアグラウンド復帰時に自動復元し、待機中のキープアライブ WebView は再読み込みせず破棄します。あわせて WebView 読み込みタイムアウト監視をライフサイクル連動とし、バックグラウンド滞在中の一時停止により 15 秒タイムアウトが誤発火しないようにしました。
