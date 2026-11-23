---
type: improved
area: thread
platforms:
  - android
  - ios
  - desktop
rollout: flag
---

## zh-CN

引用回复在消息气泡与输入框中统一显示为单行标签（含发送者头像、姓名与摘录，支持多条引用编排、单条删除、`×` 与下方回复按钮，空输入首次按 Backspace 预选、再次按 Backspace 删除），通过 `quote_reply_v0` 灰度开关控制。

## en

Quote replies now render as compact one-line chips in both message rows and the composer (showing the author's avatar, name, and single-line excerpt), with multi-quote composer support (`×` to remove, Reply below to interleave between stacked quotes, and two-step Backspace arm-then-remove), gated behind `quote_reply_v0`.
