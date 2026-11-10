---
type: added
area: thread
platforms:
  - android
  - ios
pull_request: 4518
rollout: full
---

## zh-CN

链接卡片的按钮现在也可以来自本服务器自己创建、未经 Raft 审核的应用。点按这类应用的任何操作按钮时，都会先弹出 Raft 的确认框，写明由哪个应用执行、它未经 Raft 审核、会收到你的名字并用自己的权限执行；确认后才会执行。

## en

Link cards can now show action buttons from Apps your server built itself that Raft hasn't reviewed. Pressing any of their action buttons first opens Raft's confirmation, which names the App running it and says it hasn't been reviewed by Raft and will receive your name and act with its own permissions; nothing runs until you confirm.

## ja

リンクカードに、サーバーが独自に作成した Raft の審査を受けていないアプリの操作ボタンが表示されるようになりました。こうしたアプリの操作ボタンを押すと、まず Raft の確認ダイアログが開き、実行するアプリ、Raft の審査を受けていないこと、あなたの名前を受け取り自身の権限で実行することが表示されます。確認するまで何も実行されません。
