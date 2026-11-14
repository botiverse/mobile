---
type: fixed
area: composer
platforms:
  - ios
pull_request: 4779
rollout: full
---

## zh-CN

在 iPhone 和 iPad 上，剪贴板里只有图片时，长按输入框的菜单里又会出现「粘贴」，按 ⌘V 也能粘贴了，图片会像之前一样在图片编辑器里打开。以前输入框获得焦点后，如果它的输入状态在内部重新开始（使用硬件键盘时更常见），菜单里只剩「选择」和「全选」，⌘V 也没有反应。

## en

On iPhone and iPad, when the clipboard holds only images, a long press in the message box shows Paste again and ⌘V pastes again; the images open in the image editor, as before. Before, once the message box had restarted its text input after getting focus (more common with a hardware keyboard), the menu showed only Select and Select All, and ⌘V did nothing.

## ja

iPhone と iPad で、クリップボードに画像だけがあるとき、入力欄を長押しして出るメニューに「ペースト」が再び表示され、⌘V でも貼り付けられるようになりました。画像はこれまでどおり画像エディタで開きます。これまでは、入力欄にフォーカスが移ったあとで入力の状態が内部で開始し直されると（ハードウェアキーボード使用時に起きやすい）、メニューには「選択」と「すべてを選択」しか表示されず、⌘V も反応しませんでした。
