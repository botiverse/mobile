---
type: fixed
area: android
platforms:
  - android
pull_request: 5280
rollout: full
---

## zh-CN

修复开启「多窗口」后在 Android 上的三处窗口恢复边界问题：系统复用旧任务 ID 时不再把主窗口误认作文档窗口；唤醒已回收主窗口时不再重放旧的分享、登录回调或消息链接；从桌面快捷方式打开指定工作区时不再短暂闪回主窗口原本的工作区。

## en

Fixed three Android multi-window restoration edge cases: a recycled launcher task ID is no longer matched as a persisted document window, reviving a reclaimed launcher task no longer replays its original one-shot share, sign-in callback, or message link, and opening a workspace from a desktop shortcut no longer flashes the launcher window's previous workspace.

## ja

Android の「マルチウィンドウ」有効時におけるウィンドウ復元の境界ケースを 3 件修正しました。再利用されたタスク ID をドキュメントウィンドウとして誤認識しなくなり、回収されたメインタスクを復帰する際に以前の共有・ログインコールバック・メッセージリンクを再実行しなくなり、デスクトップショートカットから指定のワークスペースを開く際に元のワークスペースが一瞬表示されなくなりました。
