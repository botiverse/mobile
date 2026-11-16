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

动态角标在更多情况下保持准确：动态在后台标签页里时不会被当成正在显示；回到应用时如果动态没能读到最新列表，角标会改为从服务器重新读取；只有正在接收实时消息的窗口才负责角标；同一条话题回复不会再被重复计数。

## en

The Activity badge stays right in more cases. Activity in a background tab is no longer treated as on screen. If Activity can't read a fresh list when you come back to the app, the badge is read from the server instead. Only a window that receives live messages keeps the badge. A thread reply is no longer counted twice.

## ja

アクティビティのバッジがより多くの場面で正確に保たれます。背面のタブにあるアクティビティは表示中として扱われなくなりました。アプリに戻ったときにアクティビティが最新のリストを読めなかった場合は、バッジをサーバーから読み直します。バッジはリアルタイムのメッセージを受け取っているウィンドウだけが受け持ちます。スレッドの同じ返信が二重に数えられなくなりました。
