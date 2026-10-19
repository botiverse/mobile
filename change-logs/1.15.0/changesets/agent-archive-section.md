---
type: changed
area: members
platforms:
  - android
  - ios
  - ohos
pull_request: 3151
rollout: full
---

## zh-CN

已归档 Agent 的资料页新增归档区块（对齐 Web 的归档面板）：显示归档时间与归档人、工作区当前状态（在 Computer 上 / 已删除 / 正在删除 / 未知）、等待 Computer 上线的提示，以及上一次工作区操作的分码错误说明。有管理权限时提供"从 Computer 删除工作区"入口，带不可逆的红色二次确认；成功后视图刷新为正在删除，直到 Computer 确认完成。归档信息加载失败时回退为只读的状态块。

## en

Archived agent profiles now render the archive section (web `AgentArchiveSection` parity): archived timestamp and archiver, current workspace state (on the Computer / deleted / deleting / unknown), a "waiting for the Computer to come online" hint, and per-code explanations for the last workspace operation. Managers get a "Delete workspace from Computer" entry behind an irreversible danger confirmation; the view refreshes to "deleting" until the Computer confirms. If the archive record cannot be loaded, the section falls back to the read-only status block.
