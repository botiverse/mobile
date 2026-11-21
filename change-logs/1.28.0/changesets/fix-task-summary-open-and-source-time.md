---
type: fixed
area: tasks
platforms:
  - android
  - ios
  - desktop
pull_request: 5294
rollout: full
---

## zh-CN

修复从任务列表打开已有讨论的任务时，偶发卡在「这个任务还没有讨论」空白页的问题；同时修复任务详情页中来源消息和历史记录时间显示为原始 UTC 时间戳的问题，统一按本地时间正常显示。

## en

Fixed opening a task with existing replies from the Tasks list occasionally getting stuck on the empty "No discussion in this task yet" screen, and fixed source message and history timestamps in task details displaying as raw UTC ISO strings instead of local time.

## ja

タスク一覧からすでに議論のあるタスクを開いた際に「このタスクにはまだ議論がありません」の空画面のまま進まなくなる不具合を修正しました。あわせてタスク詳細の引用元メッセージや履歴の時刻が生の UTC 文字列で表示される問題を直し、ローカル時刻で表示されるようにしました。
