---
type: fixed
area: thread
platforms:
  - android
pull_request: 1532
rollout: release
---

## zh-CN

修复应用长时间处于后台后，返回并反复打开同一 Reply Thread 仍持续加载的问题。Android 进程前后台切换现在会结束并取消旧代网络请求，未确认结果的写请求不会自动重放；同一身份下重新进入 Thread 会复用尚未结束的访问校验，短期失败则保持隐藏缓存内容并提供一次明确的重试。

## en

Fixed Reply Threads remaining stuck in loading after returning from a long Android background period and reopening the same thread. Process visibility changes now retire and cancel requests from the previous network generation without replaying writes whose outcome is unknown. Re-entry under the same identity joins the unresolved access check, while brief failures keep cached content hidden and offer one explicit retry.
