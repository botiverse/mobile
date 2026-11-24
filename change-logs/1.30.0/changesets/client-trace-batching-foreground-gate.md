---
type: improved
area: diagnostics
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

优化客户端链路追踪上报批处理与前后台门控：将批量合并等待窗口拉长至 15 秒并在单个服务队列满 100 条时立即触发上报，同时接入进程前后台状态门控——应用退后台时取消待触发的定时与重试上报、暂存事件不唤醒网络，回到前台后再恢复批量上报。

## en

Improved client trace upload batching and background power efficiency by extending the flush coalescing window to 15 seconds with immediate early flushing when a server batch reaches 100 records, and gating uploads on process foreground visibility so pending flush and retry timers pause in the background without waking the network and resume cleanly upon returning to the foreground.

## ja

クライアントトレース送信のバッチ処理とバックグラウンド制御を改善しました。バッチ待機ウィンドウを 15 秒へ延長しつつ単一サーバーのキューが 100 件に達した際は即時に送信し、プロセスのフォアグラウンド状態に連動させてバックグラウンド移行時は待機中の送信・リトライタイマーを停止してネットワーク起動を抑止、フォアグラウンド復帰時にバッチ送信を再開します。
