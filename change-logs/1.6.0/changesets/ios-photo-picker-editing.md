---
type: fixed
area: composer
platforms:
  - ios
pull_request: 1259
rollout: full
---

## zh-CN

修复 iOS 从系统相册选择图片后偶发无法进入标注编辑或上传的问题；选中的临时文件现在会在系统回收前可靠缓存。

## en

Fixed iOS photos occasionally failing to enter annotation/editing or upload after selection by reliably caching the picker file before the system reclaims its temporary URL.
