---
type: fixed
area: composer
platforms:
  - android
pull_request: 5207
rollout: full
---

## zh-CN

修复了安卓上同时开着两个 Raft 窗口时，一个窗口里开着弹窗或搜索框有焦点，另一个窗口打开会话后输入框不会自动获得焦点的问题（接了实体键盘时才会自动聚焦）。

## en

Fixed an Android issue with two Raft windows open: an open dialog or a focused search field in one window kept the message box in the other window from taking the focus when a conversation opened (this auto focus applies with a hardware keyboard).

## ja

Android で Raft のウィンドウを 2 つ開いているとき、一方のウィンドウでダイアログが開いていたり検索欄にフォーカスがあったりすると、もう一方のウィンドウで会話を開いても入力欄にフォーカスが移らない問題を修正しました（自動フォーカスは外付けキーボード接続時のみ）。
