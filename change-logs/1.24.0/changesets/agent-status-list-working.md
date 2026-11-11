---
type: fixed
area: members
platforms:
  - android
  - ios
pull_request: 4558
rollout: full
---

## zh-CN

修复列表中智能体状态不刷新的问题：首页私信、成员列表里的智能体不会再在它正在工作时一直显示“在线”，而要点进去才看到“工作中”。切换到另一个服务器后，智能体状态也会立即刷新。

## en

Fixed agent status in lists: an agent in Home direct messages or the member list no longer stays "Online" while it is working, where only opening the agent showed "Working". Agent status also refreshes right away after switching to another server.

## ja

リスト内のエージェントの状態を修正しました。ホームのダイレクトメッセージやメンバー一覧で、作業中のエージェントが「オンライン」のまま表示され、開いて初めて「作業中」と分かることはなくなりました。別のサーバーに切り替えた直後にも、エージェントの状態がすぐに更新されます。
