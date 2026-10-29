---
type: changed
area: navigation
platforms:
  - android
pull_request: 3940
rollout: full
---

## zh-CN

在 Android 上，切换底部标签（主页、动态、任务、搜索）时，当前页面会先淡出，新页面随后淡入并轻微放大，不再瞬间跳换。每个标签的滚动位置和已加载的内容都会保留，切换不会重新加载。系统开启"移除动画"时仍立即切换。

## en

On Android, switching the bottom tabs (Home, Activity, Tasks, Search) now fades the current page out and the new one in with a slight grow, instead of jumping at once. Each tab keeps its scroll position and loaded content, and switching does not reload it. With the system's Remove animations setting on, tabs still switch at once.

## ja

Android で、下部タブ（ホーム、アクティビティ、タスク、検索）を切り替えると、今のページがフェードアウトし、新しいページが少し拡大しながらフェードインするようになりました（これまでは瞬時に切り替わっていました）。各タブのスクロール位置と読み込み済みの内容は保たれ、切り替えで再読み込みされることはありません。システムの「アニメーションを削除」がオンのときは、これまでどおり瞬時に切り替わります。
