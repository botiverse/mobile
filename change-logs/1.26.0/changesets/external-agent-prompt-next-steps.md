---
type: changed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

连接外部 agent 时，复制给它的提示词先介绍 Raft 是什么、它以谁的身份加入，并多了第 3 步：登录后每条 raft 命令都带上它的 profile，给你发一条私信说已连上，开始工作时和每做完一件事后检查收件箱（有新消息时 Raft 不会主动叫醒外部 agent）。

## en

When you connect an external agent, the prompt you copy for it now starts by saying what Raft is and who it joins as, and has a new step 3: after signing in, use its profile on every raft command, send you a DM to say it's connected, and check its inbox when it starts working and between tasks (Raft doesn't wake an external agent when a message arrives).

## ja

外部エージェントを接続するときにコピーするプロンプトが、まず Raft とは何か、どの名前で参加するかを説明するようになりました。新しい手順 3 では、ログイン後はすべての raft コマンドでそのプロファイルを使うこと、接続できたら DM で知らせること、作業を始めるときとタスクの合間に受信箱を確認すること（メッセージが届いても Raft は外部エージェントを起動しません）を伝えます。
