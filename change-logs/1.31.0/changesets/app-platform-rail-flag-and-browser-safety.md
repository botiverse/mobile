---
type: fixed
area: apps
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

完善应用平台与内置浏览器体验：为宽屏侧栏每服务器固定项与「编辑导航栏」接入每服务器功能开关 `rail_places_v0`（默认关闭，未开启或加载中保持原有账号级侧栏顺序且不读写每服务器配置）；点击侧栏已缓存的可用应用时立即打开，并在后台按需刷新列表；应用图标加载完成后去除背后的淡紫色底色，使带透明边角的图标自然贴合界面背景；内置浏览器底栏展示长域名时改为从头部截断，确保末尾真实主域名始终完整可见。

## en

Aligned App platform and in-app web panel behavior with web: gated per-server wide-rail places and "Edit navigation bar" behind the per-server `rail_places_v0` feature flag (default off, preserving the account tab order without reading or writing per-server places while off or unresolved); tapping a cached rail App now opens immediately and refreshes places in the background when stale; removed the lavender fill behind loaded App logos so transparent corners render cleanly on the surface underneath; and updated web panel domain display to head-truncate (`StartEllipsis`, LTR) so a long site domain's real trailing host is never cut off.

## ja

アプリプラットフォームおよびアプリ内ウェブパネルの挙動を Web 版に揃えました。ワイドレールにおけるサーバーごとの配置や「ナビゲーションバーを編集」をサーバー単位の機能フラグ `rail_places_v0`（デフォルト無効、無効または未解決時は従来のアカウント順を維持しサーバー配置の読み書きを行わない）で制御し、キャッシュ済みのレールアプリはタップ時に即座に開いてバックグラウンドで最新化するようにしました。また、読み込み完了後のアプリアイコン背面のラベンダー背景を取り除き透過角を自然に表示し、ウェブパネルのドメイン表示は先頭から省略することで末尾の実際のドメインが切れないようにしました。
