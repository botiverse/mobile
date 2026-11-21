---
type: fixed
area: performance
platforms: [android, ios]
pull_request: 0
rollout: full
---

## zh-CN

优化冷启动主线程耗时，减少启动阶段的同步磁盘读写与系统 IPC：安卓端将诊断日志目录初始化与旧日志清理、通知渠道创建、FCM 推送注册、通知权限上报、无 Play 服务保活探测与外部分享待恢复文件检查移至后台线程或共享缓存，合并重复的 Play 服务检查，仅在需要时查询电池优化状态，并在深浅色模式与系统已持久化配置一致时跳过冷启动冗余 `UiModeManager.setApplicationNightMode` 调用；iOS 端为 `KuiklyFont` 的 `NSBundle.pathForResource` 字体路径查询增加线程安全内存缓存。

## en

Reduce cold-start main-thread disk I/O and Binder IPC latency on Android and iOS: move diagnostics log directory setup/pruning, notification channel creation, FCM push registration, notification permission reporting, no-Play-services fallback checks, and durable external-share pending-file checks off the main thread onto background/serial executors, deduplicate Google Play services availability checks per process, query battery optimization exemption only when the no-Play-services fallback applies or is observed, skip redundant API 31+ `UiModeManager.setApplicationNightMode` calls when the system-persisted night mode already matches the stored preference, and add a thread-safe in-memory cache for iOS `KuiklyFont` `NSBundle.pathForResource` lookups.

## ja

コールドスタート時のメインスレッド負荷を軽減するため、起動時の同期ディスク I/O とシステム IPC を削減しました。Android では診断ログディレクトリの初期化・古いログの整理、通知チャンネル作成、FCM プッシュ登録、通知権限の確認、Play サービス非搭載端末向けフォールバック判定、外部共有の保留ファイル確認をバックグラウンドスレッド／キャッシュへ移行し、Google Play 開発者サービスの重複チェックを統合しました。またバッテリー最適化の確認は必要な場合にのみ行い、システムに保存済みのダークモード設定と一致する場合は `UiModeManager.setApplicationNightMode` の冗長な呼び出しをスキップします。iOS では `KuiklyFont` の `NSBundle.pathForResource` 参照にスレッドセーフなメモリキャッシュを追加しました。
