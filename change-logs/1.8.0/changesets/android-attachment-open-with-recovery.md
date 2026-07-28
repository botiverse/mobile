---
type: fixed
area: attachments
platforms:
  - android
pull_request: 1686
rollout: full
---

## zh-CN

Android 附件预览中的“打开方式”现在会在临时文件缺失或已被清理时，重新下载当前附件并交给所选应用打开；同名附件和不同账号、服务器之间不会串用文件，显式保存到“下载”目录的行为保持不变。

## en

Android's attachment preview now rematerializes the current attachment when the temporary file for “Open with” is missing or evicted, then hands a readable copy to the selected app. Same-named attachments and different accounts or servers remain isolated, while explicit saves to Downloads are unchanged.
