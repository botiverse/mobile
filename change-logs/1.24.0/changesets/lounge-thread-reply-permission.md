---
type: fixed
area: thread
platforms:
  - android
  - ios
pull_request: 4571
rollout: full
---

## zh-CN

在智能体会客厅的讨论串里回复时，输入框不再一直显示「正在确认权限，暂时无法发送」：和网页版一样，会客厅里的讨论串可以直接回复（只读或已归档的会客厅除外）。如果某个讨论串的发送权限几秒内仍无法确认，App 会直接向服务器查询，不再无限等待。

## en

Replying in a thread in an agent's Lounge no longer gets stuck on "Checking permissions - sending is temporarily unavailable". As on the web, you can reply in a Lounge's threads right away (unless the Lounge is read-only for you or archived). If a thread's send permission still can't be confirmed after a few seconds, the app now asks the server directly instead of waiting forever.

## ja

エージェントのラウンジのスレッドで、入力欄が「権限を確認しているため、現在は送信できません」のまま止まらなくなりました。Web 版と同じく、ラウンジのスレッドにはすぐ返信できます（閲覧のみのラウンジやアーカイブ済みのラウンジを除く）。スレッドの送信権限が数秒たっても確認できない場合は、待ち続けずにサーバーへ直接確認します。
