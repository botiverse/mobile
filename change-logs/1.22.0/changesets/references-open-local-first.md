---
type: improved
area: thread
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: full
---

## zh-CN

点击消息里指向会话、消息列或任务的引用时，如果它已经在设备上，会立即打开，不再先等服务器。只有需要从服务器获取时才会在引用上显示加载圈；打不开时会提示原因（找不到、没有权限、不在当前服务器），并停留在当前页面。

## en

Tapping a reference to a conversation, thread or task that is already on your device now opens it instantly instead of waiting on the server. A spinner shows on the reference only while it has to be fetched, and if it can't be opened you're told why (not found, no access, not in this server) and stay where you are.

## ja

メッセージ内の会話・スレッド・タスクへの参照をタップしたとき、すでに端末にあるものはサーバーを待たずにすぐ開くようになりました。サーバーから取得する必要があるときだけ参照に読み込み中の表示が出ます。開けない場合は理由（見つからない、アクセス権がない、このサーバーにない）が表示され、今の画面にとどまります。
