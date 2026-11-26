---
type: fixed
area: auth
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复接口返回 HTTP 403 拒绝（例如账号待删除、组织要求 SSO 或访问受限）时误将当前登录态清空并退出登录的问题：现在仅在 HTTP 401 时失效并退出会话，403 会保留会话并展示服务端提示；同时统一了各处服务端拒绝错误码的兼容解析。

## en

Fixed an issue where HTTP 403 refusals (such as pending account deletion, organization SSO requirements, or restricted access) cleared the local session and signed you out: only HTTP 401 now invalidates the session, while 403 preserves the session and displays the server's explanation. Also aligned refusal error-code decoding across auth, channel access, invites, browser sign-in, and agent flows.

## ja

HTTP 403 による拒否応答（削除予定アカウント、組織の SSO 要件、アクセス制限など）でローカルセッションが破棄されサインアウトされてしまう問題を修正しました。セッションの無効化は HTTP 401 のみで行い、403 ではセッションを保持してサーバー側の説明を表示します。あわせて認証・チャンネルアクセス・招待・ブラウザサインイン・エージェント各フローの拒否コード解釈も統一しました。
