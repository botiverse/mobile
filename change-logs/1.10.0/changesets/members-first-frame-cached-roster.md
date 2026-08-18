---
type: fixed
area: members
platforms:
  - android
  - ios
  - ohos
pull_request: 2130
rollout: full
---

## zh-CN

修复频道"成员"页打开时首帧短暂显示"成员 (0) / 暂无成员"假空态的问题：现在首帧直接展示本机已缓存的成员名单，后台刷新期间和刷新失败时都保留已显示的名单，不再闪空。添加成员时只有正在提交的那一行显示进行中状态，其余行保持正常可操作；未确认拥有权限时也不再错误开放"添加成员"入口。

## en

Fixed the Channel Members page briefly showing a false empty state ("Members (0) / no members yet") on first frame: the page now renders the locally cached roster immediately and keeps it during background refreshes and after refresh failures instead of flashing empty. When adding a member, only the row being submitted shows an in-progress state while the other rows stay normal and usable, and the Add entry is no longer offered before permission is confirmed.
