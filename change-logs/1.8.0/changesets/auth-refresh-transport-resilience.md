---
type: fixed
area: auth
platforms:
  - android
  - ios
  - ohos
pull_request: 1676
rollout: full
---

## zh-CN

网络切换或短暂中断导致会话刷新失败时，Raft 不再误判为登录已过期；只有服务端明确拒绝会话时才会要求重新登录。重新登录后，待恢复的页面也会按当前账号隔离，避免切换账号时打开上一个账号的页面。

## en

Raft no longer treats a session refresh interrupted by a network switch or temporary connection failure as an expired sign-in. Reauthentication is now required only when the server explicitly rejects the session, and pending navigation is isolated to the current account after signing in again.
