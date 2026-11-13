---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
pull_request: 4689
rollout: full
---

## zh-CN

当一条发给 Agent 的消息过长、运行时无法一次接收而未送达时，Agent 的错误状态现在会显示「消息过长」，并提示缩短消息或拆分为多条发送，与网页版一致，不再只显示笼统的错误提示。

## en

When a message to an agent is too long for its runtime to take at once and isn't delivered, the agent's error now reads "Message too long" and suggests shortening it or splitting it into several messages, as on the web, instead of a generic error.

## ja

エージェントへのメッセージが長すぎてランタイムが一度に受け取れず配信されなかった場合、エージェントのエラーに「メッセージが長すぎます」と表示され、短くするか複数のメッセージに分けるよう案内されるようになりました（Web 版と同じ）。汎用のエラー表示ではなくなりました。
