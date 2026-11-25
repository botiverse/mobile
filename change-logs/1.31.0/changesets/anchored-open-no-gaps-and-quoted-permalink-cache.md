---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

修复了从动态、通知、消息链接或未读回复跳转到具体消息时，首帧会把未同步完整的零散实时消息硬拼进当前视图、导致消息之间连续出现多个「正在加载更早的消息…」的问题；同时为已加载的引用消息链接预览加入进程内 LRU 缓存，重新进入会话或滚回该消息时不再反复闪烁「正在加载消息…」并重复拉取。

## en

Fixed anchored conversation opens (from Activity, notifications, permalinks, search, or unread reply threads) splicing disconnected realtime messages into the first frame with stacked "Loading older messages…" indicators between rows, and added a viewer-scoped in-memory LRU cache for resolved quoted message permalink previews so returning to a conversation or scrolling back renders immediately without flashing "Loading message…" or re-fetching.

## ja

アクティビティ、通知、メッセージリンク、未読返信から特定メッセージへ移動した際、未同期の断片的なリアルタイムメッセージが初回フレームに差し込まれてメッセージ間に「以前のメッセージを読み込み中…」が連続表示される問題を修正しました。あわせて読み込み済みの引用メッセージリンクプレビューにビューアー単位のメモリ内 LRU キャッシュを追加し、会話を再度開いたりスクロールで戻ったりした際に「メッセージを読み込み中…」が毎回表示・再取得されないようにしました。
