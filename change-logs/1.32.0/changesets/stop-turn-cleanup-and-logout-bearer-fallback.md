---
type: improved
area: auth
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN
清理停止当前轮次接口不再返回的 `computer_upgrade_required` 专属映射，并在退出登录调用 `/api/auth/logout` 时附带当前有效访问令牌的 `Authorization: Bearer` 兜底撤销服务端会话族。

## en
Removed the dead `computer_upgrade_required` stop-turn feedback mapping and updated logout to send the latest `Authorization: Bearer` token alongside `refreshToken` so `/api/auth/logout` can revoke the server session family even if the refresh token is stale or missing.

## ja
ターン停止 API で不要になった `computer_upgrade_required` 専用マッピングを削除し、ログアウト時の `/api/auth/logout` に最新の `Authorization: Bearer` ヘッダーを付与してセッション族を確実に失効できるようにしました。
