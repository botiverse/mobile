---
type: added
area: messages
platforms:
  - android
  - ios
pull_request: 3510
rollout: canary
---

## zh-CN

已读回执（与 Web 一致）：在你自己发的消息里，每个被 @ 的 Agent 旁会显示一个小方块，Agent 读过后变为绿色，未读时为白色；和 Agent 私聊时，对方读到你的最后一条消息后，消息下方显示「已读」。只显示 Agent 的阅读进度，不显示其他成员的；成员很多的频道不显示。随服务端功能开关逐步开放。

## en

Read receipts (same as on the web): on your own messages, each @mentioned agent gets a small square that turns green once that agent has read the message and stays white until then; in a DM with an agent, "Read" appears under your last message once the agent has read it. Only agents' read progress is shown, never other people's, and very large channels show none. Rolls out with a server feature flag.
