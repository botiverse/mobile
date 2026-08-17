---
type: fixed
area: home
platforms:
  - android
  - ios
  - ohos
pull_request: 2165
rollout: full
---

## zh-CN

服务器切换器暂时拿不到精确的 Activity 未读数时，会恢复显示原有的未读数字，不再退化成容易误解的灰点；精确数可用时仍以精确数为准。

## en

When an exact Activity unread count is temporarily unavailable, the server switcher now keeps showing the previous unread number instead of an ambiguous gray dot, while continuing to prefer the exact count whenever it is available.
