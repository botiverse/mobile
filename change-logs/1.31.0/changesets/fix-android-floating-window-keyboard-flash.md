---
type: fixed
area: composer
platforms:
  - android
rollout: full
---

## zh-CN

修复了在 Android 上曾聚焦过会话输入框后，切到系统小窗再点回消息列表时软键盘会瞬间弹出又立刻收起闪烁的问题：四个页面宿主 Activity 均显式声明 `stateHidden|adjustResize`，同时将窗口焦点、编辑器焦点/光标与显式键盘弹出意图解耦——移动端编辑器默认关闭非点击获焦时的自动弹键盘（`showKeyboardOnFocus = false`），仅在用户直接点击输入框或显式程序化请求（回复、引用、提及、自动聚焦等）时拉起软键盘。失去窗口焦点时不再清空草稿光标与输入框焦点，点回消息列表不会闪现键盘，直接点击输入框即可保留原草稿与光标连续输入。

## en

Fixed the soft keyboard briefly flashing open and immediately snapping shut on Android when tapping back onto a conversation's message list from a system floating window after the composer had previously been focused: all four page host activities declare `stateHidden|adjustResize`, and window focus, editor focus/cursor, and explicit keyboard-show intent are now decoupled—mobile editors default `showKeyboardOnFocus = false` so non-tap focus transitions and window focus regains never auto-raise the IME, while direct user taps and explicit programmatic requests (reply, quote, mention, autofocus, image-editor return) still open the keyboard cleanly. Losing window focus (such as opening a floating window or notification shade) preserves composer focus, draft text, and cursor position, while tapping back on the message list dismisses focus without any keyboard flash.

## ja

Android でメッセージ入力欄にフォーカスした後にシステムのフローティングウィンドウへ切り替え、再びメッセージ一覧をタップして戻った際にソフトウェアキーボードが一瞬表示されてすぐ閉じる問題を修正しました。4つのページホスト Activity に `stateHidden|adjustResize` を明示したうえで、ウィンドウフォーカス・エディタのフォーカス／カーソル・明示的なキーボード表示意図を分離し、モバイルの入力欄はデフォルトで `showKeyboardOnFocus = false` として非タップ時の自動表示を抑止しつつ、直接タップや明示的なプログラム要求（返信・引用・メンション・オートフォーカスなど）では通常どおりキーボードを開くようにしました。ウィンドウフォーカス喪失時に入力中の下書きやカーソル位置が失われず、メッセージ一覧へ戻ってもキーボードがちらつきません。
