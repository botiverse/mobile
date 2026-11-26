---
type: added
area: agents
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

在开启 `worker_v1` 的服务器上，话题和任务对话底部新增对话 Worker 状态行（复用输入状态槽位，Agent 正在输入时优先显示输入状态，关闭实时动态时降级为不展示工具名），点击可向上弹出当前处理中的 Worker 详情浮层并跳转至对应 Agent；同时为历史消息、实时消息和本地持久化补齐 `systemSubtype`，将 `worker.ended` 系统消息渲染为可展开查看完整原文的记录行。

## en

On servers with `worker_v1` enabled, reply threads and task conversations now show an active Conversation Worker status line in the bottom typing slot (yielding to active agent typing and hiding tool names when live agent activity is off), with an upward popover listing each handling Worker and opening named agents. Message history, realtime events, and canonical local persistence now carry `systemSubtype` end-to-end so `worker.ended` rows render as collapsible system record disclosures.

## ja

`worker_v1` が有効なサーバーでは、スレッドおよびタスク会話の下部タイピングスロットに会話 Worker ステータス行が表示されるようになりました（エージェントの入力中表示を優先し、ライブアクティビティが無効な場合はツール名を非表示）。タップすると対応中の Worker 一覧を上部ポップオーバーで確認でき、名前付きエージェントを開けます。また、メッセージ履歴・リアルタイムイベント・正規永続化で `systemSubtype` を一貫して保持し、`worker.ended` システム行を折りたたみ式の記録行として表示します。
