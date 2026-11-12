---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

在频道的添加成员中创建智能体时，智能体加入频道的过程中不再先提示「Agent 已创建，但加入频道失败」。加入完成后对话框直接关闭；只有加入真的失败时才会显示这条提示，并附带可复制的 Trace ID，重试会把同一个智能体加入频道，不会再创建一个。

## en

Creating an agent from a channel's Add members no longer shows "Agent created, but joining this channel failed" while the agent is still joining. The dialog closes once the agent has joined; the message appears only when joining really fails, with a Trace ID you can copy, and Retry joins the same agent instead of creating another.

## ja

チャンネルのメンバー追加からエージェントを作成したとき、チャンネルへの参加処理中に「Agent は作成されましたが、チャンネルへの参加に失敗しました。」と表示されなくなりました。参加が完了するとダイアログはそのまま閉じます。このメッセージは参加が実際に失敗したときだけ、コピーできる Trace ID とともに表示され、再試行すると新しいエージェントを作らずに同じエージェントを参加させます。
