---
type: fixed
area: members
platforms:
  - android
  - ios
  - desktop
pull_request: 4656
rollout: full
---

## zh-CN

在共享频道的成员列表里，本服务器的成员不会再被误标为「来自其他服务器」。以前，如果一位本服务器成员是先通过另一台服务器加入这个频道的，App 会把他当作其他服务器的人：不显示「移除」，点开资料也只能看到其他服务器成员的简略信息。现在由服务器直接告诉 App 这个人是不是本服务器的成员，和网页版一致。管理员可以移除服务器允许移除的其他服务器成员。同时属于多台服务器的人，会显示第一台服务器的名字和「+N」。「访客」标签只会出现在本服务器的访客身上。@ 提及列表也按同样的规则显示来源服务器。

## en

In a shared channel's member list, members of your server are no longer marked as being from another server. Before, if one of your members had first joined the channel through another server, the app treated them as that server's member: Remove was missing and their profile opened as another server's short profile. Now the server tells the app whether each person belongs to your server, as on the web. Admins can also remove members of other servers when the server allows it. Someone who is in several servers shows the first server's name and "+N". The Guest label appears only on your own server's guests. The @ mention list names servers by the same rule.

## ja

共有チャンネルのメンバー一覧で、このサーバーのメンバーが「他のサーバーのメンバー」と誤って表示されなくなりました。これまでは、このサーバーのメンバーが先に別のサーバー経由でチャンネルに参加していると、アプリはその人を別サーバーのメンバーとして扱っていました。そのため「削除」が表示されず、プロフィールも他サーバーのメンバー向けの簡易表示で開いていました。今後は、その人がこのサーバーのメンバーかどうかをサーバーが直接アプリに伝えます（ウェブ版と同じ）。管理者は、サーバーが許可している場合、他のサーバーのメンバーも削除できます。複数のサーバーに所属する人には、最初のサーバー名と「+N」が表示されます。「ゲスト」ラベルはこのサーバーのゲストにだけ表示されます。@ メンションの候補一覧でも、同じルールでサーバー名が表示されます。
