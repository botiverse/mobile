---
type: changed
area: network
platforms:
  - android
  - ios
  - ohos
pull_request: 2510
rollout: full
---

## zh-CN

普通网络请求（登录、拉列表、常规 GET/POST）的连接超时改为由应用侧统一配置显式下发（10 秒，与此前生效的库默认值一致），并写入网络诊断日志；行为不变，但库升级不再会悄悄改变这个值。

## en

Ordinary network requests (sign-in, list loads, plain GET/POST) now receive their connect timeout explicitly from a single app-side setting (10 s, equal to the previously effective library default) and report it in network diagnostics; behaviour is unchanged, but a library upgrade can no longer move the value unnoticed.
