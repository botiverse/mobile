---
type: improved
area: notifications
platforms:
  - android
rollout: flag
---

## zh-CN

在灰度开关 `mobile_fcm_probe_v0` 下，Android 端新增静默 FCM 可达性探测与双引擎通知回退：在前台通过服务端下发静默探测消息，连续两次在同一网络确认无法送达时，直装安装包会自动启用后台常驻连接并保持本地消息通知，网络切换或收到推送恢复可达后自动切回省电模式；Google Play 版本不启用后台常驻服务，仅在「设置 › 通知」中展示可关闭的网络提示。

## en

Behind the `mobile_fcm_probe_v0` feature flag, Android now runs an end-to-end silent FCM reachability probe with dual-engine notification fallback: after two consecutive foreground probe misses on the same network while the Raft API stays healthy, direct-install APKs automatically enable the persistent background connection and local notifications, then switch back cleanly once Google push is confirmed reachable again; Google Play builds never start the foreground service and surface a dismissible notice in Settings › Notifications instead.

## ja

フィーチャーフラグ `mobile_fcm_probe_v0` のもとで、Android にサイレント FCM 到達性プローブとデュアルエンジン通知フォールバックを追加しました。フォアグラウンドで同一ネットワーク上のプローブが2回連続して未達となった場合、直接インストール版 APK ではバックグラウンド常駐接続とローカル通知を自動で有効化し、Google プッシュ通知の到達が確認されると自動で省電力モードへ戻ります。Google Play 版ではフォアグラウンドサービスを起動せず、「設定 › 通知」に閉じられる案内バナーのみを表示します。
