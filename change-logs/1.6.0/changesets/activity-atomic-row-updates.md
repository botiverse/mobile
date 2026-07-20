---
type: fixed
area: activity
platforms:
  - android
  - ios
  - ohos
pull_request: 1346
rollout: full
---

## zh-CN

修复 Activity 收到实时消息、回复或已读更新时，条目可能先显示错误的未读状态、突然消失或跳到其他位置，以及快速点击时偶发打开相邻条目的问题。现有条目会在原位置一次性更新，新增和重排内容会等到用户确认后再展示。

## en

Fixed Activity rows briefly showing the wrong unread state, disappearing, or jumping to another position during live message, reply, and read-state updates, which could also make a rapid tap open a neighboring row. Existing rows now update atomically in place, while new or reordered content waits for user confirmation before being revealed.
