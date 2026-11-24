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

修复使用中文等输入法输入拼音尚未选字时，`@` 提及与 `#` 频道候选列表会因音节分隔符而消失或无法匹配的问题；现在拼写过程中即可实时筛选成员和频道，点选后会完整替换整段未确认文字。

## en

Fixed `@` mention and `#` channel autocomplete closing or failing to match while typing with CJK keyboards (such as iOS Pinyin and Shuangpin); suggestions now filter live before text is committed and tapping a candidate replaces the entire unfinished input cleanly.

## ja

中国語などのキーボードで変換確定前に `@` メンションや `#` チャンネル候補が閉じたり一致しなくなったりする問題を修正しました。入力中でもリアルタイムに候補が絞り込まれ、選択すると未確定文字全体が置き換わります。
