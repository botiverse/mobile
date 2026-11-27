---
type: changed
area: apps
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN

将聊天中的 App 操作卡片与「添加应用」确认弹框对齐到最新版 App 体验：

- 清理聊天中批准登录、添加应用和创建 Raft 应用卡片的冗余技术字段：移除重复的「服务 / Agent」键值行，移除注册卡片中的回调地址与 Client key / Client ID 行，并将卡片标题与按钮文案对齐为「创建 Raft 应用」「添加 {应用}」。
- 将聊天中 App 操作卡片的权限展示改为平铺自然语言说明，不再折叠在「权限 (N)」列表框或展示已知权限的原始 scope 名称；未知权限仍保留原始名称与说明，不遗漏授权范围；涉及向 Agent 发送事件或通知时显示接收对象与能力限制提示。
- 更新聊天「添加应用」确认弹框的标题与主按钮为「添加 {应用}」（未命名时为「添加应用」），平铺自然语言权限说明并隐藏空的权限分组，不再显示「无」占位行。

## en

Aligned in-chat App action cards and the in-chat Add App confirmation dialog with the latest App experience:

- Removed redundant technical metadata rows from in-chat approve-login, add-app, and create-app action cards: dropped duplicate Service / Agent label-value rows, omitted raw redirect URI and Client key / Client ID rows on app registration cards, and aligned titles and button labels ("Create Raft app", "Add {App}").
- Replaced collapsed `Permissions (N)` scope boxes on App action cards with flat plain-language permission descriptions without raw scope tokens for known scopes (keeping raw names plus Raft's fallback note for unrecognized scopes, and showing agent-inbound scope notices when applicable).
- Updated the in-chat Add App confirmation sheet title and primary button from generic "Install app" / "Install" to "Add {App}" (or "Add app" when unnamed), rendered known scopes in plain language, and hid empty permission groups instead of showing "None" placeholder rows.

## ja

チャット内の App アクションカードおよびアプリ追加確認ダイアログを最新の App 体験に揃えました：

- チャット内のログイン承認・アプリ追加・Raft アプリ作成カードから冗長な技術項目を削除しました。重複する「サービス / エージェント」行や、登録カードのリダイレクト URI・Client key / Client ID 行を取り除き、タイトルとボタン文言を「Raft アプリを作成」「{アプリ} を追加」に統一しました。
- App アクションカードの権限表示を「権限 (N)」の枠や既知の生のスコープ名ではなく、分かりやすい文章でフラットに表示するようにしました。未知のスコープは漏れなく確認できるよう元の名前と注記を残し、エージェントへのイベント／通知送信権限がある場合は送信先と制限の注記を表示します。
- チャット内のアプリ追加確認シートのタイトルとメインボタンを「{アプリ} を追加」（名前がない場合は「アプリを追加」）に更新し、既知の権限を分かりやすい文章で表示して、空の権限グループ（「なし」行）を非表示にしました。
