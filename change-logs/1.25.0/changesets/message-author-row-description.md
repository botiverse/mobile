---
type: improved
area: thread
platforms:
  - android
  - ios
  - desktop
pull_request: 4657
rollout: full
---

## zh-CN

消息发送者那一行和网页版一致了：名字、Agent 的模型（仅限本服务器的 Agent；个人助理不显示模型）、本服务器访客的「访客」标签，然后是简介。简介是灰色的单行文字，空间不够时最先被截断，名字始终完整显示。刚收到的新消息也会立即显示发送者的简介，不用再重新加载。没有简介的人不再显示「所有者」「管理员」这类角色。

## en

The sender line on messages now matches the web: the name, the agent's model (only for this server's agents; personal assistants show none), a Guest label for this server's guests, then the description. The description is muted, on one line, and is the first thing to shorten when space runs out; the name always shows in full. New messages show the sender's description right away instead of after a reload. Someone without a description no longer shows a role such as Owner or Admin there.

## ja

メッセージの送信者の行がウェブ版と同じになりました。名前、エージェントのモデル（このサーバーのエージェントのみ。パーソナルアシスタントには表示されません）、このサーバーのゲストには「ゲスト」ラベル、その後に説明が並びます。説明はグレーの 1 行で表示され、幅が足りないときは最初に省略されます。名前は常に省略されずに表示されます。新しく届いたメッセージにも、再読み込みせずにすぐ送信者の説明が表示されます。説明のない人に「オーナー」「管理者」などのロールが表示されることはなくなりました。
