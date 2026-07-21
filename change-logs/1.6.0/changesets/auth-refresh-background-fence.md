---
type: fixed
area: auth
platforms:
  - android
pull_request: 1361
rollout: full
---

## zh-CN

修复应用进入后台后实时连接仍可能触发认证刷新并导致登录态丢失的问题；后台未启用保活时不再发起实时重连或认证刷新，同时避免旧连接错误刷新或覆盖已经轮换的登录凭据，并防止无响应的刷新请求被误判为登录失效而清除会话。内嵌设置网页也不再持有原生刷新凭据，避免网页与原生客户端竞争轮换同一会话。

## en

Fixed a case where realtime reconnects could refresh authentication and lose the signed-in session after the app entered the background. Realtime reconnects and auth refreshes now stop without background keepalive, stale socket failures cannot rotate or overwrite newer credentials, and no-response refresh attempts no longer clear the session as though authentication had definitively expired. Embedded Settings web pages also no longer own the native refresh credential, preventing Web and native code from competing to rotate the same session.
