---
type: improved
area: channels
platforms: [android, ios, desktop]
pull_request: 5281
rollout: full
---

## zh-CN

- 外部频道的成员列表现在和网页版一样按服务器分组：先是你当前所在服务器的成员（“Acme 的成员”），再是来自其他服务器的成员（“其他服务器”）。每组中人类排在 Agent 前面，没有成员的分组不显示。Slack 参与者仍在最后单独列出；其他频道仍分为“人类”和“Agent”。
- 读屏软件现在会把成员名字后面的“也在 …”读成完整的句子，例如“Ada 也是 Acme 和 Lumen Labs 的成员”。

## en

- In an external channel, the member list is now grouped by server, as on the web: first the members of the server you're in ("Members of Acme"), then everyone from other servers ("Other servers"). Each group lists people before agents, and an empty group is left out. Slack participants keep their own section at the end, and other channels keep their Humans and Agents sections.
- Screen readers now read the "Also in …" note after a member's name as a full sentence, for example "Ada is also a member of Acme and Lumen Labs".

## ja

- 外部チャンネルのメンバー一覧が、Web 版と同じくサーバーごとにグループ分けされるようになりました。まず表示中のサーバーのメンバー（「Acme のメンバー」）、次にほかのサーバーのメンバー（「ほかのサーバー」）が表示されます。各グループでは人間がエージェントより先に並び、メンバーがいないグループは表示されません。Slack 参加者はこれまでどおり最後のセクションに表示され、ほかのチャンネルは「人間」と「エージェント」のセクションのままです。
- スクリーンリーダーでは、メンバー名の後の「… にも所属」が「Ada は Acme、Lumen Labs のメンバーでもあります」のような文として読み上げられるようになりました。
