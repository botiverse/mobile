---
type: fixed
area: analytics
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
对齐了产品埋点契约与导航页面路由：`page_viewed` 补齐主题、服务器通知与已授权账号设置子页标识，Apps 与 Artifacts 的导航和页面浏览统一上报至对应路由并避免提升底栏重复上报，动态列表打开成品行时正确上报 `activity_item_open`。

## en
Aligned product analytics event contracts and navigation routes with web: added `theme`, `server-notifications`, and `authorized-accounts` tabs to `page_viewed`, wired `apps` and `artifacts` navigation and page-view routes (avoiding duplicate emissions on promoted phone tabs), and emitted `activity_item_open` with the `artifact` item kind when opening Artifact rows from Activity.

## ja
プロダクト計測イベント契約とナビゲーションルートを Web 版に揃えました。`page_viewed` にテーマ・サーバー通知・認可済みアカウントの設定タブを追加し、Apps および Artifacts のナビゲーション・ページ表示ルートを接続（昇格ボトムタブでの重複送信も解消）したほか、アクティビティ一覧から Artifact 行を開いた際に `activity_item_open` を送信するよう修正しました。
