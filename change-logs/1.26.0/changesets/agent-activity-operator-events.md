---
type: added
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 4866
rollout: full
---

## zh-CN

Agent 的“动态”概览现在会在各轮之间显示谁启动、停止、重启、重置或迁移了这个 Agent，谁停止了当前这一轮，以及谁改了模型或运行时，例如“Artin 停止了 Agent”。启动失败后 Raft 自动重启的记录颜色更浅，不显示人名。连续三条及以上会折叠成一行，点一下即可展开。

## en

An agent's Activity overview now shows, between its turns, who started, stopped, restarted, reset or moved the agent, who stopped the current turn, and who changed its model or runtime, for example "Artin stopped the agent". Raft's own restarts after failed starts are fainter and name no one. Three or more in a row fold into one line that opens with a tap.

## ja

エージェントのアクティビティ概要で、ターンの間に、誰がエージェントを起動・停止・再起動・リセット・移行したか、誰が現在のターンを停止したか、誰がモデルやランタイムを変更したかが表示されるようになりました（例：「Artin がエージェントを停止しました」）。起動に失敗した後の Raft による自動再起動は薄く表示され、名前は出ません。3 件以上続くと 1 行にまとまり、タップで開けます。
