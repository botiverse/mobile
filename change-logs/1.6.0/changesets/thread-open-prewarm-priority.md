---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 1228
rollout: full
---

## zh-CN

修复从 Activity 打开回复 Thread 时，前台加载可能继续排在后台预加载队列中，导致网络正常但页面仍需额外等待多个无关预加载请求的问题。尚未开始网络请求的后台预加载现在会在用户点开 Thread 时晋升为前台加载；已经开始的请求仍保持单飞复用。

## en

Fixed reply Thread opens inheriting the wait time of the Activity background prewarm queue even when the network itself was responsive. A queued prewarm that has not started its request is now promoted when the user opens the Thread, while an already-started request remains single-flight and is reused.
