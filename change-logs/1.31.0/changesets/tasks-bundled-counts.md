---
type: improved
area: tasks
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

任务页「全部」视图下，「我的」与「全部」两个标签的计数改为一次请求取回（对齐网页），数字出现得更快、请求更少；尚未升级的服务器自动沿用原来的分项计数。

## en

In the Tasks page's All view, the Mine and All tab counts now load in a single request, as the web does, so the numbers appear sooner with fewer requests. Servers that have not been updated yet keep using the previous per-section counts automatically.

## ja

タスクページの「すべて」表示で、「自分」と「すべて」タブの件数を Web と同じく 1 回のリクエストでまとめて取得するようにし、件数がより早く表示されるようにしました。未更新のサーバーでは従来のセクション別の件数取得を自動で使います。
