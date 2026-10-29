---
type: improved
area: thread
platforms:
  - android
  - ios
pull_request: TBD
rollout: full
---

## zh-CN

点「下一个未读消息列」/「最早的未读消息列」后，如果要先加载那条消息，按钮会立刻显示「正在加载消息列…」和加载动画，直到跳到那条消息；这期间再点不会重复跳转。

## en

When "Next Unread Thread" / "Earliest Unread Thread" has to load the message first, the pill shows "Loading Thread…" with a spinner from the tap until the message is in view, and further taps meanwhile do nothing.

## ja

「次の未読スレッド」/「最も古い未読スレッド」を押したときにメッセージの読み込みが必要な場合、ボタンはタップした瞬間から「スレッドを読み込み中…」とスピナーを表示し、メッセージに移動するまでそのままになります。その間に押しても二重にジャンプしません。
