---
type: fixed
area: channels
platforms: [android, ios, desktop]
rollout: full
pull_request: 5513
---

## zh-CN
- 修复在频道设置（重命名、归档、删除、访客可见、Slack Bridge 连接）与频道成员管理（添加成员、移除成员、调整频道管理员角色）被服务端拒绝时，未能展示服务端具体拒绝原因与追踪编号的问题。

## en
- Fixed channel settings actions (rename, archive, delete, visitor visibility, Slack Bridge pairing) and channel member actions (add, remove, role change) omitting the server's refusal reason and trace ID when refused.

## ja
- チャンネル設定（名前変更・アーカイブ・削除・ゲスト公開設定・Slack Bridge 連携）およびチャンネルメンバー操作（追加・削除・ロール変更）がサーバーに拒否された際、サーバーの具体的な拒否理由とトレース ID が表示されない不具合を修正しました。
