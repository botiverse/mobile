---
type: changed
area: settings
platforms:
  - android
rollout: full
---

## zh-CN

将 Android 直装版「设置 › 关于 › 抢先体验」更新通道切换至独立的 `beta` 通道（含增量更新支持），与工程测试用的 `alpha` 通道分离；此前在 1.31.0 中已开启抢先体验的设备升级后会自动迁移到 `beta` 通道，无需重新安装。

## en

Moved direct-install Android Early access (Settings › About › Early access) from `alpha` to the dedicated `beta` channel with in-app delta update support, keeping `alpha` isolated for engineering testing. Devices that enabled Early access on 1.31.0 migrate automatically to `beta` on upgrade without reinstalling.

## ja

Android 直インストール版の「設定 › このアプリについて › 早期アクセス」の更新チャネルを専用の `beta` チャネル（差分アップデート対応）へ移行し、開発テスト用の `alpha` チャネルと分離しました。1.31.0 で早期アクセスを有効にしていた端末は、アップグレード後に再インストールなしで自動的に `beta` チャネルへ移行されます。
