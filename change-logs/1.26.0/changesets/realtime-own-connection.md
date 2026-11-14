---
type: changed
area: realtime
platforms:
  - android
  - ios
pull_request: PRNUM
rollout: full
---

## zh-CN

实时连接（新消息、状态变化等即时更新）在 Android、iPhone 和 iPad 上统一改用应用自带的连接实现，不再使用第三方 Socket.IO 库。断网恢复后会自动重连并补齐期间错过的消息。「设置 › 开发者」里的「新的实时连接」开关已移除。

## en

Realtime updates (new messages, status changes and other live updates) now use the app's own connection on Android, iPhone and iPad instead of a third-party Socket.IO library. After a network drop it reconnects on its own and catches up on the messages you missed. The "New realtime connection" switch in Settings › Developer is gone.

## ja

リアルタイム接続（新着メッセージやステータスの変化などの即時更新）は、Android・iPhone・iPad でサードパーティの Socket.IO ライブラリではなくアプリ独自の接続を使うようになりました。ネットワークが切れても自動で再接続し、その間に届いたメッセージを取り込みます。「設定 › 開発者」の「新しいリアルタイム接続」スイッチは削除しました。
