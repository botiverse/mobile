---
type: fixed
area: saved
platforms:
  - android
pull_request: 1523
rollout: release
---

## zh-CN

修复 Saved 列表中取消收藏按钮可能误打开对应对话的问题。点击书签减号现在只会取消当前条目的收藏，并保持列表行的打开和长按操作互不干扰。

## en

Fixed an issue where tapping Unsave in the Saved list could open the conversation instead. The bookmark-minus action now removes only the intended saved item without conflicting with row open or long-press actions.
