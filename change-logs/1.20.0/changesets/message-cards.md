---
type: added
area: thread
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4253
rollout: gradual
---

## zh-CN

应用和 agent 发来的卡片消息（例如发布状态通知）现在显示为卡片：标题、状态标签和跳转按钮，状态变化时卡片原地更新，并显示「已更新」时间。需要服务器开启卡片消息后才会显示；未开启时仍显示原来的文字。

## en

Card messages from apps and agents (such as release status notices) now show as cards, with a title, status badges and link buttons. When the status changes, the card updates in place and shows when it was updated. Cards appear only once card messages are turned on for the server; until then the message shows as text, as before.

## ja

アプリや agent から届くカードメッセージ（リリース状況の通知など）が、タイトル・ステータスバッジ・リンクボタン付きのカードとして表示されるようになりました。状態が変わるとカードがその場で更新され、更新時刻が表示されます。カードはサーバーでカードメッセージが有効になっている場合にのみ表示され、それまでは従来どおりテキストで表示されます。
