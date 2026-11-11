---
type: added
area: agents
platforms:
  - android
  - ios
pull_request: 0
rollout: full
---

## zh-CN

迁移 Agent 时，如果原计算机正在等 Agent 结束当前回合、后台任务或排队的消息，迁移卡片现在会写明在等什么、最晚几点停下。Agent 自己精简工作区并重新发起迁移时，卡片会说明它代谁重试，并列出失败的那次和本次的大小（以 GiB 计）；这次重试也失败时，会提示下一次迁移需要由人发起。操作卡片上的计算机现在显示为你的计算机列表里的名字，而不是一串 ID。

## en

While a migration's source computer waits for the agent to finish its current turn, its background tasks or its queued messages, the migration card now says what it waits for and when it stops at the latest. When an agent trims its workspace and retries a migration itself, the card says on whose behalf, and shows the failed attempt's size next to this one's (in GiB); if that retry fails too, it says a person needs to start the next migration. Computers on action cards now show by their name from your computer list instead of an ID.

## ja

移行元のコンピューターがエージェントの現在のターン、バックグラウンドタスク、キューにあるメッセージの完了を待っている間、移行カードに何を待っているか、遅くともいつ停止するかが表示されるようになりました。エージェントが自分でワークスペースを整理して移行を再試行すると、誰に代わって再試行したか、失敗した試行と今回の試行のサイズ（GiB 単位）が表示されます。その再試行も失敗した場合は、次の移行を人が開始する必要があることが表示されます。操作カード上のコンピューターは、ID ではなくコンピューター一覧の名前で表示されるようになりました。
