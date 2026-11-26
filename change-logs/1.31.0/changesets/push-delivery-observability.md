---
type: added
area: notifications
platforms:
  - android
  - ios
rollout: flag
---

## zh-CN

为移动端（Android 与 iOS）接入端到端推送送达可观测链路（`mobile.push.received` / `mobile.push.presented` / `mobile.push.opened`）：严格遵循服务端采样下发的 `deliveryId`（客户端不二次采样、旧服务端无字段零影响、绝不上报消息正文/会话或发送者标识/设备令牌），通过本地有界持久化缓冲（上限 100 条、48 小时 TTL、退出登录/切换账号/隐私模式立即清空、仅前台回流零后台网络唤醒）复用现有 `RaftClientTraceEvents` 上报通道，覆盖 Android FCM、保活 Socket、前台横幅与系统通知栏点击以及 iOS APNs 前台回调、系统通知点击和前台返回通知中心回填。

## en

Added end-to-end mobile push delivery observability (`mobile.push.received`, `mobile.push.presented`, and `mobile.push.opened`) across Android and iOS: strictly follows server-sampled `deliveryId` UUIDs with zero client re-sampling, full backward compatibility when absent, and no message content, channel/sender IDs, or device tokens; persists events in a bounded local buffer (max 100 entries, 48h TTL, cleared on sign-out, account switch, or privacy mode) that drains only in the foreground through `RaftClientTraceEvents` with zero background network wakeups; wires Android FCM, keep-alive Socket, foreground in-app banners, and system-tray opens alongside iOS APNs `willPresent`, notification opens, foreground Notification Center backfill, and Socket banners.

## ja

モバイル（Android・iOS）向けにエンドツーエンドのプッシュ配信可観測性（`mobile.push.received` / `mobile.push.presented` / `mobile.push.opened`）を追加しました。サーバー側でサンプリングされた `deliveryId` のみを記録（クライアント再サンプリングなし、未付与サーバーへの完全な後方互換、メッセージ本文・チャンネル/送信者 ID・デバイストークン非含有）し、上限 100 件・48 時間 TTL のローカル永続バッファ（サインアウト・アカウント切替・プライバシーモード時に即時消去）からフォアグラウンド時のみ既存の `RaftClientTraceEvents` 経路で送信します。Android の FCM・キープアライブ Socket・アプリ内バナー・通知トレイタップ、および iOS の APNs `willPresent`・通知タップ・フォアグラウンド復帰時の通知センター補完に対応しました。
