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

新增 Agent 消息的「查看上下文」面板：在支持查看上下文的服务器（`agent_context_view_v0`）中，打开 Agent 消息的操作菜单会自动检查该 Agent 的上下文可用状态（30 秒按 Agent 缓存，检查失败可在菜单内重试），点击「查看上下文」即可查看模型收到的系统提示词、工具定义与对话消息（支持筛选、搜索、折叠展开、复制原始请求、校验状态与「查看记录」审计列表），并在手机端全屏、宽屏端侧栏展示。同时补充了「管理 › Agent 的上下文访问权限」的共享仓库与原生授权管理组件（含失效授权提示与授权前风险提示）。

## en

Added the Agent message "View context" panel: on servers with `agent_context_view_v0`, opening an agent message's action menu checks that agent's context availability (30s per-agent cache with in-menu Retry on check failure) and opens the full View Context viewer (system prompt, tools, searchable/filterable messages, copy raw request, verification status, unavailable/error states with Retry, and the "Viewed by" audit list) as a full-screen overlay on phones and a side panel on wide screens. Also added the shared data layer and native management UI for Settings › Administration › "Context access for agents" (including stopped-working grant badges and pre-pick server-wide disclosure warnings).

## ja

エージェントメッセージの「コンテキストを表示」パネルを追加しました。`agent_context_view_v0` が有効なサーバーでは、エージェントメッセージの操作メニューを開くとコンテキストの利用可否を確認し（エージェントごとに 30 秒キャッシュ、確認失敗時はメニュー内で再試行可能）、「コンテキストを表示」からシステムプロンプト・ツール定義・検索や絞り込みに対応したメッセージ一覧・生リクエストのコピー・検証バッジ・「閲覧済み」監査ログを確認できます（スマートフォンでは全画面、ワイド画面ではサイドパネル表示）。あわせて「管理 › エージェントのコンテキストアクセス」用の共有データ層およびネイティブ管理 UI（停止中グラント表示・選択前のサーバー全体アクセス警告を含む）も追加しました。
