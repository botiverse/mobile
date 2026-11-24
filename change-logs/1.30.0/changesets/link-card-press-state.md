---
type: fixed
area: messages
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

点击应用链接卡片上的操作按钮后，如果应用正在后台处理，按钮会一直保持加载状态并禁用同卡片其他按钮，避免误重复点击；同时实时订阅并轮询操作状态，处理完成后立刻刷新卡片，且状态提示按当前界面语言显示。

## en

After pressing an action button on an App link card, the button now stays in its loading state while sibling actions stay disabled until the App finishes and the card refreshes, preventing accidental duplicate taps. Raft also tracks operation updates with live hints plus status polling so cards refresh as soon as the action completes, and shows state notices in the app's language.

## ja

アプリのリンクカードの操作ボタンを押したあと、アプリが処理を完了してカードが更新されるまでボタンの読み込み表示と他ボタンの無効化が維持されるようになり、重複タップを防げます。操作状態のライブ通知とポーリングにも対応し、完了直後にカードを更新して現在の表示言語で状態メッセージを表示します。
