---
type: added
area: feedback
platforms:
  - android
  - ios
rollout: flag
pull_request: TBD
---

## zh-CN

在开启 `mobile_screenshot_feedback_v0` 功能开关（且已登录并关闭隐私模式）后，前台系统截屏会立即在底部浮出「遇到问题？反馈」快捷提示条，无需申请相册或存储权限；轻点后在当前页面直接弹出快速反馈面板，自动附带当前页应用内截图预览（可一键移除）、当前页面状态与诊断日志，写一句说明即可原地提交并回到原页面，不丢失浏览位置。

## en

When the `mobile_screenshot_feedback_v0` feature flag is enabled (while signed in with privacy mode off), taking a system screenshot in the foreground surfaces a non-intrusive bottom prompt ("Something wrong? Send feedback") without requiring storage or Photos permissions. Tapping it opens an in-place quick feedback bottom sheet over the current page with a removable in-app screenshot thumbnail, attached page context, and prepared diagnostics logs, letting you submit a short note without leaving the page or losing scroll position.
