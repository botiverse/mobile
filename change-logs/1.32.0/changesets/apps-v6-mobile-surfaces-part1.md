---
type: changed
area: apps
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN

统一多个原生 App 界面的文案与版式，并清理重复的设置入口：

- 移除「设置 › 应用」重复入口，Apps 页面直接打开服务器的 `/s/<slug>/apps` 路径，旧链接会自动跳转到对应页面，成员个人连接与链接预览开关仍保留在「应用授权」中。
- 优化 Agent 资料中的「应用」标签页：各 App 改为平铺行列表，多条授权直接展开展示、不再嵌套边框或显示原始权限名；待批准申请以自然语言说明权限并使用「批准请求 / 拒绝请求」按钮；没有 App 事件时隐藏空的事件区块。
- 更新链接预览确认提示按钮为「显示预览 / 只显示链接」，并将「用 Raft 登录」面板的主按钮更新为「登录 {App}」（未命名时为「登录 App」）。
- 统一聊天中连接外部账号卡片的外框、等待横幅与报错文案，改用更易懂的说明并支持服务端标准错误码。

## en

Aligned native App surfaces with the latest App experience and cleaned up the duplicate Settings entry:

- Removed the duplicate Applications row in Settings and opened the Apps view directly at `/s/<slug>/apps` (old links route straight to Apps, while personal connections and link-preview toggles stay in App authorizations).
- Updated the Apps tab on Agent profiles: flattened App rows into a single list group without nested boxes or collapsed multi-grant accordions, displayed permissions in plain language without raw scope tokens, added plain-language pending requests with "Approve request" / "Deny request" actions, and hid the App events section when an Agent has no events yet.
- Updated link-preview consent buttons to "Show previews" / "Keep plain links" and updated the "Sign in with Raft" sheet primary button to "Sign in to {App}" (or "Sign in to the app").
- Refined the in-chat account connection card framing, sign-in waiting banner, and error messages into plain language with full server error-code mapping.

## ja

複数のネイティブ App 画面の文言とレイアウトを統一し、重複していた設定項目を整理しました：

- 設定内の重複した「アプリ」項目を削除し、アプリ画面をサーバーの `/s/<slug>/apps` に直接開くようにしました（古いリンクもそのままアプリ画面へ移動し、個人の連携やリンクプレビュー設定は引き続き「アプリの認可」に残ります）。
- エージェントのプロフィール内の「アプリ」タブを改善しました。各アプリをフラットな一覧行にまとめ、複数の権限も折り畳みや入れ子の枠なしで表示し、権限名を生のスコープ名ではなく分かりやすい文章で表示します。承認待ちリクエストでは「リクエストを承認 / リクエストを拒否」ボタンを表示し、イベントがまだない場合は空のイベントセクションを非表示にしました。
- リンクプレビュー確認のボタンを「プレビューを表示 / リンクのみ表示」に、「Raft でログイン」シートのメインボタンを「{App} にログイン」（アプリ名がない場合は「アプリにログイン」）に更新しました。
- チャット内のアカウント接続カードの枠、ログイン待機バナー、エラーメッセージを分かりやすい表現に整え、サーバーの標準エラーコードにも対応しました。
