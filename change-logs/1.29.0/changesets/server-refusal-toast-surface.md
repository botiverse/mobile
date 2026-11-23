---
pull_request: 5471
category: fixed
platforms: [android, ios, desktop]
---

### zh-CN
- 修复连接外部频道或邀请其他服务器被服务端拒绝时，带 Trace ID 的错误提示只显示标题、原因被截断的问题；改为展示完整原因并将 Trace ID 单独分行显示，多个服务器被拒时合并为一条提示，并补齐部署人数上限提示文案。

### en
- Fixed error toasts with a Trace ID clipping the server's refusal reason when inviting or connecting external servers; refusal reasons now wrap in full above a dedicated Trace ID line, multiple server refusals merge into one toast, and deployment user limit copy is localized.

### ja
- 外部チャンネル接続や他サーバー招待が拒否された際、Trace ID 付きエラートーストで拒否理由が途切れる問題を修正しました。理由を全文表示して Trace ID を別行に分け、複数サーバーの拒否を 1 件にまとめ、デプロイメント人数上限の文言を追加しました。
