---
type: changed
area: channels
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

对齐联合频道跨服务器管理权限：外部人员邀请（含待处理邀请管理）与断开其他已连接服务器的操作现直接以服务端返回的 `channelCapabilities.federateChannels` 为准，不再额外要求当前视角为宿主服务器或依赖本地服务器角色，以便在已连接服务器的频道副本上按服务端授权正常显示对应入口。

## en

Aligned joint-channel management gates with `channelCapabilities.federateChannels`: external invites (including open-invite management) and disconnecting another connected server now trust the channel's resolved `federateChannels` capability directly instead of requiring `jointRole == "host"` or checking the viewer's own-server role, so host owners and admins receive the same controls on connected servers' copies once enabled server-side.

## ja

ジョイントチャンネルの管理権限判定を `channelCapabilities.federateChannels` に統一しました。外部招待（保留中招待の管理を含む）と他の接続済みサーバーの切断について、`jointRole == "host"` やローカルサーバーロールによる制限を外し、サーバーが解決した `federateChannels` 権限を直接参照するようにしたため、接続先サーバーのコピーでもサーバー側の権限に応じて管理操作が表示されます。
