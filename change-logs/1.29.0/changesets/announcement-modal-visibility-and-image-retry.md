---
type: fixed
area: announcements
platforms: [android, ios, desktop]
pull_request: 5493
rollout: full
---

## zh-CN

修复在频道或对话页面从其他应用切回 Raft 时，全局公告弹窗可能浮在消息列表上方且无法点击关闭的问题；现在公告只会在回到主页时弹出，被其他页面覆盖时不会误标为已读关闭。同时修复公告配图加载失败时替代文字被裁切的问题，网络恢复或重新回到前台后会自动重新加载配图。

## en

Fixed an issue where returning to Raft from another app while viewing a channel or thread could leave the announcement dialog stuck over the message list and impossible to dismiss. Announcements now appear only while Home is visible, and navigating to another page hides the dialog without permanently dismissing it so it returns when you come back to Home. Also fixed clipped fallback text when an announcement image fails to load, and failed images now retry automatically when connectivity or the app returns to the foreground.

## ja

チャンネルやスレッドの表示中に他のアプリから Raft へ戻った際、お知らせダイアログがメッセージ一覧の上に重なって操作や閉じる操作ができなくなる問題を修正しました。お知らせはホーム画面が表示されているときのみ表示され、別の画面に移動したときは既読にせず一時的に非表示になり、ホームに戻ると再表示されます。また、お知らせ画像の読み込みに失敗した際に代替テキストが見切れる問題を修正し、ネットワーク復帰やアプリ再開時に自動で再読み込みするようになりました。
