---
type: fixed
area: auth
platforms:
  - android
  - ios
  - ohos
pull_request: 2168
rollout: full
---

## zh-CN

修复自动登录刷新在特定时序下的竞态：当一次刷新以不确定的结果结束（例如超时或被取消）时，紧随其后的请求可能错误地加入这个已经结束的刷新流程，导致服务器返回的"登录已失效"结论被吞掉，App 该引导重新登录时没有引导。现在刷新流程会完全结束后才向等待中的请求发布结果，登录失效信号按顺序可靠送达。

## en

Fixed a race in automatic session refresh: when a refresh ended ambiguously (for example, on timeout or cancellation), an immediate follow-up request could attach to the already-finished refresh and suppress the server's authoritative "session expired" answer, so the app did not prompt for sign-in when it should have. A refresh now fully retires before its result is published to waiting requests, so sign-in-expiry signals are delivered reliably and in order.
