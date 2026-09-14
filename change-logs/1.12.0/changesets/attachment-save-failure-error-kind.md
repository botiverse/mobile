---
type: fixed
area: attachments
platforms:
  - android
  - ios
  - ohos
pull_request: 2467
rollout: full
---

## zh-CN

保存附件失败时，现在会区分“下载失败”和“无法写入存储”两种原因，并显示对应的错误提示，而不是一律显示同一条模糊的保存失败信息。

## en

When saving an attachment fails, Raft now distinguishes between a download failure and a storage write failure and shows the matching error message instead of a single generic save-failed message.
