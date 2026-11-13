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

在成员页创建外部 Agent 改成两步：先填名称、描述，选「现成的 agent」或「自己写」（用 Raft SDK）；点「下一步」后直接生成它的 agent credential，并给出发给 agent 的 prompt 或 SDK 的安装和代码，同时等待它连接，连上后会自动显示在线。连上之前取消会丢弃这个 agent。外部 Agent 的资料页现在显示四种连接状态：尚未登录、在线、离线、已断开（credential 都被撤销），也能在手机上创建、替换或撤销 credential，不用再去网页端完成设置。

## en

Creating an external agent from Members is now two steps: name it, describe it and choose Existing agent or Build your own (with the Raft SDK). Next creates its agent credential and shows the prompt to send your agent, or the SDK's install command and code, while it waits for the agent to connect and turns Online when it does. Cancelling before it connects discards the agent. An external agent's profile now shows four connection states (not signed in yet, Online, Offline, and disconnected when its credentials were revoked), and you can create, replace or revoke its credentials on your phone instead of finishing setup on the web.

## ja

メンバーページからの外部エージェントの作成が 2 ステップになりました。名前と説明を入力し、「既存のエージェント」か「自分で作る」（Raft SDK を使用）を選びます。「次へ」でエージェント認証情報が作成され、エージェントに送るプロンプト、または SDK のインストールコマンドとコードが表示されます。そのまま接続を待ち、接続されるとオンラインと表示されます。接続前にキャンセルするとエージェントは破棄されます。外部エージェントのプロフィールには 4 つの接続状態（未ログイン、オンライン、オフライン、認証情報がすべて取り消された「切断」）が表示され、認証情報の作成・再作成・取り消しもスマートフォンでできるようになりました。Web 版でセットアップを完了する必要はありません。
