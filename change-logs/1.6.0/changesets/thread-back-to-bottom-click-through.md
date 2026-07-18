---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 1198
rollout: full
---

## zh-CN

修复对话流「回到底部」按钮的点击穿透：点击该按钮不再误触发下层消息的点击行为。

## en

Fixed the conversation "back to bottom" button leaking taps through to the message underneath: tapping it no longer triggers the message below.
