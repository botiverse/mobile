---
type: added
area: apps
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

在设置中新增「应用授权」（位于当前服务器分组，所有成员及访客可见），统一管理已连接的应用账号与链接预览开关；同时为服务器所有者与管理员点亮「应用」入口的待审核红点（实时响应 `permission-updates:changed`），并支持应用权限更新推送通知（key set 4 与 `{ type: "app" }` 路由直达对应应用详情）。

## en

Added **Settings › App authorizations** under the current server group (visible to every member including guests) to manage connected accounts and link preview toggles in one place. Wired the **Apps** attention dot (`GET /integrations/permission-updates` + realtime `permission-updates:changed`) for owners and admins, and added support for App permission update push notifications (key set v4 localization and `{ type: "app" }` deep-link routing straight to the installed App's detail page).

## ja

設定の現在のサーバーグループに「アプリの認可」（ゲストを含む全メンバー向け）を追加し、連携アカウントとリンクプレビュー設定をまとめて管理できるようにしました。また、オーナー・管理者向けに「アプリ」の要確認ドット（`GET /integrations/permission-updates` とリアルタイムの `permission-updates:changed`）を連携し、アプリの権限更新プッシュ通知（キーセット v4 の多言語コピーと `{ type: "app" }` ルートによるアプリ詳細への直接遷移）に対応しました。
