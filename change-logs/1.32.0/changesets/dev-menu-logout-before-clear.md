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
修复开发者菜单切换 API 环境及在已登录状态下通过 Developer → Router → Login 重新登录时，未先调用 `POST /api/auth/logout` 撤销旧服务端会话就直接清空或覆盖本地令牌的问题。

## en
Fixed Developer-menu API environment switches and signing in via Developer → Router → Login while already signed in to send a best-effort, timeout-bounded `POST /api/auth/logout` (with `refreshToken` and `Authorization: Bearer` fallback against the previous server) before persisting the new environment or clearing/replacing local tokens.

## ja
開発者メニューの API 環境切替およびログイン済み状態での Developer → Router → Login 再サインイン時に、新しい環境の保存やローカルセッションの消去・置換を行う前に、旧サーバーへタイムアウト付きの `POST /api/auth/logout`（`refreshToken` と `Authorization: Bearer` 併用）を送信してサーバー側セッションを失効させるよう修正しました。
