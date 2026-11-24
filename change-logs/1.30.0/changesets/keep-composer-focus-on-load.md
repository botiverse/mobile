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

修复首次进入未缓存的空私聊或频道时，在消息加载完成前点击输入框会因页面切换导致输入框失焦、键盘弹起后自动收回的问题：现在底部输入框固定托管在页面脚手架中，加载完成后保持焦点与输入状态不中断。

## en

Fixed an issue where tapping the composer before an uncached empty DM or channel finished loading caused the keyboard to open and immediately dismiss when the page settled. The composer dock now stays persistently attached in the route scaffold so editor focus and keyboard input remain uninterrupted across the loading transition.

## ja

キャッシュされていない空の DM やチャンネルを開いた直後に読み込み完了前に入力欄をタップすると、画面切り替えでフォーカスが外れてキーボードが自動で閉じてしまう不具合を修正しました。入力欄を画面スキャフォールドに固定配置することで、読み込み完了後もフォーカスと入力状態を途切れずに維持します。
