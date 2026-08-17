---
type: changed
area: home
platforms:
  - android
  - ios
  - ohos
rollout: full
---

## zh-CN

把 Settings Home、路由视觉页、两个 TabBar smoke 页的 `@Page` 壳从巨型 `SettingsHomePage.kt` 拆到各自文件。页面内容和视觉 fixture 仍留在原文件，行为不变。

## en

Move the Settings Home, route-visual, and two TabBar smoke `@Page` shells out of the oversized `SettingsHomePage.kt` into their own files. Page content and visual fixtures stay in the original file, with no behavior change.
