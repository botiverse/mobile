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

在开启增量同步的服务器上，访客重新连接后不再每次都重新加载全部内容，只同步离开期间的变化。所有人：如果某次更新后首页、动态或频道成员没能加载出来，App 下次同步时会再加载一次，不会一直显示旧内容。

## en

On servers with incremental sync turned on, guests no longer reload everything each time the app reconnects: only what changed while they were away is synced. For everyone: if Home, Activity or a channel's member list failed to load after an update, the app now loads it again on the next sync instead of showing old content.

## ja

増分同期が有効なサーバーでは、ゲストが再接続するたびにすべてを読み込み直すことはなくなり、離れていた間の変更だけを同期するようになりました。すべてのユーザー：更新後にホーム、アクティビティ、チャンネルのメンバー一覧を読み込めなかった場合、古い内容のままにせず、次の同期で読み込み直すようになりました。
