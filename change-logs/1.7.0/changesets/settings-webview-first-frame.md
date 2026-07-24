---
type: fixed
area: settings
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

修复从设置打开内嵌网页时偶发先显示白屏、顶部栏消失后重现的问题。Computers、套餐与账单、Administration、Connected Apps / Marketplace 和更新日志现在共用同一套网页首帧、加载遮罩与错误恢复逻辑；网页内部打开详情后，返回操作也会优先回到上一层网页，而不是直接退出设置页面。

## en

Fixed embedded Settings pages occasionally showing a blank white frame and remounting the header while opening. Computers, Plan & Billing, Administration, Connected Apps / Marketplace, and Release Notes now share one first-frame, loading-cover, and error-recovery implementation. Back actions also return through in-page Web history before closing the Settings page.
