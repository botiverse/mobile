---
type: improved
area: thread
platforms:
  - android
  - ios
  - desktop
pull_request: https://github.com/botiverse/mobile/pull/4302
rollout: full
---

## zh-CN

在连接了 Slack 的频道里输入 @ 时，候选列表现在会和网页版一样列出这个频道的 Slack 成员，并标注“Slack”和所属工作区。选中后发出的消息会在 Slack 那边真正 @ 到这个人。如果发送前删掉了这个 @，就不会再 @ 他；草稿和发送失败后的重试也会保留选中的人。

## en

In channels connected to Slack, typing @ now lists the channel's Slack members, as on the web, marked "Slack" with their workspace. Picking one makes the message mention that person on the Slack side too. If you delete the @ before sending, they are not mentioned; drafts and retries of a failed send keep the people you picked.

## ja

Slack と連携したチャンネルで @ を入力すると、Web 版と同じくこのチャンネルの Slack メンバーが「Slack」とワークスペース名付きで候補に表示されるようになりました。選ぶと、そのメッセージは Slack 側でもその人へのメンションになります。送信前に @ を消した場合はメンションされません。下書きや送信失敗後の再送でも、選んだ相手は保持されます。
