---
type: fixed
area: thread
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复弱网或后台刷新期间打开讨论串、频道、私信或任务会话时，消息已经显示但底部输入框缺失或长时间空白的问题：输入框现在会在首帧立即显示并可直接输入，不再被未完成的列表刷新或权限确认阻塞；若权限确认结果为无权限、未加入、已归档或冻结，会自动切换为对应提示并保留已输入的草稿。

## en

Fixed the composer appearing missing or blank for a long time when opening a reply thread, channel, direct message, or task conversation on slow networks or while Home is refreshing: the composer now renders immediately on the first frame so you can start typing right away without waiting for background list refreshes or permission confirmation. If access later resolves as unjoined, archived, frozen, or without permission, the footer switches cleanly while preserving your draft.

## ja

低速回線やバックグラウンド更新中にスレッド、チャンネル、ダイレクトメッセージ、タスク会話を開いた際、メッセージが表示されているのに下部の入力欄が表示されない・空白のままになる不具合を修正しました。入力欄は最初のフレームから即座に表示され、すぐに入力を始められます。あとから未参加・アーカイブ・凍結・権限なしと確定した場合は、入力中の下書きを保持したまま適切な案内へ切り替わります。
