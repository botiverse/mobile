---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 2555
rollout: full
---

## zh-CN

修复私信内引用当前用户自身的 DM 标签（如 `dm:@user:shortId`）点击无响应的问题：在解析私信标签会话时增加当前用户视角对齐，处于私信会话或私信回复线程时直接对齐当前私信流通道，频道消息中则根据原消息作者对齐私信目标，并防御性补齐富文本上下文投影的空列表保护。

## en

Fixed an issue where tapping a self-addressed Direct Message thread tag (such as `dm:@user:shortId`) inside a conversation had no response: enhanced DM conversation resolution to align with the viewer's perspective, mapping to the active DM channel when viewing a DM or DM reply thread and resolving via the referencing message author in channel contexts, while guarding DM peer name sets from empty patch projections.
