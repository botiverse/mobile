---
type: fixed
area: interaction
platforms:
  - android
  - ios
  - ohos
pull_request: 1277
rollout: full
---

## zh-CN

修复界面更新后复用的输入框、对话框或抽屉偶发仍调用旧事件处理逻辑的问题；聚焦、失焦、关闭与收起操作现在始终作用于最新界面状态。

## en

Fixed reused text fields, dialogs, and drawers occasionally invoking stale event handlers after a UI update. Focus, blur, dismiss, and close actions now always target the latest screen state.
