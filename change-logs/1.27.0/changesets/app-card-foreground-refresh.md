---
type: fixed
area: messages
platforms:
  - android
  - ios
pull_request: 5094
rollout: full
---

## zh-CN

应用（App）发的卡片在 App 退到后台期间被更新时，回到前台后会显示最新内容，不再停留在旧版本：重新拉取消息时，服务器上较新的消息内容会替换屏幕上的旧内容（卡片只接受更新的版本）。卡片按钮的授权超过 9 分钟时，按下前会先自动重新读取卡片，不再先提示失败、需要再按一次。

## en

An App's card that changed while Raft was in the background now shows its new content when you come back, instead of staying on the old one: when the app reads messages again, the server's newer copy replaces what was on screen (a card only ever moves to a newer revision). When a card's buttons were read more than 9 minutes ago, pressing one reads the card again first, instead of failing and asking you to press again.

## ja

Raft がバックグラウンドにある間に App のカードが更新された場合、戻ったときに新しい内容が表示されるようになりました（古い内容のままにはなりません）。メッセージを読み直すとき、サーバー上の新しい内容が画面上の内容を置き換えます（カードは新しいリビジョンにのみ更新されます）。カードのボタンを読み込んでから 9 分以上たっている場合は、押す前にカードを読み直すため、いったん失敗してもう一度押す必要はなくなりました。
