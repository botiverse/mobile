---
type: fixed
area: android
platforms:
  - android
pull_request: 5280
rollout: full
---

## zh-CN

修复 Android 上从最近任务恢复已回收主任务及开启「多窗口」时的窗口恢复边界问题：所有用户在最近任务重开已被系统回收的主任务时，不再误重放当初的分享、登录回调或消息链接；开启「多窗口」后，系统复用旧任务 ID 时不再把主窗口误认作文档窗口，从桌面快捷方式打开指定工作区时也不再短暂闪回主窗口原本的工作区。

## en

Fixed Android task revival and multi-window restoration edge cases: for all users, reopening a reclaimed launcher task from Recents no longer replays its original one-shot share, sign-in callback, or message link; when multiple app windows are enabled, a recycled task ID is no longer matched as a persisted document window, and opening a workspace from a desktop shortcut no longer flashes the launcher window's previous workspace.

## ja

Android における回収済みタスクの復帰および「マルチウィンドウ」有効時のウィンドウ復元境界ケースを修正しました。すべてのユーザーについて、最近使ったアプリから回収済みメインタスクを開き直した際に以前の共有・ログインコールバック・メッセージリンクを再実行しないようにし、マルチウィンドウ有効時には再利用されたタスク ID をドキュメントウィンドウとして誤認識しないようにし、デスクトップショートカットから指定のワークスペースを開く際に元のワークスペースが一瞬表示されないようにしました。
