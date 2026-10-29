---
type: fixed
area: thread
platforms:
  - android
  - ios
pull_request: 3933
rollout: full
---

## zh-CN

「标为未读」在服务器还没给出结果时，不再误报「没有别人的新消息可标」：首页和动态里的未读标记先保留，消息列也继续保持未读，等服务器随后推送结果再更新。如果结果是确实没有可标的内容，而你还停留在这个消息列，会像以前一样提示原因；已经离开的话不再弹出提示。

## en

Mark as Unread no longer says there is nothing new from others to mark while the server is still working on it: the unread state on Home and in Activity stays, and the thread stays unread, until the server sends the result. If it turns out there was nothing to mark and you are still in the thread, the app says so as before; once you have left, no notice appears.

## ja

「未読にする」の結果がまだサーバーから返っていないときに、「他の人の新しいメッセージがない」と誤って表示されなくなりました。ホームとアクティビティの未読表示はそのまま残り、スレッドも未読のまま保たれ、サーバーから結果が届いた時点で更新されます。実際に未読にできるものがなく、まだそのスレッドを開いている場合は、これまでどおりその旨が表示されます。すでに離れている場合は表示されません。
