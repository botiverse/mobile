---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

修复当线程根消息来自 Slack 桥接时，打开线程详情或查看回复列表报错无法正常加载的问题。放行线程消息作者外部身份（`MemberKind.External`）投影与外部作者信息归属，同时在频道成员操作与任务指派上保持严格防御隔离。

## en

Fix thread details and reply list failing to load when the thread root message originates from a Slack bridge. Permits external message author projection (`MemberKind.External`) and preserves external author attribution while strictly keeping channel membership operations and task assignee wire parsing guarded.
