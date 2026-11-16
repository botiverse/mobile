---
type: fixed
area: navigation
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

Raft 链接在 App 里打开。指向任务对话的链接（包括 App 自己“复制链接”得到的）会打开那个任务，不再进入一个只有“加入”按钮的频道页。只有频道、没有具体消息的链接，以及其他服务器的会话链接，也在 App 里打开，不再跳到浏览器。写成 `app.raft.build.`（末尾带点）的地址同样算作 Raft 的链接。在 Artifact 里点 Raft 链接，会在 App 里打开对应内容，Artifact 查看器保持打开；Android 上不再关掉查看器后把链接丢掉，iOS 上也不再转到浏览器。App 还没有对应页面的 Raft 页面（例如任务列表、设置、成员资料）仍在浏览器中打开。

## en

Raft links open in the app. A link to a task's conversation (including one from the app's own Copy link) opens the task instead of a channel page that only offers Join. A link to a channel without a message, and a link to a conversation on another of your servers, also open in the app instead of the browser. An address written as `app.raft.build.` (with a trailing dot) counts as a Raft link too. Tapping a Raft link inside an Artifact opens it in the app and keeps the Artifact viewer open; on Android the viewer no longer closes and drops the link, and on iOS the link no longer goes to the browser. Raft pages the app has no screen for yet (such as the task list, Settings or member profiles) still open in the browser.

## ja

Raft のリンクがアプリ内で開くようになりました。タスクの会話へのリンク（アプリの「リンクをコピー」で得たものを含む）は、「参加」しかないチャンネルページではなく、そのタスクを開きます。メッセージを含まないチャンネルへのリンクや、ほかのサーバーの会話へのリンクも、ブラウザではなくアプリ内で開きます。`app.raft.build.`（末尾にドット）と書かれたアドレスも Raft のリンクとして扱います。Artifact 内の Raft リンクをタップすると、Artifact ビューアーを開いたままアプリ内で開きます。Android でビューアーが閉じてリンクが失われることも、iOS でブラウザに移ることもなくなりました。アプリにまだ画面がない Raft のページ（タスク一覧、設定、メンバーのプロフィールなど）は、引き続きブラウザで開きます。
