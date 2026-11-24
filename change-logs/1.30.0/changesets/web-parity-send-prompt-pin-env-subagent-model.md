---
type: improved
area: chat
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

对齐 Web 1.30 体验更新：
- 发送提示词弹窗重构为与 Agent 的私聊样式：顶栏直接展示并切换 Agent，中间展示最近 5 条只读私聊记录（含日期分组与 3 行折叠展开），底部复用聊天输入框发送，并清晰提示加载失败。
- 联合频道中当其他服务器关闭置顶公告时，频道详情直接说明不可置顶原因且不显示无效的置顶按钮。
- `agent:create` 动作卡片支持携带 `suggestedEnvVars` 非敏感环境变量提案，在创建 Agent 弹窗中默认展开编辑，并对配置目录变量默认取消勾选且给出安全提示。
- Agent Activity 的子 Agent 列表、详情与日志标签现已显示子 Agent 使用的模型。
- 选择 Antigravity Orchestrator 模型时，模型字段说明会提示任务中途消息需交回规划模型再回复。

## en

Aligned five Web 1.30 parity improvements:
- Redesigned the Send-prompt dialog to mirror the DM with the chosen agent: agent picker in the header, read-only look-back of the last 5 DM messages (grouped with date dividers, 3-line clamp with Show more, and clear load-failure copy), and the real chat composer prefilled at the bottom.
- Joint channels where a peer server disabled pinning now explain why nothing can be pinned instead of offering a Pin button that fails.
- `agent:create` action cards now surface proposed non-secret `suggestedEnvVars` directly open in the Create agent dialog, keeping config-directory variables unticked by default with a warning.
- Agent Activity now displays each sub-agent's model in the sub-agents list, sub-agent detail view, and raw activity badge.
- Antigravity Orchestrator models now display a model hint noting that mid-task messages hand back to the planner model before replying.

## ja

Web 1.30 相当の 5 項目をモバイル／デスクトップへ反映しました：
- プロンプト送信ダイアログをエージェントとの DM 形式へ刷新しました。ヘッダーでエージェントを切り替え、直近 5 件の読み取り専用履歴（日付区切り・3 行折りたたみ・読み込み失敗表示）とチャット入力欄でそのまま送信できます。
- 共同チャンネルで相手サーバーがピン留めを無効にしている場合、失敗するピン留めボタンを出さずに理由を表示するようにしました。
- `agent:create` アクションカードの `suggestedEnvVars` 提案をエージェント作成ダイアログで開いたまま編集できるようにし、設定ディレクトリ系変数は警告付きで未チェック既定としました。
- Agent Activity のサブエージェント一覧・詳細・ログバッジにサブエージェントのモデル名を表示するようにしました。
- Antigravity の Orchestrator モデル選択時、タスク途中のメッセージはプランナーモデルへ戻してから返信される旨のヒントを表示するようにしました。
