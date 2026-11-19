---
type: improved
area: diagnostics
platforms:
  - android
  - ios
  - desktop
pull_request: 5069
rollout: full
---

## zh-CN

反馈报告附带的诊断日志里，会记录 Raft 没有采用的 Agent 状态更新，以及没采用的原因，方便排查 Agent 状态显示不对的问题。日志只记录编号，不记录 Agent 正在做什么。

## en

The diagnostics attached to a feedback report now record agent status updates Raft did not apply, and why, to help track down an agent status that looks wrong. Only ids are recorded, never what the agent is doing.

## ja

フィードバックに添付される診断ログに、Raft が反映しなかったエージェントの状態更新とその理由が記録されるようになりました。エージェントの状態表示がおかしいときの調査に役立ちます。記録されるのは ID だけで、エージェントの作業内容は記録されません。
