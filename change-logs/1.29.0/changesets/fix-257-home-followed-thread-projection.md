---
type: fixed
area: home
platforms:
  - android
  - ios
  - desktop
pull_request: 5503
rollout: full
---

## zh-CN

修复首页关注话题未读刷新与侧栏话题同步问题：刚读过的话题不再因服务端汇总短暂延迟而重新闪回未读，且大屏侧边栏打开的话题收到新回复时不再误计未读。

## en

Fixed Home followed-thread unread refreshes so a thread you just read is no longer briefly resurrected by a lagging unread summary, and replies to a thread open in the wide side pane are no longer counted as unread on Home.

## ja

ホームのフォロー中スレッド未読更新を修正しました。直前に既読にしたスレッドがサーバー集計の遅延で一時的に未読へ戻る現象を防ぎ、ワイド画面のサイドペインで開いているスレッドへの返信が未読としてカウントされないようにしました。
