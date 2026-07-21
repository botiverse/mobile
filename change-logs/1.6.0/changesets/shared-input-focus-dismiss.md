---
type: fixed
area: interaction
platforms:
  - android
  - ios
  - ohos
pull_request: 1362
rollout: full
---

## zh-CN

统一标准输入框的失焦基础设施：页面、认证界面、文件预览和弹窗现在通过输入框底层的焦点 owner 自动在点按空白区域时收起软键盘，新增标准输入框无需再为具体业务页面补点击处理。消息 Composer 继续保留原有的时间线点按收键盘合同。此变更已通过源码与合同测试；iOS 运行时验证尚未执行。

## en

Unified focus dismissal at the standard input layer. Pages, authentication surfaces, file previews, and modals now use input-owned focus state to dismiss the software keyboard from blank-area taps, so new standard inputs no longer need page-specific tap handlers. The message Composer keeps its existing timeline-tap dismissal contract. Source and contract validation passed; iOS runtime validation has not yet been run.
