---
type: fixed
area: performance
platforms: [android, ios, desktop]
pull_request: 5230
rollout: full
---

## zh-CN
修复从后台切回 Raft 时主线程被多项系统查询和重复状态刷新拖慢导致的开屏与起滑卡顿：安卓端将小组件、通知权限、未读分组摘要与 Play 服务检查移至首帧绘制后在后台线程执行，同时合并前台对账与断线重连短时间内的重复 Agent 与计算机列表刷新。

## en
Fix resume and opening-scroll jank when returning to Raft from the background by deferring non-first-frame Android resume IPCs (widgets, Play services/battery checks, notification permission and unread group summaries) until after the first frame off the main thread, and coalescing duplicate Agent and Computer status refreshes across foreground reconciliation and reconnect.

## ja
バックグラウンドから Raft に戻った際の初回描画とスクロール開始時のカクつきを修正しました。Android の復帰時処理（ウィジェット更新、Play サービス・バッテリー確認、通知権限および未読グループ要約の更新）を初回フレーム描画後にバックグラウンドスレッドで実行するよう変更し、フォアグラウンド同期と再接続の短時間内で重複していた Agent・コンピューター一覧の取得を統合しました。
