---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 1221
rollout: full
---

## zh-CN

修复从 Activity 打开回复 Thread 后，发送消息时后台继续预加载多个无关回复 Thread，导致发送确认阶段卡顿的问题。点击 Activity 行后现在只预加载下一条符合条件的回复 Thread，避免与当前会话的前台渲染和实时更新争用资源。

## en

Fixed send-confirmation jank after opening a reply Thread from Activity. The app now preloads only the next eligible reply Thread after a click instead of continuing through multiple unrelated reply Threads in the background, avoiding contention with the active conversation's rendering and realtime updates.
