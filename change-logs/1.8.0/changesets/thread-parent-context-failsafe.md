---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 1705
rollout: full
---

## zh-CN

从动态页打开话题回复时会保留成员可见的所属会话，不再被内部目录标识改写。当回复已加载但原消息或所属会话暂时不可用时，回复会继续显示，并以内联提示降级原消息；依赖所属会话的发送、任务等操作会安全禁用，内部存储标识也不会再显示为标题或动态条目。

## en

Opening a reply thread from Activity now preserves its member-visible parent instead of replacing it with an internal directory identity. When replies are loaded but their original message or parent conversation is unavailable, they remain visible with an inline origin fallback. Parent-dependent sending and task actions fail closed, and internal storage identifiers are no longer shown in the header or Activity list.
