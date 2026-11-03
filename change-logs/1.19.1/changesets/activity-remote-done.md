---
type: fixed
area: activity
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4187
rollout: full
---

## zh-CN

在开启增量同步的服务器上，在其他设备（网页版或另一台手机）把动态里的对话标为完成后，App 跟上变化时这一行会直接从动态中移除，和网页版一致，不用再手动刷新；如果完成之后又有新消息，这一行会保留。动态页打开时，其他地方的更新（如工件动态）也会让列表自动刷新。

## en

On servers with incremental sync turned on, when a conversation in Activity is marked Done on another device (the web or another phone), its row now leaves Activity here as soon as the app catches up, as on the web, without a manual refresh; a row with newer messages after that Done stays. While Activity is open, changes made elsewhere (such as Artifact activity) also refresh the list.

## ja

増分同期が有効なサーバーでは、別のデバイス（Web 版や別のスマートフォン）でアクティビティの会話を完了にすると、アプリが変更を取り込んだ時点でその行がアクティビティから消えるようになりました（Web 版と同じ）。手動で更新する必要はありません。完了のあとに新しいメッセージがある行は残ります。アクティビティを開いている間は、ほかの場所での変更（アーティファクトのアクティビティなど）でも一覧が更新されます。
