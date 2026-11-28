---
type: added
area: notifications
platforms:
  - android
  - ios
rollout: full
---

## zh-CN

为移动端推送送达可观测事件（`mobile.push.received`、`mobile.push.presented`、`mobile.push.opened`）接入客户端时钟偏差与往返耗时校准：在请求 `POST /api/servers/:id/scope-attestation` 时记录前后本地时间戳，优先解析响应 JSON 中的 `serverTime`（`clock_source="server_time"`），缺失或无法解析时回退解析 HTTP `Date` 响应头（`clock_source="http_date"`），以 RTT 中点计算 `clock_offset_ms` 与 `clock_rtt_ms` 并随推送事件上报；本地时钟回退或偏差超出 ±24 小时时完整省略三项字段（绝不截断），非推送事件保持不变，鉴权刷新与 5xx 重试均自动使用最新校准值。

## en

Added client-vs-server clock calibration (`clock_offset_ms`, `clock_rtt_ms`, `clock_source`) to mobile push delivery observability events (`mobile.push.received`, `mobile.push.presented`, `mobile.push.opened`): measures the `POST /api/servers/:id/scope-attestation` round trip, prefers JSON `serverTime` (`server_time`) with a multiplatform RFC 7231 HTTP `Date` header fallback (`http_date`), and computes the RTT-midpoint offset `client_mid_ms - server_ms`. Omits all three fields whenever the device wall clock steps backwards or the offset falls outside ±24h (never clamping), leaves non-push telemetry untouched, and refreshes calibration across attestation renewals and 5xx retries without mutating queued records.

## ja

モバイルのプッシュ配信可観測イベント（`mobile.push.received`、`mobile.push.presented`、`mobile.push.opened`）にクライアントとサーバー間の時刻オフセット・RTT 較正（`clock_offset_ms`、`clock_rtt_ms`、`clock_source`）を追加しました。`POST /api/servers/:id/scope-attestation` の往復時間を計測し、レスポンス JSON の `serverTime`（`server_time`）を優先、未提供または解析不能時は HTTP `Date` レスポンスヘッダー（`http_date`）へフォールバックして RTT 中点オフセットを算出します。端末時刻の逆行や ±24 時間超の偏差時は 3 フィールドすべてを省略（クランプなし）し、非プッシュイベントには影響せず、認証更新や 5xx リトライ時も最新の較正値を反映します。
