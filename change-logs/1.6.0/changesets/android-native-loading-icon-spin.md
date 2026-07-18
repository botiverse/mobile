---
type: improved
area: performance
platforms:
  - android
pull_request: 1240
rollout: full
---

## zh-CN

降低 Android 加载动画的渲染开销：加载图标改由原生动画驱动，不再为每一帧重复重组页面，在消息、成员与设置加载场景中更流畅。

## en

Reduced Android loading-animation overhead by moving spinner rotation to the native view layer instead of recomposing the page every frame, improving smoothness in conversations, members, and settings loading states.
