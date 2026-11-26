---
type: added
area: billing
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

30 天聊天记录升级弹窗现已支持与 Web 端一致的会话上下文提示，并在服务器所有者或管理员查看时于同一句中展示过去 7 天内 Agent 触达 30 天历史窗口的次数（来自计费摘要的 `agentHistoryBlocks7d` 字段）；次数为 0、未返回或从搜索等非会话入口打开时保持原有文案。

## en

The 30-day history upgrade dialog now displays the contextual conversation line matching Web and, for server owners and admins, appends how often the server's agents ran into the 30-day history window in the past 7 days (parsed from `agentHistoryBlocks7d` in the billing summary) within the same sentence; non-contextual gates and zero/missing counts omit the clause.

## ja

30 日間履歴のアップグレードダイアログに Web 版と同等の会話コンテキスト文言を追加し、サーバーのオーナーおよび管理者向けには過去 7 日間にエージェントが 30 日制限に当たった回数（請求サマリーの `agentHistoryBlocks7d`）を同じ文中に表示するようにしました。回数が 0・未取得の場合や検索など非コンテキスト導線では回数句を表示しません。
