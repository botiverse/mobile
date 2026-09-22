---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 2548
rollout: full
---

## zh-CN

修复进入频道或私信时，输入框先不显示、过一拍才出现的闪动。此前有缓存内容的会话要等首次网络刷新完成才承认「你可以在这里发言」，输入框随之延后出现；现在会话目录里的成员与权限信息一到就挂上输入框（这与内容为空时的行为一致），若服务端随后判定无权限，输入框仍会按原有规则关闭。

## en

Fixed the composer flashing on thread entry: it first stayed hidden and then popped in a beat later. Conversations with cached content used to wait for the first network refresh before accepting "you can write here", delaying the composer; the directory's membership and permission answer now mounts it as soon as it arrives, matching the existing behaviour for empty conversations. A later server no-permission verdict still closes it as before.
