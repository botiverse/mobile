---
type: improved
area: settings
platforms:
  - android
pull_request: 5492
rollout: full
---

## zh-CN
统一 Android 后台保活设置入口：首页的「Raft 在后台被系统停止」提示横幅改为直接跳转至应用内「设置 → 通知」并定位到后台运行设置；通知设置内统一整合了「允许后台运行」与厂商自启动/后台管理入口，厂商意图不可用时优先回退至系统电池优化列表而非直接跳转应用信息页。

## en
Unify Android background keep-alive settings into a single entry point: tapping the Home "Raft was stopped in the background" banner now opens Raft's in-app Settings › Notifications and highlights the background keep-alive controls, which combine direct battery-optimization exemption (on direct/non-Play builds) and OEM auto-start/background settings with a unified fallback through the system battery optimization list before App Info.

## ja
Android のバックグラウンド常駐設定の導線を「設定 › 通知」に統一しました。ホームの「Raft がバックグラウンドで停止されました」バナーをタップするとアプリ内の通知設定へ直接移動し、バッテリー最適化の除外設定と端末メーカーの自動起動・バックグラウンド管理設定を統一されたフォールバック順（OEM 設定 → システムのバッテリー最適化一覧 → アプリ情報）で開けるようになりました。
