---
type: improved
area: links
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

已安装应用域名识别改为优先读取服务端单次接口 `GET /api/integrations/app-origins`（同时带回链接预览注册域名与应用图标，旧版服务器自动回退至原有接口）；在应用内直接打开的已安装应用链接（Agent「查看服务」、消息卡片按钮与应用链接卡片按钮）不再显示多余的外部跳转箭头 ↗。

## en

Installed Raft App origin resolution now reads the server's single `GET /api/integrations/app-origins` endpoint (including link-preview origins and registered App icons, with automatic fallback on older servers), and links that open inside Raft as an installed App (agent View service, message card buttons, and App link card buttons) now omit the trailing external ↗ indicator.

## ja

インストール済み Raft アプリのオリジン判定でサーバーの単一エンドポイント `GET /api/integrations/app-origins` を優先して取得するようになりました（リンクプレビュー登録オリジンとアプリアイコンを含み、古いサーバーでは従来方式へ自動フォールバックします）。また、Raft アプリ内で開くリンク（エージェントの「サービスを表示」、メッセージカードおよびアプリリンクカードのボタン）では不要な外部リンク矢印 ↗ を非表示にしました。
