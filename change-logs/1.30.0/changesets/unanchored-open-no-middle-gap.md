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

打开频道或私聊时，先展示连续的最近本地消息，不再把尚未同步完整的零散新消息或更早的历史片段硬拼进当前视图，修复了刚进入会话就在消息中间出现「正在加载更早的消息…」的问题；新消息会随进入会话后的刷新从底部顺畅补齐。

## en

Opening a channel or direct message now shows only the latest contiguous local messages instead of splicing disconnected realtime messages or older history fragments into the middle of the chat, fixing a spurious "Loading older messages…" indicator appearing mid-conversation on entry; newer messages load cleanly at the bottom as the conversation refreshes.

## ja

チャンネルやダイレクトメッセージを開いた際、まずは連続した最新のローカルメッセージのみを表示し、未同期の断片的な新着メッセージや離れた過去履歴を途中に差し込まないようにしました。これにより会話を開いた直後にメッセージの途中で「以前のメッセージを読み込み中…」が表示される問題を修正し、新着メッセージは会話の更新とともに下部へ自然に追加されます。
