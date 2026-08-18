---
type: fixed
area: composer
platforms:
  - android
pull_request: 2170
rollout: full
---

## zh-CN

修复 Android 输入框的一个光标问题：当 @ 或 # 候选列表在输入过程中弹出时，光标不再被重置到开头，当前选中的文本范围保持不变；回车发送和换行行为均不受影响。

## en

Fixed an Android composer cursor issue: when the @ or # suggestion panel appears while typing, the cursor is no longer reset to the start of the field and the current text selection is preserved. Enter-to-send and newline behavior are unchanged.
