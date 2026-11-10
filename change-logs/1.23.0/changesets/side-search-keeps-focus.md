---
type: fixed
area: thread
platforms:
  - ios
  - android
  - desktop
pull_request: 4511
rollout: full
---

## zh-CN

在会话旁边的搜索框里打字时，光标不会再被拉回消息输入框。这些搜索框包括「成员」、设置和 Artifacts 的搜索。之前，会话加载完成时，或实体键盘重新连上时（例如妙控键盘唤醒），输入框都可能把焦点抢走。现在输入框只在你打开会话时自动获得焦点，每次打开一次。关闭旁边的讨论串时，如果焦点原本就在讨论串里，它会回到消息输入框；如果你已经在别处，焦点就留在那里。

## en

Typing in a search field beside a conversation no longer gets pulled into the message box. This covers Members, Settings and Artifacts search. Before, the message box could take the focus when the conversation finished loading or when a hardware keyboard reconnected (a Magic Keyboard waking up, for example). Now it takes the focus only when you open the conversation, once each time. Closing the side thread puts the focus back in the message box only if it was in the thread. If you had moved somewhere else, the focus stays there.

## ja

会話の横にある検索欄で入力しているとき、カーソルがメッセージ入力欄に引き戻されなくなりました。「メンバー」、設定、Artifacts の検索欄が対象です。これまでは、会話の読み込みが終わったときや、ハードウェアキーボードが再接続されたとき（Magic Keyboard のスリープ解除など）に、入力欄がフォーカスを奪うことがありました。現在は、会話を開いたときに 1 回だけ入力欄にフォーカスが移ります。横のスレッドを閉じたときは、フォーカスがスレッド内にあった場合だけメッセージ入力欄に戻ります。別の場所に移っていた場合は、フォーカスはそのままです。
