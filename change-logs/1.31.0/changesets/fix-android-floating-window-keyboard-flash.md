---
type: fixed
area: composer
platforms:
  - android
rollout: full
---

## zh-CN

修复了在 Android 上曾聚焦过会话输入框后，切到系统小窗再点回消息列表时软键盘会瞬间弹出又立刻收起闪烁的问题：四个页面宿主 Activity 均显式声明 `stateHidden|adjustResize`，并在失去窗口焦点切往外部窗口时清理残留的输入框焦点（同时不影响应用内保留键盘的长按消息菜单，也不主动关闭其他小窗的输入法）。点回消息列表不再闪现键盘，直接点击输入框仍可正常弹出键盘连续输入。

## en

Fixed the soft keyboard briefly flashing open and immediately snapping shut on Android when tapping back onto a conversation's message list from a system floating window after the composer had previously been focused: all four page host activities now declare `stateHidden|adjustResize`, and stale composer focus is cleared when losing window focus to an external window without touching another window's IME or disturbing in-app menus that intentionally keep the keyboard open. Returning to the message list no longer flashes the keyboard, while tapping the composer directly still opens it cleanly for typing.

## ja

Android でメッセージ入力欄にフォーカスした後にシステムのフローティングウィンドウへ切り替え、再びメッセージ一覧をタップして戻った際にソフトウェアキーボードが一瞬表示されてすぐ閉じる問題を修正しました。4つのページホスト Activity に `stateHidden|adjustResize` を明示し、外部ウィンドウへフォーカスが移った際に残留した入力フォーカスをクリアするようになりました（キーボードを維持するアプリ内のメッセージ長押しメニューや他ウィンドウの IME には影響しません）。メッセージ一覧に戻ってもキーボードがちらつかず、入力欄を直接タップした場合はそのまま正常に入力できます。
