---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

「动态」的「未读」筛选里，读过后仍留在原处的会话，有新消息时会立刻变回未读并移到最上面，显示新消息的预览，和「全部」里一样；频道、私信和话题都是如此，不会出现两行。自己发的消息不会让它变回未读。如果刷新时服务器还没收到这条消息，列表会稍后再试，期间这一行保留在列表里。

## en

In Activity's Unread filter, a conversation you read stays in place; when a new message arrives in it, it now turns unread right away and moves to the top with the new preview, as it does in All. This works for channels, DMs and threads, and the row is never listed twice. Your own messages don't bring it back. If the list refreshes before the server has the message, it tries again shortly and keeps the row on screen meanwhile.

## ja

「アクティビティ」の「未読」フィルターで、読んだあとその場に残る会話に新しいメッセージが届くと、すぐに未読に戻って一番上に移り、新しいメッセージのプレビューを表示するようになりました。「すべて」と同じ動きで、チャンネル・DM・スレッドのどれでも同じです。行が二重に表示されることはありません。自分のメッセージでは未読に戻りません。サーバーがまだそのメッセージを受け取っていない時点で一覧が更新された場合は、少しあとにもう一度読み込み、そのあいだも行は一覧に残ります。
