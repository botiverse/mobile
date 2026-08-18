---
type: added
area: avatars
platforms:
  - ios
pull_request: 2137
rollout: full
---

## zh-CN

iOS 头像现在使用持久化挂载缓存：重启应用或重新进入页面后，已缓存的头像立即可见，不再先闪占位图。缓存按内容分区并设有容量上限，失败或中断的写入不会留下半成品，退出登录后按账号隔离的缓存随之失效。

## en

Avatars on iOS now use a durable mounted cache: previously cached avatars appear immediately after restarting the app or re-entering a page instead of flashing a placeholder first. The cache is partitioned by content with size limits, failed or interrupted writes never leave partial entries, and account-scoped cache entries are retired on sign-out.
