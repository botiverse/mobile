---
type: fixed
area: external-invites
platforms:
  - android
  - ios
  - desktop
pull_request: 5471
rollout: full
---

## zh-CN

- 提示条标题不再强制截断为单行，可以完整换行显示；所有错误提示条统一采用错误样式（警告图标、停留 8 秒、读屏立即播报、文字左对齐），与网页保持一致。
- 连接外部频道或邀请其他服务器被服务端拒绝时，完整展示拒绝原因并将 Trace ID 单独分行显示；未映射为本地化文案的非服务器内部错误会直接显示服务端返回的说明句子，并补齐部署人数上限提示文案。
- 多个服务器同时被拒时合并为一条提示，避免后一条覆盖前一条；仅连接服务器（未发送邮件邀请）被拒时，与网页一致关闭弹窗并弹出未连接提示，不再停留在确认页或误显示“邀请未发出”。

## en

- Toast titles now wrap naturally instead of clipping to a single line, and all error toasts consistently use the error style (warning icon, 8-second display duration, immediate screen-reader announcement, and left-aligned text) to match the web.
- When inviting people outside the server or connecting servers is refused, the refusal reason now wraps in full above a dedicated Trace ID line; non-5xx refusals without dedicated localized copy surface the server's explanation directly, and deployment user limit messages are localized.
- Multiple refused servers merge into a single toast with plural wording so later refusals do not replace earlier ones; when connecting servers without email invites fails, the dialog now closes and shows a server-not-connected toast (matching the web) instead of staying open or saying invites were not sent.

## ja

- トーストのタイトルが 1 行で省略されず自然に折り返すようになり、すべてのエラートーストで Web と同じエラー表示スタイル（警告アイコン、8 秒表示、スクリーンリーダーの即時読み上げ、左揃え）に統一しました。
- 外部チャンネルへの接続や他サーバーへの招待がサーバーに拒否された際、拒否理由を全文表示し、Trace ID を独立した行に分けて表示します。専用の翻訳がない拒否ではサーバーの説明文をそのまま表示し、デプロイメントの人数上限メッセージも多言語対応しました。
- 複数サーバーが同時に拒否された場合は 1 件のトーストにまとめて上書きを防ぎ、メール招待なしでサーバー接続のみが失敗したときは Web と同様にダイアログを閉じて未接続トーストを表示します。
