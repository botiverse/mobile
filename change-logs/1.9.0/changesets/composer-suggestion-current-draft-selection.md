---
type: fixed
area: composer
platforms:
  - android
  - ios
  - ohos
pull_request: 1843
rollout: full
---

## zh-CN

修复从空输入框开始输入 `@` 或 `#` 后，点击候选偶发无法写入输入框的问题；成员和频道候选现在只会应用到当前文字与光标范围，过期候选会安全忽略。

## en

Fixed member or channel suggestions occasionally failing to update a composer that started empty. `@` and `#` candidates now apply only to the current text and selection, while stale candidates are safely ignored.
