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

对齐 1.31.0 Web 端三项能力：已停止支持的运行时（旧版 Kimi CLI、Gemini CLI、Copilot CLI）现在会显示对应的受阻状态、更换运行时提示与 DM 输入框上方提醒（若所在 Computer 版本过旧不支持 Kimi Code，会先提示更新 Computer）；跨服历史联合任务在参与服侧的讨论以只读方式展示（显示只读说明、禁用意向表情与操作卡，并在 403 `joint_task_read_only` 时优雅展示“保留在托管服务器上”空态）；离线且 7 天内因版本过低被拒连的 Computer 会解析并展示 `refusedComputerVersion` 对应的重装指引与首页通知。

## en

Added 1.31.0 web parity across agents, joint tasks, and Computers: retired runtimes (old Kimi CLI, Gemini CLI, Copilot CLI) now show their blocked state, per-runtime guidance, and DM composer notice (leading with a Computer update when an older Computer cannot run Kimi Code); historical joint task discussions on participant servers render in read-only mode with reactions and action cards disabled and a dedicated host-server empty state on `403 joint_task_read_only`; and offline Computers refused within the last 7 days now parse `refusedComputerVersion` to display accurate version-specific reinstall guidance and notification-center notices.

## ja

1.31.0 の Web 版に合わせて 3 つの機能を追加しました。サポート終了したランタイム（旧 Kimi CLI、Gemini CLI、Copilot CLI）ではブロック状態・ランタイム別の案内・DM コンポーザー上の通知が表示され、Kimi Code 非対応の古いコンピューターでは先にコンピューターの更新を案内します。参加サーバー側の過去ジョイントタスクの議論は読み取り専用（リアクションとアクションカード無効）となり、`403 joint_task_read_only` 時はホストサーバー専用の空状態を表示します。また、過去 7 日以内に接続拒否されたオフラインコンピューターでは `refusedComputerVersion` を解析し、正確な再インストール案内と通知センター表示を行います。
