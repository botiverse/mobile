---
type: added
area: diagnostics
platforms:
  - ios
rollout: full
---

## zh-CN

在 iOS 端补齐基于 `CADisplayLink` 的掉帧与卡顿诊断追踪（`SlockPerf`），自适应 60Hz / 120Hz ProMotion 刷新率预算，并在退后台或非活跃状态时自动暂停、恢复首帧去噪，将卡顿帧、冻结帧与聚合窗口摘要写入本地诊断日志，随 Hands 反馈与崩溃诊断包一并上报，对齐 Android `FrameMetrics` 性能追踪能力。

## en

Added `CADisplayLink`-based frame jank and frozen-frame diagnostics (`SlockPerf`) on iOS with adaptive 60Hz/120Hz ProMotion frame-budget tracking, route/operation attribution, rate-limited severe-jank and frozen-frame records, rolling window summaries, and background/inactive pause with resume-spike suppression—bringing iOS diagnostic bundles in line with Android `FrameMetrics` jank tracing.

## ja

iOS に `CADisplayLink` ベースのフレーム落ち・フリーズ診断トレース（`SlockPerf`）を追加しました。60Hz / 120Hz ProMotion の可変リフレッシュレートに対応したフレーム予算計算、画面・操作コンテキストの付与、レート制限付きの重大ジャンク／フリーズ記録とローリング集計サマリー、バックグラウンド／非アクティブ時の自動停止と復帰初回フレームのノイズ除外を備え、Android の `FrameMetrics` 診断と同等の情報を Hands 診断パッケージに含められるようにしました。
