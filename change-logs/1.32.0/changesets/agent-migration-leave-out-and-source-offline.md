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
当 Agent 迁移因工作区超过大小上限失败后，重试弹窗现支持勾选最大目录或添加自定义路径写入 `.raftbackupignore.user` 排除迁移（始终保留 `MEMORY.md`、`notes/` 与 `.raft/`）；同时补齐源计算机离线专属提示并优化未核验目标登录状态时的提示文案。

## en
When an agent migration fails for exceeding the size limit, the retry dialog now lets you pick large directories or add custom workspace paths to leave out via `.raftbackupignore.user` (always preserving `MEMORY.md`, `notes/`, and `.raft/`). Also maps offline-source refusals to dedicated copy and softens the unverified preflight notice across all 8 locales.

## ja
ワークスペースのサイズ上限超過でエージェントの移行が失敗した場合、再試行ダイアログから除外するディレクトリやカスタムパスを選んで `.raftbackupignore.user` に追加できるようになりました（`MEMORY.md`、`notes/`、`.raft/` は常に移行されます）。あわせて移行元オフライン時の専用メッセージと未確認ログイン通知文言を 8 言語で更新しました。
