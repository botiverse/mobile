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

把首页搜索、收藏、Activity 三个独立页面从巨型 `SettingsHomePage.kt` 拆到各自文件，三页仍共用同一个 router，行为不变。

## en

Move the standalone Home Search, Saved, and Activity pages out of the oversized `SettingsHomePage.kt` into their own files. The three pages still share one router, with no behavior change.
