---
type: fixed
area: markdown
platforms:
  - android
  - ios
  - ohos
pull_request: 1961
rollout: full
---

## zh-CN

消息里的引用块现在在没有 `>` 标记的下一行结束：先引用再写回复时，回复不再被并进引用条里，与网页端一致。连续 `>` 行仍是同一个引用块，列表换行行为不变。

## en

Block quotes in messages now end at the first line without a `>` marker: when quoting and then replying, the reply is no longer absorbed into the quote bar, matching the web. Consecutive `>` lines remain one quote and list wrapping is unchanged.
