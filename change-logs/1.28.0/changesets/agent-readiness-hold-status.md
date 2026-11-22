---
type: fixed
area: agents
platforms:
  - android
  - ios
  - desktop
rollout: full
pull_request: 5241
---

## zh-CN

修复了当 Agent 因服务商额度耗尽或限流（`quota_exhausted` / `rate_limited`）等原因被暂缓启动回合时，客户端未读取服务端 `readiness` 状态、仍在资料页/私信顶栏/成员列表/侧边栏显示「空闲」或「已收到消息」的问题。现在会按网页端一致的口径展示状态灯、恢复倒计时或「暂时无法回复」以及保留消息等待送达的说明。

## en

Fixed an issue where agents held from starting new turns due to provider quota exhaustion, rate limits (`quota_exhausted` / `rate_limited`), or other server readiness holds still appeared as "Idle" or "Message received" in the app. Mobile now reads the server-composed `readiness` field from `GET /api/agents` and displays the matching status light, reset countdown ("Back in …") or "Can't reply right now", and held-message detail across the agent profile, DM header, member list, and sidebar.

## ja

プロバイダーの利用枠上限やレート制限（`quota_exhausted` / `rate_limited`）などでターン開始が一時停止されている Agent について、アプリ側がサーバーの `readiness` を読み取らず「待機中」や「メッセージを受信しました」のまま表示されていた問題を修正しました。Web と同様に、状態インジケーター、回復までのカウントダウンまたは「現在返信できません」、およびメッセージが保持され後で届く旨の説明を表示するようになりました。
