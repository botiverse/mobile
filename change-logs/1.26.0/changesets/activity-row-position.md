---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: flag
---

## zh-CN

动态列表里的会话不再因为一次迟到的刷新而退回到更早的状态：最新消息、未读数和 @ 标记会保持在已经显示的最新一条上，已读的会话也不会再次变成未读。一条应用无法当场判断的新消息只会重新读取它所在的那一行，而不是整个列表。已静音的会话收到新消息不会增加未读数，但有人 @ 你时仍会显示 @。

## en

Conversations in Activity no longer fall back to an older state when a refresh arrives late: the latest message, the unread count and the @ mark stay on the newest one shown, and a conversation you have read doesn't turn unread again. A new message the app can't place right away refreshes only its own row, not the whole list. In a muted conversation a new message doesn't add to the unread count, but a mention of you still shows the @.

## ja

アクティビティの会話が、遅れて届いた更新で古い状態に戻らなくなりました。最新のメッセージ、未読数、@ マークは表示中の最新の内容のまま保たれ、既読にした会話が再び未読になることもありません。アプリがその場で判断できない新しいメッセージは、リスト全体ではなくその行だけを読み直します。ミュート中の会話では新しいメッセージで未読数は増えませんが、自分へのメンションには @ が表示されます。
