---
type: improved
area: messages
platforms:
  - android
  - ios
pull_request: null
rollout: full
---

## zh-CN

点击消息里的频道、消息列或私信引用时，如果需要先查找目标，被点的引用会变淡并在开头显示加载图标，直到页面打开。打开期间再点同一条或其他引用都不会重复打开；打不开时引用恢复原样，并提示「无法打开消息列，它可能不可用或已被删除。」

## en

When you tap a channel, thread or DM reference in a message and the app has to look the target up first, the tapped reference dims and shows a spinner at its start until the page opens. Tapping it or another reference again meanwhile doesn't open anything twice; if the thread can't be opened, the reference returns to normal and a toast says "Unable to open thread. It may be unavailable or deleted."

## ja

メッセージ内のチャンネル、スレッド、DM の参照をタップしたとき、開く先を先に調べる必要がある場合は、タップした参照が薄くなり先頭に読み込み中のアイコンが表示され、ページが開くまで続きます。その間に同じ参照やほかの参照をもう一度タップしても二重に開くことはありません。スレッドを開けないときは参照が元に戻り、「スレッドを開けません。利用できないか、削除された可能性があります。」と表示されます。
