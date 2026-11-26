---
type: added
area: channels
platforms:
  - android
  - ios
  - desktop
rollout: flag
---

## zh-CN

在服务器特性开关 `mcp_personal_accounts` 开启时，原生支持 MCP 连接操作卡片（`integration:connect_mcp`）的账号选择：成员与管理员可直接在卡片内为 Agent 选择可用的个人账号或团队账号（仅展示就绪的个人账号，默认账号保持跟随而非固定绑定），或发起新的 OAuth 账号登录并跟踪授权状态；开关关闭或无可用元数据时保持原有“在网页中打开”回退路径。

## en

Added native account choice for MCP connect action cards (`integration:connect_mcp`) gated behind the server feature flag `mcp_personal_accounts`: members and admins can pick an existing ready personal or team account right on the card (filtering out non-ready personal accounts and keeping "All agents" team accounts following the default), or start a new OAuth sign-in flow with live waiting and cancellation controls. When the flag is off, cards keep the existing "Open on the web" fallback.

## ja

サーバーの機能フラグ `mcp_personal_accounts` が有効な場合に、MCP 接続アクションカード（`integration:connect_mcp`）のアカウント選択にネイティブ対応しました。メンバーや管理者はカード上で利用可能な個人アカウントや共有アカウントを選択できるほか（利用可能な個人アカウントのみを表示し、すべてのエージェント用アカウントはデフォルトに追従）、新しい OAuth ログインを開始して進行状況の確認やキャンセルができます。フラグが無効な場合は従来の「Web で開く」フォールバックを維持します。
