---
type: added
area: notifications
platforms: [android, ios, desktop]
---

## zh-CN
为服务器所有者和管理员接入运行时账号额度耗尽通知卡片（`GET /api/servers/:id/system-notifications-v2`）：在通知中心展示受影响的 Computer、Agent 数量和预计恢复时间，支持点击直达对应 Computer 页面，并在跨设备间同步已读与关闭状态。

## en
Added owner/admin runtime-account usage-limit attention cards in the Notification Center (`GET /api/servers/:id/system-notifications-v2`): shows affected Computers, agent counts, and relative reset times, opens the corresponding Computer page on tap, and syncs receiver-scoped seen and dismiss states across devices.

## ja
サーバーのオーナーおよび管理者向けに、ランタイムアカウントの上限到達通知カード（`GET /api/servers/:id/system-notifications-v2`）を通知センターに追加しました。影響を受ける Computer・エージェント数・回復までの目安時間を表示し、タップで該当の Computer ページを開けるほか、既読・非表示状態をデバイス間で同期します。
