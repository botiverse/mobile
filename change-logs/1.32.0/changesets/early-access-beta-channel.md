---
type: changed
area: settings
platforms:
  - android
rollout: full
---

## zh-CN

将 Android 直装版「设置 › 关于 › 抢先体验」更新通道改为由服务端功能开关动态下发（默认指向独立的 `beta` 通道并支持增量更新），与工程测试用的 `alpha` 通道分离；此前在 1.31.0 中已开启抢先体验的设备升级后会自动迁移到服务端配置的目标通道，无需重新安装。

## en

Moved direct-install Android Early access (Settings › About › Early access) to follow the server feature-flag target channel (defaulting to the dedicated `beta` channel with in-app delta updates), keeping `alpha` isolated for engineering testing and allowing future channel shifts without a client release. Devices that enabled Early access on 1.31.0 migrate automatically on upgrade without reinstalling.

## ja

Android 直インストール版の「設定 › このアプリについて › 早期アクセス」の更新チャネルをサーバー側のフィーチャーフラグで動的に制御する方式に変更し（既定は専用の `beta` チャネル、差分アップデート対応）、開発テスト用の `alpha` チャネルと分離しました。1.31.0 で早期アクセスを有効にしていた端末は、アップグレード後に再インストールなしで自動的に対象チャネルへ移行されます。
