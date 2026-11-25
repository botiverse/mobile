---
type: fixed
area: composer
platforms:
  - android
rollout: full
---

## zh-CN

修复了在 Android 上曾聚焦过会话输入框后，切到系统小窗再点回消息列表时软键盘会瞬间弹出又立刻收起闪烁的问题：四个页面宿主 Activity 均显式声明 `stateHidden|adjustResize`，同时将窗口焦点、编辑器焦点/光标与平台输入法会话解耦——当宿主窗口因系统小窗等外部窗口失去焦点时，仅挂起平台输入法会话（不清空草稿、光标与编辑器焦点，也不影响应用内保留键盘的长按消息菜单，同时保持无障碍与外接键盘中文输入正常建立输入法会话）；切回后点消息列表不再闪现键盘，直接点击输入框即可恢复输入法会话并保留原草稿与光标连续输入。

## en

Fixed the soft keyboard briefly flashing open and immediately snapping shut on Android when tapping back onto a conversation's message list from a system floating window after the composer had previously been focused: all four page host activities declare `stateHidden|adjustResize`, and window focus, editor focus/cursor, and the platform IME session are now decoupled—losing host window focus to an external window (such as a floating window or notification shade) parks only the platform IME session while preserving composer focus, draft text, cursor position, in-app keyboard-retaining menus, accessibility focus, and hardware-keyboard IME input. Returning to the message list no longer flashes the keyboard, while tapping the composer directly wakes the session cleanly for continuous typing.

## ja

Android でメッセージ入力欄にフォーカスした後にシステムのフローティングウィンドウへ切り替え、再びメッセージ一覧をタップして戻った際にソフトウェアキーボードが一瞬表示されてすぐ閉じる問題を修正しました。4つのページホスト Activity に `stateHidden|adjustResize` を明示したうえで、ウィンドウフォーカス・エディタのフォーカス／カーソル・プラットフォーム IME セッションを分離し、外部ウィンドウへの切替時はプラットフォーム IME セッションのみを待機させて下書きやカーソル位置を保持しつつ（アクセシビリティフォーカスや外付けキーボードの日本語・中国語 IME 入力も維持）、メッセージ一覧へ戻った際のちらつきを防ぎ、入力欄をタップしたときにそのまま連続入力を再開できるようにしました。
