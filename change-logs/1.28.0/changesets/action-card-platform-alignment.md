---
type: improved
area: thread
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN
- 应用链接卡片按钮的确认改为小号对话框：标题旁有 ×，「取消」在前、确认在后，和网页端一致。
- 应用链接卡片和卡片消息上的按钮执行时，读屏读出的是「处理中…」，不再是「加载中」。
- 关闭助理说明或微信连接时如果有未保存的修改，提示改为「放弃更改？」，按钮为「放弃」，以带 × 的小号对话框显示；更换 Agent 头像时的提示同样改为「放弃更改？」和「放弃」。
- MCP 连接卡片的标题写明为哪个 Agent 授权哪个应用（例如「为 Kai 授权 Notion」），不再显示让你选择连接的那条提示；创建 Agent 的卡片标题里名字前不再带 @。

## en
- An App link card's confirmation is now a small dialog, with × in its head and Cancel before the confirm, as on the web.
- While a button on an App link card or a card message runs, screen readers say "Working…" instead of "Loading".
- Closing the assistant's instructions or WeChat connect with unsaved edits now asks "Discard changes?" with Discard, in a small dialog with ×; changing an agent's avatar asks "Discard changes?" with Discard too.
- An MCP connection card's title now says which App and which agent ("Authorize Notion for Kai") and no longer shows the note asking you to choose a connection; a card that creates an agent names it without an @.

## ja
- アプリリンクカードのボタンの確認が小さなダイアログになりました。見出しに × があり、「キャンセル」が先、確定ボタンが後で、Web 版と同じ並びです。
- アプリリンクカードやカードメッセージのボタンの実行中、スクリーンリーダーは「読み込み中」ではなく「処理中…」と読み上げます。
- アシスタントの指示や WeChat 連携を未保存の変更があるまま閉じると、「変更を破棄しますか？」と「破棄」を、× 付きの小さなダイアログで確認するようになりました。エージェントのアバター変更でも「変更を破棄しますか？」と「破棄」で確認します。
- MCP 接続カードのタイトルに、どのエージェントのどのアプリかが表示されます（例：「Kai の Notion 接続を認可」）。接続を選ぶよう促す注記は表示されなくなりました。エージェントを作成するカードのタイトルでは、名前の前に @ が付かなくなりました。
