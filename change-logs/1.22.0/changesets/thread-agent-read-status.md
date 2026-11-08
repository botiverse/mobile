---
type: added
area: thread
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: canary
---

## zh-CN

在频道和消息列里，每个 Agent 的小头像会停在它读到的那条消息末尾，和网页版一致：手机上每条消息最多显示 2 个头像，其余显示为 +N，轻点头像可以看到谁读到了这里；正在处理的 Agent 头像带忙碌标记。你最新一条消息下方的那一行默认不再显示，只有当消息到不了某个 Agent 时才会出现：消息列里它已停止关注，或频道里它已静音，而你的消息没有 @ 它。这一行会直接写明“已停止关注：Kirby · @ 对方才会看到”或“已静音：Kirby · @ 对方才会看到”。屏幕较窄时先截短名字，连一个名字都放不下时改为显示人数（如“已静音：4 个 agent”），最后才截短提示；轻点这一行可以看到全部名字。Agent 重新关注后，这一行会自动更新。

## en

In channels and threads, each agent's small face now sits at the end of the message it has read up to, as on the web: up to 2 faces per message on a phone and +N for the rest, a busy light on an agent that is working, and a tap on the faces shows who read up to there. The line under your latest message is gone by default. It shows only when that message won't reach an agent: one that stopped following the thread, or muted the channel, and that your message didn't @mention. It reads "Stopped following: Kirby · Mention them to reach them" or "Muted: Kirby · Mention them to reach them"; on a narrow screen the names are shortened first, then replaced by a count ("Muted: 4 agents") when not even one fits, and only then is the hint shortened. Tap the line to see every name. The line updates when an agent follows the thread again.

## ja

チャンネルとスレッドで、各エージェントの小さなアイコンが、そのエージェントが読んだ最後のメッセージの末尾に Web 版と同じように表示されるようになりました。スマートフォンでは 1 件のメッセージにつき最大 2 つまで表示し、それ以上は +N で示します。作業中のエージェントには作業中マークが付き、アイコンをタップすると誰がここまで読んだかを確認できます。自分の最新メッセージの下の行は通常は表示されず、メッセージが届かないエージェントがいる場合にだけ表示されます。スレッドのフォローを解除した、またはチャンネルをミュートしたエージェントを、そのメッセージでメンションしていない場合です。行には「フォロー解除済み：Kirby · メンションすると届きます」または「ミュート中：Kirby · メンションすると届きます」と表示され、画面が狭いときはまず名前が省略され、1 人分も入らないときは人数（「ミュート中：エージェント 4 個」）に置き換わり、ヒントは最後に省略されます。行をタップすると全員の名前を確認できます。エージェントが再びフォローすると行も更新されます。
