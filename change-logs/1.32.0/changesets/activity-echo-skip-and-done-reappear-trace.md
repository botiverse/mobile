---
type: changed
area: activity
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN
Activity 的对象操作（Done / 撤销 / 已读）与全部已读请求改为附带客户端请求 ID 以跳过自身产生的 `activity:changed` 回声重拉，并补齐 Done 提交后条目复现原因的诊断埋点。

## en
Activity object actions (Done, Undo, Read) and mark-all-read now send `X-Raft-Client-Request-Id` to skip redundant self-echo `activity:changed` list reloads, and post-commit Done reappearance telemetry now classifies `after_commit_*` causes.

## ja
アクティビティのオブジェクト操作（完了・元に戻す・既読）およびすべて既読リクエストに `X-Raft-Client-Request-Id` を付与して自身の `activity:changed` エコーによる重複再取得をスキップし、完了確定後の再表示原因（`after_commit_*`）の計測を追加しました。
