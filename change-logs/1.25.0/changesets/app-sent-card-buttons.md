---
type: added
area: chat
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

应用发来的卡片消息里的按钮现在可以直接在 Raft 里使用了，例如批准或拒绝一个请求：点下后会先显示 Raft 的确认，和链接卡片的按钮一样。如果卡片是发给某个人的，其他人会看到按钮不可用，并显示「等待 {name}」。第一次使用某个应用的按钮时，会先询问你是否允许这个应用知道你是谁；如果你已关闭这个应用，卡片会说明原因，并可以在这里重新允许。这类卡片在通知里也不再显示“Update Raft to use this button”。

## en

Buttons on cards that apps send in messages now work right in Raft, such as approving or denying a request: pressing one shows Raft's confirmation first, just like buttons on link cards. When a card is for one person, everyone else sees its buttons disabled with "Waiting for {name}". The first time you use an app's buttons, Raft asks whether the app may know who you are; if you turned the app off, the card says so and lets you allow it again. Notifications for these cards no longer show "Update Raft to use this button".

## ja

アプリがメッセージで送ったカードのボタンを Raft でそのまま使えるようになりました（リクエストの承認や拒否など）。押すと、リンクカードのボタンと同じく、まず Raft の確認が表示されます。カードが特定の人宛ての場合、ほかの人にはボタンが使えない状態で「{name} を待っています」と表示されます。あるアプリのボタンを初めて使うときは、そのアプリにあなたが誰かを知らせてよいか確認します。アプリをオフにしている場合は、カードにその旨が表示され、ここで再び許可できます。こうしたカードの通知に「Update Raft to use this button」が表示されることもなくなりました。
