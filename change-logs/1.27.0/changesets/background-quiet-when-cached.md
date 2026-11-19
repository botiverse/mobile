---
type: fixed
area: diagnostics
platforms:
  - android
  - ios
pull_request: 5081
rollout: full
---

## zh-CN

（内部稳定性）应用在后台时更安静：Android 上，没有后台连接时不再接收系统的网络变化回调，回到前台时立即重新读取网络状态；两个平台上，功能开关刷新、频道公告轮询、通知刷新和每秒更新的计时在后台都会暂停，回到前台时如有到期的刷新只补做一次。这样 Android 17 不会再因为应用在后台缓存期间收到过多系统调用而将其结束。诊断日志会每分钟汇总一次后台收到的网络回调次数。

## en

(Internal stability) The app is quieter in the background. On Android it no longer receives the system's network change callbacks while hidden without a background connection, and reads the network status again as soon as it comes back. On both platforms, feature flag refreshes, channel announcement polling, notification refreshes and once-a-second timers pause in the background; back in front, a refresh that came due runs once. Android 17 should no longer end the cached app for excessive system calls. The diagnostics log now summarizes, once a minute, the network callbacks received while hidden.

## ja

（内部の安定性）バックグラウンドでのアプリの動作を控えめにしました。Android では、バックグラウンド接続がないときは非表示中にシステムのネットワーク変更コールバックを受け取らず、前面に戻るとすぐにネットワークの状態を読み直します。両プラットフォームで、機能フラグの更新、チャンネルのお知らせのポーリング、通知の更新、1 秒ごとのタイマーはバックグラウンドで止まり、前面に戻ったときに期限が来ていた更新を 1 回だけ実行します。Android 17 がキャッシュ中のアプリをシステム呼び出しの過多で終了させることはなくなるはずです。診断ログには、非表示中に受け取ったネットワークコールバックの回数が 1 分ごとにまとめて記録されます。
