---
type: fixed
area: thread
platforms:
  - android
  - ios
  - ohos
pull_request: 2547
rollout: full
---

## zh-CN

修复从动态（Activity）、通知或搜索定位进入频道后，消息下方的「N 条回复」预览行长时间不出现、看起来像没有回复的问题。此前该预览行要等到账号「关注的全部 thread 列表」下载完成才会绘制，关注得越多、网络越慢，等得越久甚至一直不出现；现在频道自身的回复摘要一到就绘制，关注状态标记随后再补。

## en

Fixed reply-preview rows ("N replies") under messages staying absent, as if a message had no replies, after landing in a channel from Activity, a notification or search. The rows used to wait for the account-wide followed-threads list to download, which on accounts following many threads over a slow link could take long enough to never arrive; channel reply summaries are now drawn as soon as they return, and the followed marker is filled in afterwards.
