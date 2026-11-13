---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
pull_request: 4663
rollout: full
---

## zh-CN

在「动态」里点开一条消息时，如果这台设备上保存的对话内容里还没有这条消息，页面会先在这条消息的位置显示加载中，等最新内容加载好后直接定位到它，不再先显示一段旧的对话、却看不到你点开的消息。在很活跃的任务对话里，这条消息也会更早出现。如果加载失败，仍会显示设备上已有的内容。

## en

When you open a message from Activity and the conversation saved on this device doesn't include it yet, the page now shows a loading state at that message, then goes straight to it once the latest content loads. It no longer shows an older part of the conversation without the message you tapped. In very busy task conversations, the message also appears sooner. If loading fails, you still see what's already on the device.

## ja

「アクティビティ」からメッセージを開いたとき、この端末に保存されている会話にまだそのメッセージがない場合は、そのメッセージの位置で読み込み中を表示し、最新の内容が読み込まれるとそのメッセージへ直接移動するようになりました。タップしたメッセージがないまま古い会話が表示されることはなくなりました。とても活発なタスクの会話でも、メッセージがより早く表示されます。読み込みに失敗した場合は、端末にある内容がそのまま表示されます。
