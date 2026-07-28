---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 0
rollout: full
---

## zh-CN

当话题回复已加载但原消息或所属会话暂时不可用时，回复会继续显示，并以内联提示降级原消息；依赖所属会话的发送、任务等操作会安全禁用，内部存储标识也不会再显示为标题。

## en

When thread replies are loaded but their original message or parent conversation is unavailable, the replies now remain visible with an inline origin fallback. Parent-dependent sending and task actions fail closed, and internal storage identifiers are no longer shown in the header.
