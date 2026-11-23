---
type: fixed
area: tasks
platforms: [android, ios]
pull_request: 5371
rollout: full
---

## zh-CN

优化任务页计数刷新：切离任务页时暂停后台拉取、返回时补拉，并在前台保持 10 秒最小刷新间隔与并发单飞去重。

## en

Pause Tasks tab count refreshes when off-screen, refetch on return, enforce a 10-second minimum refresh interval, and coalesce concurrent count requests.
