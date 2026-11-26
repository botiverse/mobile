---
type: added
area: activity
platforms: [android, ios, desktop]
rollout: flag
---

## zh-CN
在 `task_waiting_on_you_v0` 功能开关开启的服务器中，动态列表里的任务对话行会解析并显示「等你处理」等待状态与悬浮/长按说明（含派发人、相对时间与原因）；当实时任务更新结束等待或切换负责人时就地恢复为任务状态，并在新等待落到当前用户时自动重读该任务行。

## en
On servers with `task_waiting_on_you_v0` enabled, task conversation rows in Activity now parse and display the "Waiting on you" status with a hover/long-press tooltip (including assigner, relative time, and reason); the indicator clears in place when a live task update resolves or reassigns the task, and re-reads the listed task row when a new hold lands on you.

## ja
`task_waiting_on_you_v0` が有効なサーバーでは、アクティビティのタスク会話行に「あなたの対応待ち」ステータスとホバー／長押しツールチップ（割り当て元・相対時刻・理由）を表示します。タスクのリアルタイム更新で対応待ちが終わった場合はその場で通常のステータス表示に戻り、新しい対応待ちが発生した場合は該当行を再取得します。
