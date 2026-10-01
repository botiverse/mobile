---
type: fixed
area: members
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

修复成员页 Agent 电脑分组标题点击无反应的问题：上一版把折叠状态写反了，点按始终写入旧值导致行无法折叠或展开。现在点按能正常切换收起/展开，并继续按「当前用户 + 电脑 id」持久化。

## en

Fixed the members page machine group row not responding to taps: the previous version wrote the collapsed flag back to its old value, so the row could neither collapse nor expand. Tapping now toggles correctly and still persists per user+machine.
