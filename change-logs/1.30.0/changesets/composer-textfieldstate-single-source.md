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

将聊天与反馈输入框重构为原生单源 `TextFieldState` 并移除受控同步桥和旧版选区补丁，修复在已有文字中间使用输入法（如 iOS 拼音）编辑或选字后光标跳回末尾的问题。

## en

Refactored the chat and feedback composers to a single-source native `TextFieldState` and removed the controlled sync bridge and legacy selection patches, fixing the cursor jumping to the end when typing or committing IME input mid-sentence on iOS.

## ja

チャットおよびフィードバック入力欄を単一ソースのネイティブ `TextFieldState` に刷新し、制御同期ブリッジとレガシー選択パッチを削除しました。これにより、iOS などで文章の途中に IME 入力・変換確定した際にカーソルが末尾へ戻る問題を修正しました。
