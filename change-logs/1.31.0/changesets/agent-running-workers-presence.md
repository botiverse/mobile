---
type: improved
area: agents
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

当 Agent 本身空闲在线但有 Worker 正在运行时，状态灯显示为忙碌黄灯并展示「正通过 Worker 工作 / 正通过 N 个 Worker 工作」（联合频道中其他服务器的 Agent 仅展示「正通过 Worker 工作」，不暴露数量）；同时纳入工作区「在工作」摘要列表与成员页在工作计数，不计入频道内「正在这里工作」。Agent 自身的思考/工作状态及出错、离线、暂停投递、已暂停或电脑受阻状态均保持优先。

## en

When an online agent is idle itself while one or more of its Workers are working, its status light now shows busy yellow with "Working through a Worker" / "Working through N Workers" (other servers' agents in joint channels show "Working through Workers" without exposing a count). Busy-through-Workers agents are included in the workspace Working now summary and Members page working count, while staying excluded from per-channel "working here" indicators. An agent's own thinking/working state, errors, offline, delivery holds, paused state, and held Computers keep priority.

## ja

エージェント自体はオンライン待機中でも、配下の Worker が作業している間はステータスランプを黄色の作業中表示にし、「Worker を通じて作業中 / N 個の Worker を通じて作業中」（ジョイントチャンネル内の他サーバーエージェントは件数を出さず「Worker を通じて作業中」）と表示するようにしました。ワークスペースの「作業中」サマリーおよびメンバー画面の作業中カウントには含めつつ、チャンネル内の「ここで作業中」表示には含めません。エージェント自身の思考・作業状態やエラー、オフライン、配信保留、一時停止、コンピューター制限が常に優先されます。
