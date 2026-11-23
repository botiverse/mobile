---
type: fixed
area: attachments
platforms:
  - android
pull_request: 5547
rollout: full
---

## zh-CN

修复 Android 点击下载进度通知无反应的问题：点按进行中、失败或无可用外部打开方式的文件下载通知会直接进入应用内下载中心（设置 › 存储），已完成且有可用打开方式的下载通知继续直接打开对应文件；对 APK 下载通知在未开启安装权限或 Play 构建下自动回落到下载中心引导处理。

## en

Fixed Android download progress notification taps doing nothing: tapping an active, failed, or unopenable download notification now opens Raft's in-app Download Center (Settings › Storage) without resetting your current screen, while completed download notifications continue opening the downloaded file directly (with APK installs falling back to the Download Center when install permission is unavailable).

## ja

Android でダウンロード進行中の通知をタップしても反応しない問題を修正しました。進行中・失敗・外部アプリで直接開けないファイルの通知をタップするとアプリ内のダウンロードセンター（設定 › ストレージ）が開き、完了したファイルの通知は引き続き対応するアプリで直接開きます（インストール権限のない APK はダウンロードセンターへ安全にフォールバックします）。
