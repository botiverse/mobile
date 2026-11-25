---
type: improved
area: reliability
platforms:
  - android
  - ios
  - desktop
rollout: full
---

## zh-CN

将频道成员页与 Agent 动态总览切回数据层自动保鲜：切回前台、断线重连或恢复网络后自动单飞刷新当前可见数据并保留已有内容，不再因页面单独补拉或失败刷新消耗冷却窗口；同时清理同步已上线后的多余设备列表回退请求。

## en

Moved channel members and Agent Activity overview refresh ownership into the data layer so returning to the foreground, reconnecting, or regaining network automatically single-flight refreshes visible data while keeping shown content intact and without burning the coalescing window on failed reads; also removed the redundant `/machines` fallback on sync-enabled servers.

## ja

チャンネルメンバー一覧と Agent アクティビティ概要の自動更新をデータ層へ移行し、フォアグラウンド復帰・再接続・ネットワーク回復時に表示中データのみを単一フライトで更新して既存表示を維持するようにしました（失敗時に再取得クールダウンを消費しない）。同期対応サーバーでの不要な `/machines` フォールバックも整理しています。
