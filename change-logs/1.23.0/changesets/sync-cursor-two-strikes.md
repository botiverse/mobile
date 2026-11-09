---
type: fixed
area: home
platforms:
  - android
  - ios
  - desktop
pull_request: null
rollout: flag
---

## zh-CN

在开启增量同步的服务器上，服务器短暂出问题（例如更新期间）时，App 不再重新加载全部内容，而是保留同步进度，稍后再试一次，只同步变化的部分。

## en

On servers with incremental sync turned on, a brief server hiccup (for example during an update) no longer makes the app reload everything: it keeps its place, tries again a little later, and syncs only what changed.

## ja

増分同期が有効なサーバーでは、サーバーの一時的な不調（更新中など）があっても、アプリがすべてを読み込み直すことはなくなりました。同期の位置を保ったまま少し後にもう一度試し、変更された分だけを同期します。
