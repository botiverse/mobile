---
type: fixed
area: thread
platforms:
  - android
  - ios
pull_request: TBD
rollout: full
---

## zh-CN

在有消息历史期限的服务器上，「下一个未读消息列」/「最早的未读消息列」不会再卡在一条超出历史期限的消息列上：如果这条消息列在期限内还有回复，点按钮会直接打开它；如果全部超出期限，会跳到那里并把它标为已读，下一次点就去下一条。

## en

On a server with a limited message history, "Next Unread Thread" / "Earliest Unread Thread" no longer gets stuck on a thread whose parent is past the history window: if the thread has replies inside the window the pill opens it; if it is entirely past the window the pill goes there and marks it read, so the next tap moves on.

## ja

メッセージ履歴に期限があるサーバーで、「次の未読スレッド」/「最も古い未読スレッド」が履歴の期限より前のスレッドで止まらなくなりました。期限内に返信があるスレッドはボタンを押すと直接開き、すべて期限外のスレッドはその場所へ移動して既読にするので、次に押すと次のスレッドへ進みます。
