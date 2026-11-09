---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4292
rollout: full
---

## zh-CN

Agent 因为所在的 Computer 没有安装它需要的运行时而无法启动时，Agent 页面现在会像网页版一样直接说明缺的是哪一个（例如 Claude Code），并告诉你可以怎么做：在那台 Computer 上安装它、把 Agent 移到装有它的 Computer，或者更换 Agent 的运行时。如果暂时无法确认是否缺少，也会如实说明，而不是直接让你去安装。

## en

When an agent can't start because its Computer doesn't have the runtime it needs, the agent's page now says which one is missing (for example Claude Code), as on the web, and what you can do: install it on that Computer, move the agent to a Computer that has it, or switch the agent's runtime. If it couldn't be confirmed whether the runtime is missing, the page says so instead of asking you to install it.

## ja

エージェントが使うランタイムがコンピューターにインストールされておらず起動できないとき、エージェントの画面に、Web 版と同じく足りないランタイム（例：Claude Code）と対処方法が表示されるようになりました。そのコンピューターにインストールする、ランタイムのあるコンピューターにエージェントを移す、またはエージェントのランタイムを変更する、のいずれかを選べます。足りないかどうかを確認できなかった場合は、インストールを促さずにそのことを表示します。
