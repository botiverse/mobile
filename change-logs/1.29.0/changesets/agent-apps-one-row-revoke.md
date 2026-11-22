---
type: added
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN
- 在 Agent 的「应用」标签页中，每个应用现在只占一行（无论它持有几项授权），并用一句话说明它能做什么，例如「Stamp 可以用这个 Agent 的身份登录和发送通知」。
- 服务器所有者、管理员和这个 Agent 的创建者可以撤销应用的访问权限：「撤销访问权限」会先逐项列出将失去的授权并确认，然后撤销该应用的全部授权。持有多项授权的应用还有「详情」，按授权方式列出各项授权，每项都可以单独撤销。
- 如果有授权没能撤销，确认框会只留下这些授权，并说明原因（附追踪 ID），可以点「重试」；对于无权撤销的授权，只能「关闭」。

## en
- On an agent's Apps tab, each App now has one row, however many grants it holds, with one plain line of what it can do, such as "Stamp can sign in as this agent and send notifications".
- Server owners, admins and the agent's creator can revoke an App's access: Revoke access lists every grant the App would lose, asks to confirm, then revokes them all. An App with several grants also has Details, listing its grants by how access was granted, each with its own Revoke.
- If some grants can't be revoked, the confirmation keeps only those, says why (with the trace ID), and offers Try again; for a grant you aren't allowed to revoke, it offers only Close.

## ja
- エージェントの「アプリ」タブで、アプリは許可がいくつあっても 1 行にまとまり、できることを 1 文で表示するようになりました（例：「Stamp ができること：このエージェントとしてログイン、通知の送信」）。
- サーバーのオーナー、管理者、エージェントの作成者は、アプリのアクセスを取り消せます。「アクセスを取り消す」は失うアクセスを一覧して確認したうえで、そのアプリの許可をすべて取り消します。複数の許可を持つアプリには「詳細」があり、許可の経緯ごとに一覧して、それぞれ個別に取り消せます。
- 取り消せなかった許可がある場合、確認画面にはその許可だけが残り、理由（トレース ID 付き）を表示して「再試行」できます。取り消す権限のない許可は「閉じる」のみです。
