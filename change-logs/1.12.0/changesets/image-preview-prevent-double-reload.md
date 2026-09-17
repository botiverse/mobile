---
type: fixed
area: attachments
platforms:
  - android
  - ios
  - ohos
pull_request: 2511
rollout: full
---

## zh-CN

修复打开图片大图预览时画面闪烁并重复加载两次的问题：已成功展示的图片在获取到完整位图尺寸后不再重建底层渲染组件，避免不必要的重新挂载；底层同时增加按图片 URL 去重的网络单飞，防止布局测算阶段发起重复的网络下载。

## en

Fixed an issue where opening an image preview would flicker and reload twice: once an image preview has successfully displayed, late updates to its decoded dimensions no longer reconstruct the underlying render component, preventing unnecessary remounts; additionally introduced URL-level network single-flight deduplication to avoid redundant downloads across layout passes.
