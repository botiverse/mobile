---
type: added
area: web-container
platforms:
  - android
  - ios
  - desktop
rollout: flag
---

## zh-CN

为新网页容器（`compose/web-container`，挂在 `mobile_web_container_v1` 开关下）补齐跨端崩溃 tracing 与监控上报：渲染进程崩溃/系统回收（含 Android 保活池与 iOS 预热备用实例）、Watchdog 终态异常（崩溃熔断/加载中断/白屏/卡顿）、引擎异步启动耗时与失败以及反僵尸池异常均通过结构化 OTLP Span 上报（脱敏无 URL/PII、带 60 秒滑窗限流）；Android 端容器渲染进程崩溃同步接入现有 `RaftWebViewRendererGone` 单回合聚合与 Hands 非致命工单通道（与原有 WebView 共用去重与配额闸门）。

## en

Added cross-platform crash tracing and monitoring for the new web container (`compose/web-container`, gated behind `mobile_web_container_v1`): renderer process crashes/reclaims (including Android keep-alive and iOS warm-spare instances), watchdog terminal failures (crash circuit breaker, load crashes, blank screen, stalls), async engine startup latency/failures, and anti-zombie pool anomalies now emit structured, sanitized, burst-capped OTLP trace spans; Android container renderer crashes also feed the shared `RaftWebViewRendererGone` single-turn coalescer and Hands non-fatal ticket gate alongside legacy WebViews.

## ja

新しい Web コンテナ（`compose/web-container`、`mobile_web_container_v1` フラグ配下）向けに、クロスプラットフォームのクラッシュトレーシングと監視を追加しました。レンダラープロセスのクラッシュ／回収（Android の keep-alive や iOS ウォーム予備インスタンスを含む）、Watchdog 終端エラー、非同期エンジン起動の遅延・失敗、アンチゾンビプール異常を、URL や個人情報を含まないレート制限付き OTLP スパンとして送信します。Android ではコンテナのレンダラークラッシュを既存の `RaftWebViewRendererGone` 集約および Hands 非致命チケット経路へ統合しました。
