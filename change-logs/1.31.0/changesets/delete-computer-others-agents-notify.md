---
type: added
area: computers
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

在「设置 > 计算机」列表与计算机详情页删除自己接入的计算机时，与 Web 端保持一致：不再因他人 Agent 阻断删除入口，而是调用 `revoke-computer-key`（`delete: true`）；若计算机上仍有他人的运行中 Agent，会列出所有涉及的 Agent 与主人，支持按个发送「通知主人迁走」系统提醒（含 24 小时频率限制、已通知时间、无主人及发送失败重试状态），并支持二次确认「强制解绑并删除」与密钥已吊销但未能移除时的降级提示。

## en

Brought Delete Computer on the Computers rack and Computer detail page into parity with Web: deleting a Computer attached by the current user now routes through `revoke-computer-key` (`delete: true`) instead of blocking on other people's agents up front; when other people's agents remain bound, the dialog lists every agent and its owner, supports per-agent "Notify owner to move it" reminders (with 24-hour rate-limit, notified timestamp, no-owner, and retry states), and offers a two-step "Force unbind and delete" confirmation plus revoked-not-removed fallback copy.

## ja

「設定 > コンピューター」一覧および詳細画面から自分が接続したコンピューターを削除するフローを Web 版と同等にしました。他人のエージェントだけを理由に事前ブロックせず `revoke-computer-key`（`delete: true`）を送信し、409 で他人のエージェントが返された場合は一覧と持ち主を表示して個別の「持ち主に移動を依頼」通知（24 時間制限・通知済み表示・持ち主なし・再試行に対応）および確認付きの「強制的に解除して削除」を実行できるようにしました。
