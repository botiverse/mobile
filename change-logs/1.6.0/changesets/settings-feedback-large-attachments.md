---
type: fixed
area: settings
platforms:
  - android
pull_request: 1290
rollout: canary
---

## zh-CN

修复 Android 设置反馈误拒绝大附件的问题：视频和文件现在会按 Hands 支持的方式上传，单个附件最高 200 MB；真正超限时会显示当前平台的实际上限，而不是误报为附件读取失败。

## en

Fixed Android Settings feedback rejecting large attachments before upload. Videos and files now use the upload path supported by Hands up to 200 MB per attachment, and genuine size failures show the platform's actual limit instead of an attachment read error.
