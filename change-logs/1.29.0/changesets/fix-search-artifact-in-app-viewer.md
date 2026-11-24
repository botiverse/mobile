---
type: fixed
area: artifacts
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

修复在「搜索」标签页及其他应用壳子标签页点击 Artifact 搜索结果时，因缺少页面作用域而退回系统浏览器打开的问题；现在会直接打开应用内 Artifact 查看器。

## en

Fixed tapping an Artifact hit in the Search tab (and other app-shell child tabs) falling back to the external browser due to a missing shell store scope; it now opens directly in the in-app Artifact viewer.

## ja

「検索」タブなどのアプリシェル子タブから Artifact の検索結果をタップした際に、スコープ不足で外部ブラウザへフォールバックする不具合を修正し、アプリ内ビューアで直接開くようにしました。
