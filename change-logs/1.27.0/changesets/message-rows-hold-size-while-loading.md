---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
pull_request: 5103
rollout: full
---

## zh-CN

消息下的 Artifact 卡片在加载时先占好接近成品的大小，截图读不出来时也保留它的位置，不再先矮后高或忽然缩回去；自动翻译进行中，消息保持原文的高度，译文到了再替换。消息内容晚到时，上面的消息不再被顶动。

## en

An Artifact card under a message now takes about its finished size while it loads, and keeps its screenshot's place when the screenshot can't be read, instead of growing or shrinking. While a message is being translated automatically it keeps the original's height until the translation arrives. Late content no longer pushes the messages above it.

## ja

メッセージの下の Artifact カードは、読み込み中からほぼ完成時の大きさを確保し、スクリーンショットを読み込めない場合もその場所を保つようになりました。自動翻訳中のメッセージは、翻訳が届くまで原文の高さを保ちます。遅れて届く内容で上のメッセージが押し動かされなくなりました。
