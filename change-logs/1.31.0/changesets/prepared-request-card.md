---
type: added
area: chat
platforms:
  - android
  - ios
  - desktop
rollout: flag
---

## zh-CN

原生支持由 Agent 发起的请求确认卡片（`request:prepare`）：仅指定的决策人看到操作按钮并可在应用内确认或拒绝，其余成员展示只读态与「等待 {name} 确认」提示并隐藏操作按钮；支持卡片撤回（`withdrawn`）与拒绝结果摘要展示。

## en

Added native support for agent-prepared request cards (`request:prepare`): only the named decider sees the decision button and can confirm or decline in-app, while other viewers see a view-only card with "Waiting for {name} to confirm." and no action button. Withdrawn cards and declined result summaries are rendered cleanly across all states.

## ja

エージェントが作成したリクエスト確認カード（`request:prepare`）にネイティブ対応しました。指定された確認者のみに操作ボタンが表示されアプリ内で承認または拒否でき、ほかのメンバーには閲覧専用として「{name} の確認を待っています。」が表示され操作ボタンは非表示になります。取り下げ（`withdrawn`）状態や拒否結果のサマリー表示にも対応しました。
