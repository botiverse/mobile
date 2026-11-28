---
type: added
area: agents
platforms: [android, ios, desktop]
rollout: behind-flag
---

## zh-CN
在 `worker_v1` 下的 Agent 详情「动态 › Worker」面板与时间线中支持 Worker 持有零个或多个对话：正确解析多对话与仅发言列表及空持有行，展示「未持有对话」与「还可在 … 发言」文案，按 0 / 1 / 多个对话区分停止确认与提示文案，并在时间线中呈现移交、允许发言与交还事件。

## en
Support Workers holding zero, one, or multiple conversations in Agent Detail › Activity › Workers and Worker timelines behind `worker_v1`: parse `conversations` holdings and empty-holding rows/events without dropping them, show localized multi-conversation and post-only copy, adapt Stop confirmation dialogs and toasts for 0/1/many conversations, and render `delegated` and `undelegated` timeline events across all 8 locales.

## ja
`worker_v1` のエージェント詳細「アクティビティ › Worker」パネルとタイムラインで、Worker が 0 件・1 件・複数の会話を担当する状態に対応しました。複数会話と投稿専用リストや空の行・イベントを破棄せずに解析し、担当会話数に応じた停止確認・通知文言および `delegated` / `undelegated` タイムラインイベントを全 8 言語で表示します。
