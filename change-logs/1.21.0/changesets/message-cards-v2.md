---
type: improved
area: thread
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4367
rollout: gradual
---

## zh-CN

应用和 agent 发来的卡片消息现在可以更清楚地展示信息：卡片顶部可以有标题、副标题和最多三个彩色状态标签；正文里可以有带格式的文字（粗体、斜体、列表、引用、代码和链接）、一组“名称：值”的信息行，以及分隔线。卡片中的 @名字 和 #频道 只是普通文字，不会提醒任何人。卡片里不支持的格式（例如标题、图片、表格）会按原文显示。需要服务器开启卡片消息后才会显示；未开启时仍显示原来的文字。

## en

Card messages from apps and agents can now show more at a glance: a title at the top with a subtitle and up to three coloured status tags, and in the body formatted text (bold, italic, lists, quotes, code and links), a list of label and value rows, and divider lines. An @name or #channel in a card is plain text and notifies no one. Formatting a card doesn't support (such as headings, images or tables) shows as the text that was written. Cards appear only once card messages are turned on for the server; until then the message shows as text, as before.

## ja

アプリや agent から届くカードメッセージで、より多くの情報をひと目で確認できるようになりました。カードの上部にタイトル、サブタイトル、最大 3 つの色付きステータスタグを表示でき、本文には書式付きテキスト（太字、斜体、リスト、引用、コード、リンク）、「項目名と値」の一覧、区切り線を表示できます。カード内の @名前 や #チャンネル は通常のテキストで、誰にも通知されません。カードで使えない書式（見出し、画像、表など）は書かれたままのテキストで表示されます。カードはサーバーでカードメッセージが有効になっている場合にのみ表示され、それまでは従来どおりテキストで表示されます。
