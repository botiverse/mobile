---
type: fixed
area: members
platforms:
  - android
  - ios
  - ohos
pull_request: 2481
rollout: full
---

## zh-CN

一次添加多个成员改为原子提交：要么全部加入成功，要么一个都不加入；不会再出现「加了一半、后面失败」的部分成功状态，也不会有重复成员。

## en

Adding several members at once is now atomic: either everyone is added or no one is — no more partial success where some members join and a later one fails, and no duplicates.
