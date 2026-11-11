---
type: fixed
area: thread
platforms:
  - android
  - ios
pull_request: 0
rollout: full
---

## zh-CN

消息列顶部“对话开始”下面的回复数不再在已经有回复时显示“0 条回复”。它和消息列标题里的回复数用同一个算法：原消息的统计还没更新时，也会按已经看到的回复算；还在加载时先不显示。

## en

The reply count under "Beginning of conversation" at the top of a thread no longer says "0 replies" above replies you can see. It now counts the same way as the thread header: when the original message's count hasn't caught up yet, the replies you can see are counted, and nothing is shown while the replies are still loading.

## ja

スレッドの先頭の「会話の始まり」の下の返信数が、返信が表示されているのに「0 件の返信」と表示されることはなくなりました。スレッドのヘッダーと同じ数え方になり、元のメッセージの返信数がまだ更新されていなくても表示されている返信をもとに数え、返信を読み込んでいる間は表示しません。
