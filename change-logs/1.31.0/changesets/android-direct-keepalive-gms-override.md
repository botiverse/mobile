---
type: added
area: notifications
platforms:
  - android
rollout: full
---

## zh-CN

直装 Android 安装包在「设置 › 通知」中新增「后台常驻连接」开关（默认关闭），帮助在保留 Google 服务但无法连接 Google 推送的网络环境下保持后台连接以接收消息通知；Google Play 版本不显示也不启用此选项。

## en

Added an opt-in "Persistent background connection" toggle (off by default) in Settings › Notifications on direct-install Android APKs so devices with Google Play services on networks where Google push is unreachable can keep a background connection for message notifications; Google Play builds never expose or apply this override.

## ja

直接インストール版 Android APK の「設定 › 通知」に「バックグラウンド常駐接続」トグル（デフォルトはオフ）を追加しました。Google Play 開発者サービスを有効にしたまま Google プッシュ通知に届かないネットワーク環境でも、バックグラウンド接続を維持してメッセージ通知を受け取れます。Google Play 版では表示も適用もされません。
