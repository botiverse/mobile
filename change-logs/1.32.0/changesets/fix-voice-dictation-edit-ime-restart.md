---
type: fixed
area: composer
platforms: [android]
rollout: full
---

## zh-CN
修复了在 Android 上使用语音听写后点击输入框编辑文字时，再次轻点移动光标或点到输入框空白区域会导致软键盘突然收起又立刻弹出的问题：当编辑器的输入法会话已经处于活跃状态时，不再在编辑过程中反复切换聚焦弹键盘选项，避免触发底层输入法会话被销毁重建。

## en
Fixed the Android soft keyboard abruptly closing and immediately reopening mid-edit after voice dictation when tapping again inside the composer to move the cursor or tapping the composer card's blank area: when an editor already has an active platform input session, focus-only keyboard suppression is now kept unchanged until blur instead of toggling `showKeyboardOnFocus` mid-session and tearing down and restarting the input session.

## ja
Android で音声ディクテーション後にメッセージ入力欄をタップしてテキストを編集中、カーソル移動のためにもう一度タップしたり入力欄の余白をタップしたりするとソフトウェアキーボードが突然閉じてすぐ開き直す不具合を修正しました。入力セッションがすでに有効な間はフォーカス時のキーボード抑止フラグを途中で切り替えず、フォーカス解除時にのみクリアすることで、入力セッションの破棄と再起動を防いでいます。
