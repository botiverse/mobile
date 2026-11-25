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

优化了创建、迁移与配置 Agent 的多处体验：创建或编辑 Agent 的「高级 › 环境变量」支持直接从同一台 Computer 上其他可编辑的 Agent 复制环境变量（不覆盖已填写的键，超过 8 个候选时支持按名称或变量名搜索）；创建与迁移 Agent 的 Computer 选择器完整展示并换行显示 Computer 备注；频道「添加成员」页的「添加 Agent」入口改为居中选择弹窗（直接进入「创建 Agent」或「连接外部 Agent」）；运行时配置保存提示精简为「已生效 / 已保存 / 当前消息后生效并提示升级 Raft Computer」，并去除「轮次」表述。

## en

Improved agent creation, migration, and runtime configuration flows: Advanced › Environment Variables on Create and Edit agent now lets you copy variables from another editable agent on the same Computer without overwriting existing keys (with search above 8 candidates); Create and Move agent Computer pickers display each Computer's owner description in full with wrapping; the channel Add members "Add agent" row now opens a direct-entry chooser dialog for "Create agent" and "Connect an external agent"; and runtime config apply toasts now use concise applied/saved tiers with current-message wording and an upgrade hint for older Raft Computers.

## ja

エージェントの作成・移行・ランタイム設定フローを改善しました。作成・編集ダイアログの「詳細 › 環境変数」で、同じコンピューター上の他の編集可能なエージェントから環境変数を既存キーを上書きせずにコピーできるようになり（候補が 8 件を超える場合は検索可能）、作成・移行時のコンピューター選択で各コンピューターの説明が省略されず折り返して表示されるようになりました。また、チャンネルの「メンバーを追加」にある「エージェントを追加」から直接選択できるモーダル（「エージェントを作成」/「外部エージェントを接続」）が開くようになり、ランタイム設定適用トーストも簡潔な表現と現在のメッセージ完了後の案内・Raft Computer アップデート案内に統一しました。
