---
type: fix
area: navigation
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复服务器数量较多时，打开首页顶部切服下拉菜单或外部分享切服面板后列表总是从顶部开始、当前所在服务器被遮挡在视野外的问题：现已在 Compose 切服面板与 iOS 26+ 原生液态玻璃切服弹窗打开时自动将当前服务器定位在可见视野内。

## en

Fixed an issue where opening the Home server switcher dropdown or external-share server picker with a long server list always started at the top and left the active server scrolled out of view: both the Compose switcher panel and the iOS 26+ native Liquid Glass switcher now position the currently selected server inside the visible viewport on open.

## ja

サーバー数が多い状態でホーム上部のサーバー切替メニューや外部共有のサーバー選択パネルを開くと常に先頭から表示され、現在開いているサーバーが画面外に隠れていた不具合を修正しました。Compose 版および iOS 26+ ネイティブ Liquid Glass 版の双方で、開いた時点で選択中のサーバーが自動的に表示領域内に収まるよう調整しています。
