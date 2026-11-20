---
type: fixed
area: artifacts
platforms:
  - android
  - ios
pull_request: 5160
rollout: full
---

## zh-CN

在手机上查看 Artifact 时，点工具栏里的原消息（私信里显示的是对方的名字）会在 Artifact 之上打开那个会话；按返回会回到刚才的 Artifact，版本、文件和打开的评论列表都还在。以前会先关掉 Artifact 再进入会话。需要 Raft 网页端同步更新后生效，在此之前仍是原来的行为。

## en

On a phone, tapping an Artifact's original message in its toolbar (in a DM it shows the other person's name) now opens that conversation on top of the Artifact. Back returns to the Artifact, with the same version, file and open comment list. Before, the Artifact closed first and then the conversation opened. This takes effect once the matching Raft web update is live; until then it works as before.

## ja

スマートフォンで Artifact を表示中に、ツールバーの元のメッセージ（DM では相手の名前が表示されます）をタップすると、その会話が Artifact の上に開くようになりました。戻ると元の Artifact に戻り、バージョン、ファイル、開いていたコメント一覧もそのままです。以前は Artifact が先に閉じてから会話が開いていました。対応する Raft ウェブ側の更新が公開されると有効になり、それまではこれまでどおりの動作です。
