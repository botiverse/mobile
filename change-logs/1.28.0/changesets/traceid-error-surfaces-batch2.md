---
type: fixed
area: ui
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

继续补齐登录、OAuth 账号关联、频道设置、侧栏置顶与排序及导航栏标签排序、任务详情与关注、Agent 停止与权限以及操作卡片/链接卡片失败时的服务端追踪 ID 展示与复制能力，并确保本地或未收到响应的失败不会沿用旧请求的追踪 ID。

## en

Extended copyable server trace ID display to sign-in, OAuth account linking, channel settings, sidebar pin/reorder and rail tab order saves, task details and follow toggles, agent stop and permissions panels, and action/link card failures, while ensuring local or unanswered failures never reuse an earlier request's trace ID.

## ja

サインイン、OAuth アカウント連携、チャンネル設定、サイドバーのピン留め・並び替えおよびナビゲーションバーのタブ並び替え、タスク詳細やフォロー操作、エージェントの停止・権限設定、およびアクションカード／リンクカードの失敗時にもサーバーのトレース ID を表示・コピーできるようにしました。ローカルエラーや応答のない失敗では過去のトレース ID を引き継がないようにしています。
