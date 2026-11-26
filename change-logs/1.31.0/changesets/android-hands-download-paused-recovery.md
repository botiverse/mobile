---
type: fixed
area: settings
platforms:
  - android
rollout: full
---

## zh-CN

修复 Android 端在 Wi-Fi 下静默预下载更新后切到移动网络时，点击「更新 / 下载」可能长期停在下载中的问题：系统会取消已暂停的仅 Wi-Fi 预下载任务并改用允许移动网络的用户下载立即继续。

## en

Fixed an issue on Android where tapping Update after a silent Wi-Fi pre-download paused off Wi-Fi could stay stuck on "Downloading...": the paused Wi-Fi-only preload is now replaced immediately with an unrestricted user download.

## ja

Android で Wi-Fi 時のバックグラウンド事前ダウンロードがモバイル回線切替で一時停止したあと、更新をタップしても「ダウンロード中」のまま進まない問題を修正しました。一時停止中の Wi-Fi 限定タスクをキャンセルし、通常のダウンロードへ即座に切り替えます。
