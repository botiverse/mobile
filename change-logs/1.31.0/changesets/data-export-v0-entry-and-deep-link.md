---
type: added
area: settings
platforms:
  - android
  - ios
  - desktop
rollout: flag
---

## zh-CN

在 `data_export_v0` 灰度开关下新增「设置 › 账号 › 数据与隐私」入口与导出深链处理：
- 开关开启时，在「设置 › 账号」中展示「数据与隐私」（导出你的数据）入口，打开带登录态的设置 WebView 页面（`/settings/account#data-export`）；开关关闭时「设置 › 账号」页面保持完全不变。
- 支持处理邮件或消息中的 `/?data_export=<id>`（及服务器导出 `&data_export_server=<serverId>`）深链：开关开启时直接定位到对应的数据与隐私 WebView 页面，关闭时保留完整查询参数安全回退到浏览器打开。

## en

Added the Settings › Account › **Data & privacy** entry and `/?data_export=<id>` deep-link handling behind the `data_export_v0` feature flag:
- When `data_export_v0` is enabled, **Settings › Account** shows the **Data & privacy** (`Export your data`) entry, opening the authenticated Settings WebView at `/settings/account#data-export`. When the flag is off, Settings › Account remains unchanged.
- Handles `/?data_export=<id>` (and `&data_export_server=<serverId>`) deep links by routing to the corresponding authenticated Data & privacy WebView when enabled, or opening cleanly in the browser with query parameters preserved when off.

## ja

`data_export_v0` フラグ配下で「設定 › アカウント › データとプライバシー」入口と `/?data_export=<id>` ディープリンク処理を追加しました：
- フラグが有効な場合、「設定 › アカウント」に「データとプライバシー」（データをエクスポート）を表示し、認証済みの設定 WebView（`/settings/account#data-export`）を開きます。フラグが無効な場合、「設定 › アカウント」はこれまでどおり変更されません。
- `/?data_export=<id>`（およびサーバーエクスポート用の `&data_export_server=<serverId>`）ディープリンクを処理し、有効時は該当のデータとプライバシー WebView へ遷移し、無効時はクエリパラメータを保持したままブラウザで安全に開きます。
