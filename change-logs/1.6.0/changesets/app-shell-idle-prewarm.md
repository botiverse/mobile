---
type: improved
area: app-shell
platforms:
  - android
  - ios
  - ohos
pull_request: 1232
rollout: canary
---

## zh-CN

改进底部导航首次切换的流畅度与后台资源调度：子页面控制器预热现在只会在连续稳定帧、Kuikly 上下文队列和原生 UI 队列都空闲时逐个执行；前台交互会撤销尚未提交的预热。OHOS 保留完整预热，Android 与 iOS 先以单控制器 canary 验证 CPU、帧耗时和内存收益。

## en

Improved first-switch smoothness and background resource scheduling for bottom navigation. Child-page controller prewarm now runs one at a time only after stable frames and both the Kuikly context queue and native UI queue are idle, while foreground interaction revokes uncommitted work. OHOS keeps the full measured prewarm set, with Android and iOS starting as a one-controller canary for CPU, frame-time, and memory validation.
