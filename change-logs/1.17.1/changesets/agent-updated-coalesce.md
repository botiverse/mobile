---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: TBD
rollout: full
---

## zh-CN

一次改动涉及多个智能体时（例如给应用身份改名），App 只重新读取一次智能体列表，不再每个智能体读一次。

## en

When one change touches many agents at once (for example renaming an app identity), the app now reads the agent list once instead of once per agent.

## ja

一度の変更で複数のエージェントが更新されたとき（アプリ ID の名前変更など）、アプリはエージェント一覧をエージェントごとではなく一度だけ読み込むようになりました。
