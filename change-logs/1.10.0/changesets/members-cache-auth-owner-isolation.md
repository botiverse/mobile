---
type: fixed
area: members
platforms:
  - android
  - ios
  - ohos
pull_request: 2145
rollout: full
---

## zh-CN

修复切换账号或环境后频道成员列表可能短暂显示上一个账号缓存内容的问题：成员缓存现在按环境、账号、server、频道严格分区，退出登录后旧账号的缓存立即失效，迟到的旧会话数据不会再写入当前账号的成员列表。

## en

Fixed channel member lists briefly showing the previous account's cached content after switching accounts or environments: member caches are now strictly partitioned by environment, account, server, and channel. Signing out immediately retires the old account's cache, and late-arriving data from the old session can no longer write into the current account's member list.
