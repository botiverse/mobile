---
type: fixed
area: messages
platforms:
  - android
  - ios
pull_request: 3578
rollout: full
---

## zh-CN

网络断开重连或 App 回到前台后，会补齐期间漏掉的消息，主页、动态和打开着的页面会及时更新（安卓之前在自动重连后可能漏消息）。

## en

After the connection drops and comes back, or the app returns to the foreground, messages sent in the meantime are caught up and Home, Activity and open pages update (on Android, messages could previously be missed after an automatic reconnect).
