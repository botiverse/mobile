---
type: fixed
area: channels
platforms: [android, ios, desktop]
rollout: full
---

## zh-CN
频道成员权限现改为由独立的频道查看者权限存储统一维护，仅从带个人权限的频道接口写入，并订阅 `channel:authority-updated` 实时事件按版本增量刷新。修复了联合频道中连接服务器所有者在收到广播更新后丢失“添加成员”等权限的问题，同时在权限加载完成前保持默认收敛，不再错误回退为本服务器角色权限。

## en
Channel viewer permissions now live in a dedicated per-viewer channel authority store written strictly by per-viewer channel endpoints and refreshed live via `channel:authority-updated` events. This fixes connected-server owners losing actions such as Add Member in joint channels after channel broadcasts, and ensures channel controls fail closed while loading instead of falling back to own-server role permissions.

## ja
チャンネルごとの閲覧者権限を専用の権限ストアで管理し、閲覧者向けチャンネル API の応答のみから更新するとともに、`channel:authority-updated` リアルタイムイベントで最新バージョンへ自動再取得するようにしました。これにより、ジョイントチャンネルで接続先サーバーのオーナーがブロードキャスト後に「メンバーを追加」などの操作を失う問題を修正し、読み込み中は自サーバーのロール権限へフォールバックせず安全側に倒れるようになりました。
