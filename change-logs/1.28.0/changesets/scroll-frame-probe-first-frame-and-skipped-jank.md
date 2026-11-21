---
type: fixed
area: diagnostics
platforms:
  - android
pull_request: 5224
rollout: full
---

## zh-CN

（内部诊断）优化 Android 滚动帧率探针（`scroll_frames`）：在 1 秒节流窗口内被跳过的快速连续滑动，现在会把累计掉帧数与最长帧耗时（`skippedJankFrames`、`skippedLongestFrameMs`）合并带到下一次上报（页面退后台时若有未上报掉帧也会补发），不再丢失短间隔内的卡顿；同时补测每次起滑的首帧延迟（`firstFrameMs`），起滑首帧卡顿和短滑动掉帧都会被准确计入。

## en

(Internal diagnostics) Improved the Android scroll frame probe (`scroll_frames`): rapid consecutive scroll sessions skipped by the 1-second throttle now carry their total jank frames and worst frame gap (`skippedJankFrames`, `skippedLongestFrameMs`) onto the next emitted line (and flush on pause when any skipped session dropped frames), instead of discarding them. Opening-frame scroll latency (`firstFrameMs`) is now measured too, so scroll-start stalls and short janky scrolls are captured accurately.

## ja

（内部診断）Android のスクロールフレーム計測（`scroll_frames`）を改善しました。1 秒間の間引き間隔内でスキップされた連続スクロールについて、合計ドロップフレーム数と最長フレーム間隔（`skippedJankFrames`、`skippedLongestFrameMs`）を次の出力に引き継ぎ（一時停止時に未報告のコマ落ちがあれば出力）、短間隔での引っかかりを取りこぼさないようにしました。また、スクロール開始時の初回フレーム遅延（`firstFrameMs`）も計測対象に含め、開始直後の引っかかりや短いスクロールでのコマ落ちも正確に記録します。
