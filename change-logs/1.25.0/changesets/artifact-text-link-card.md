---
type: changed
area: chat
platforms:
  - android
  - ios
  - desktop
pull_request: 0
rollout: full
---

## zh-CN

消息里写成 `[文字](Artifact 链接)` 的 Artifact 链接，现在也和网页版一样，会在消息下方显示 Artifact 卡片；文字保持原样，点按文字会在应用内打开这个 Artifact，和点卡片一样。之前只有直接贴出的链接才有卡片。另外，一条消息里的链接写法有误或卡片显示出错时，只会让这条消息不显示卡片、保留原链接，不会影响整个会话的显示。

## en

An Artifact link written as `[words](Artifact link)` now also gets its Artifact card under the message, as on the web. The words stay as written, and tapping them opens the Artifact in the app, as the card does. Before, only a bare link got a card. Also, a malformed link or a card that fails to draw now only costs that message its card (the author's link stays); it can no longer break the whole conversation.

## ja

`[テキスト](Artifact のリンク)` の形で書かれた Artifact のリンクにも、ウェブ版と同じくメッセージの下に Artifact カードが表示されるようになりました。テキストはそのまま残り、タップするとカードと同じくアプリ内で Artifact が開きます。これまではリンクをそのまま貼った場合だけカードが表示されていました。また、メッセージ内のリンクの書き方が正しくない場合やカードの表示に失敗した場合も、そのメッセージのカードが表示されないだけで（元のリンクは残ります）、会話全体の表示が壊れることはなくなりました。
