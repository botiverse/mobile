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

把选服务器、App Shell、App Shell 子 Tab 三个页面从巨型 `SettingsHomePage.kt` 拆到各自文件。共用门页和 App Shell ReadyContent 仍留在原文件，行为不变。

## en

Move the choose-server, App Shell, and App Shell child-tab pages out of the oversized `SettingsHomePage.kt` into their own files. Shared gate content and App Shell ReadyContent stay in the original file, with no behavior change.
