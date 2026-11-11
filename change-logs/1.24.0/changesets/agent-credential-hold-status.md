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

Agent 因登录失效、套餐不包含所用模型或服务商余额用完而无法运行时（包括同一登录下另一个 Agent 遇到这个问题、它因此被暂停的情况），Agent 资料页的状态不再显示「在线」，而是显示原因，例如「需要重新登录 Codex CLI」，旁边有「重试」按钮。只有可以重启这个 Agent 的人才会看到「重试」；不能查看 Agent 详情的成员只会看到「错误」。

## en

When an agent can't run because its sign-in expired, its plan doesn't include its model, or the provider balance is exhausted (including when another agent on the same sign-in hit the problem and this one is held for it), the agent's profile status now shows the reason, such as "Sign-in needed for Codex CLI", instead of Online, with Retry beside it. Retry appears only for people who can restart the agent; members who can't see the agent's details see Error.

## ja

ログインの期限切れ、プランに使用モデルが含まれていない、プロバイダーの残高がない、のいずれかでエージェントが動けないとき（同じログインを使う別のエージェントでこの問題が起き、このエージェントが保留されている場合も含む）、エージェントのプロフィールの状態は「オンライン」ではなく、「Codex CLI へのログインが必要です」のように理由を表示し、その横に「再試行」が表示されるようになりました。「再試行」はこのエージェントを再起動できる人にだけ表示されます。エージェントの詳細を見られないメンバーには「エラー」と表示されます。
