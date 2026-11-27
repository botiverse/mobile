package build.raft.app.core.data.diagnostics

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Display
import androidx.annotation.RequiresApi
import build.raft.app.RaftScrollFrameSignal
import build.raft.app.data.RaftFrameMetricsTraceReporter
import java.lang.ref.WeakReference

/**
 * One warn line per scroll session (a list's `isScrollInProgress` true -> false, fling included)
 * summarising vsync cadence while it scrolled: scroll-start first-frame latency, frames, late
 * frames (missed at least one vsync), longest and p95 frame gap, and the display refresh rate at
 * start and end (90 <-> 60 Hz switches show up as `vsyncMs` != `vsyncEndMs`). [RaftResumeFrameProbe]
 * only covers the seconds after a resume, so a "scrolling drops frames" report carried no scroll
 * frame evidence.
 *
 * Cost: one Choreographer callback per vsync only while a list is scrolling; no allocation per
 * frame; at most one line per [MinLineIntervalMillis]. Skipped sessions carry their count, total
 * jank frames, and worst frame gap onto the next emitted line (and flush on pause when they saw
 * jank) so rapid flicks never hide a stall.
 */
object RaftScrollFrameProbe {
    private const val Tag = "SlockScrollFrames"
    private const val MinFrames = 5
    private const val MinLineIntervalMillis = 1_000L
    private const val DefaultRefreshRateHz = 60f

    // Main-thread only.
    private var activityRef: WeakReference<Activity>? = null
    private var pageName: String = ""
    private var active: ActiveProbe? = null
    private val throttle = RaftScrollFrameThrottle(MinLineIntervalMillis)

    fun onActivityResumed(activity: Activity, pageName: String) {
        activityRef = WeakReference(activity)
        this.pageName = pageName
        RaftScrollFrameSignal.sink = ::onScrollChanged
    }

    fun onActivityPaused(activity: Activity) {
        if (activityRef?.get() !== activity) return
        active?.finish(reason = "paused")
        throttle.flushAndResetOnPause()?.let { pending ->
            RaftAppLog.w(
                Tag,
                raftScrollFramesSkippedFlushLine(
                    pageName = pageName,
                    skipped = pending,
                )
            )
            RaftFrameMetricsTraceReporter.reportScrollSkippedFlush(
                page = pageName,
                list = pending.lastList,
                skippedSessions = pending.sessions,
                skippedJankFrames = pending.jankFrames,
                skippedLongestFrameMs = pending.longestFrameMs,
                deviceModel = Build.MODEL,
                osVersion = Build.VERSION.RELEASE,
            )
        }
        activityRef = null
    }

    private fun onScrollChanged(list: String, scrolling: Boolean) {
        val current = active
        if (!scrolling) {
            if (current != null && current.list == list) current.finish(reason = "stopped")
            return
        }
        // Two lists scrolling at once (nested pager + list) keep the first session.
        if (current != null) return
        val activity = activityRef?.get() ?: return
        val initialInterval = frameIntervalNanos(activity)
        val probe =
            ActiveProbe(
                activity = WeakReference(activity),
                pageName = pageName,
                list = list,
                initialDisplayIntervalNanos = initialInterval,
                stats = RaftScrollFrameStats(
                    startNanos = System.nanoTime(),
                    frameIntervalNanos = initialInterval
                )
            )
        active = probe
        probe.start()
    }

    private fun frameIntervalNanos(activity: Activity?): Long {
        val refreshRate =
            activity?.window?.decorView?.display?.refreshRate?.takeIf { it > 0f } ?: DefaultRefreshRateHz
        return (1_000_000_000.0 / refreshRate.toDouble()).toLong()
    }

    private class ActiveProbe(
        private val activity: WeakReference<Activity>,
        private val pageName: String,
        val list: String,
        initialDisplayIntervalNanos: Long,
        private val stats: RaftScrollFrameStats,
    ) : Choreographer.FrameCallback, DisplayManager.DisplayListener {
        private var finished = false
        private var displayListenerRegistered = false
        private var trackedDisplayId: Int = Display.INVALID_DISPLAY
        private var displayManager: DisplayManager? = null
        private var displayFrameIntervalNanos: Long = initialDisplayIntervalNanos
        private val vsyncCallback: Any? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                createVsyncCallback()
            } else {
                null
            }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun createVsyncCallback(): Choreographer.VsyncCallback =
            Choreographer.VsyncCallback { frameData ->
                if (finished) return@VsyncCallback
                val timelines = frameData.frameTimelines
                val timelineIntervalNanos =
                    raftFrameTimelineIntervalNanos(timelines.size) { index ->
                        timelines[index].expectedPresentationTimeNanos
                    }
                // Prefer the slower (larger) interval between Display.refreshRate and FrameTimeline
                // spacing so LTPO downclocks during slow flings never count as jank.
                val liveInterval = maxOf(displayFrameIntervalNanos, timelineIntervalNanos ?: 0L)
                stats.onScrollVsync(
                    frameTimeNanos = frameData.frameTimeNanos,
                    nowNanos = System.nanoTime(),
                    liveFrameIntervalNanos = liveInterval,
                )
                postNextFrame()
            }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun postVsyncCallbackApi33(callback: Any) {
            Choreographer.getInstance().postVsyncCallback(callback as Choreographer.VsyncCallback)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun removeVsyncCallbackApi33(callback: Any) {
            Choreographer.getInstance().removeVsyncCallback(callback as Choreographer.VsyncCallback)
        }

        fun start() {
            attachDisplayListener()
            postNextFrame()
        }

        private fun postNextFrame() {
            val cb = vsyncCallback
            if (cb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                postVsyncCallbackApi33(cb)
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun removeNextFrame() {
            val cb = vsyncCallback
            if (cb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                removeVsyncCallbackApi33(cb)
            } else {
                Choreographer.getInstance().removeFrameCallback(this)
            }
        }

        private fun attachDisplayListener() {
            val currentActivity = activity.get() ?: return
            val display =
                currentActivity.window?.decorView?.display
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { currentActivity.display }.getOrNull()
                    } else {
                        @Suppress("DEPRECATION")
                        currentActivity.windowManager?.defaultDisplay
                    }
                    ?: return
            val manager =
                currentActivity.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    ?: return
            trackedDisplayId = display.displayId
            runCatching {
                manager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
                displayManager = manager
                displayListenerRegistered = true
            }
        }

        private fun detachDisplayListener() {
            if (!displayListenerRegistered) return
            displayListenerRegistered = false
            val manager = displayManager
            displayManager = null
            if (manager != null) {
                runCatching { manager.unregisterDisplayListener(this) }
            }
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (finished || displayId != trackedDisplayId) return
            val updated = frameIntervalNanos(activity.get())
            displayFrameIntervalNanos = updated
            stats.onFrameIntervalChanged(updated)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (finished) return
            stats.onScrollVsync(
                frameTimeNanos = frameTimeNanos,
                nowNanos = System.nanoTime(),
                liveFrameIntervalNanos = displayFrameIntervalNanos,
            )
            postNextFrame()
        }

        fun finish(reason: String) {
            if (finished) return
            finished = true
            removeNextFrame()
            val hadLiveRateTracking = displayListenerRegistered || vsyncCallback != null
            detachDisplayListener()
            if (active === this) active = null
            if (!hadLiveRateTracking) {
                stats.onFrameIntervalChanged(frameIntervalNanos(activity.get()))
            }
            val summary = stats.summary(System.nanoTime())
            // Drop tiny smooth micro-touches (< MinFrames), never a short scroll that hit jank.
            if (summary.frames < MinFrames && summary.jankFrames == 0) return
            val carried = throttle.record(nowMillis = SystemClock.elapsedRealtime(), list = list, summary = summary) ?: return
            val endFrameIntervalNanos = summary.endFrameIntervalNanos
            RaftAppLog.w(
                Tag,
                raftScrollFramesLine(
                    pageName = pageName,
                    list = list,
                    reason = reason,
                    summary = summary,
                    endFrameIntervalNanos = endFrameIntervalNanos,
                    skipped = carried,
                )
            )
            RaftFrameMetricsTraceReporter.reportScroll(
                page = pageName,
                list = list,
                reason = reason,
                firstFrameMs = summary.firstFrameMs,
                durationMs = summary.durationMs,
                frames = summary.frames,
                jankFrames = summary.jankFrames,
                longestFrameMs = summary.longestFrameMs,
                p95FrameMs = summary.p95FrameMs,
                frameIntervalNanos = summary.frameIntervalNanos,
                endFrameIntervalNanos = endFrameIntervalNanos,
                skippedSessions = carried.sessions,
                skippedJankFrames = carried.jankFrames,
                skippedLongestFrameMs = carried.longestFrameMs,
                deviceModel = Build.MODEL,
                osVersion = Build.VERSION.RELEASE,
            )
        }
    }
}

internal data class RaftScrollSkippedCarryover(
    val sessions: Int = 0,
    val jankFrames: Int = 0,
    val longestFrameMs: Long = 0L,
    val lastList: String = "",
)

/**
 * Pure throttle for [RaftScrollFrameProbe]: enforces [minIntervalMillis] between emitted lines,
 * while preserving the skipped session count, total jank frames, and worst frame gap so rapid
 * consecutive scrolls never discard a hitch.
 */
internal class RaftScrollFrameThrottle(
    private val minIntervalMillis: Long = 1_000L,
) {
    private var hasEmitted: Boolean = false
    private var lastLineAtMillis: Long = 0L
    private var skippedSessions: Int = 0
    private var skippedJankFrames: Int = 0
    private var skippedLongestFrameMs: Long = 0L
    private var skippedLastList: String = ""
    private var skippedWorstJankLongestFrameMs: Long = -1L

    /**
     * Records a finished scroll [summary] at [nowMillis]. Returns null when throttled (folding
     * [summary] into the skipped carryover), or the carryover to emit with this line when allowed.
     */
    fun record(
        nowMillis: Long,
        list: String,
        summary: RaftScrollFrameStats.Summary,
    ): RaftScrollSkippedCarryover? {
        if (hasEmitted && nowMillis - lastLineAtMillis < minIntervalMillis) {
            skippedSessions += 1
            skippedJankFrames += summary.jankFrames
            if (summary.longestFrameMs > skippedLongestFrameMs) {
                skippedLongestFrameMs = summary.longestFrameMs
            }
            if (summary.jankFrames > 0) {
                if (skippedWorstJankLongestFrameMs < 0L || summary.longestFrameMs >= skippedWorstJankLongestFrameMs) {
                    skippedWorstJankLongestFrameMs = summary.longestFrameMs
                    skippedLastList = list
                }
            } else if (skippedWorstJankLongestFrameMs < 0L) {
                skippedLastList = list
            }
            return null
        }
        hasEmitted = true
        lastLineAtMillis = nowMillis
        val carried =
            RaftScrollSkippedCarryover(
                sessions = skippedSessions,
                jankFrames = skippedJankFrames,
                longestFrameMs = skippedLongestFrameMs,
                lastList = skippedLastList,
            )
        clearSkipped()
        return carried
    }

    /**
     * Flushes pending skipped sessions when pausing if any of them dropped a frame, and clears
     * stale smooth skipped counts so a later foreground visit starts clean.
     */
    fun flushAndResetOnPause(): RaftScrollSkippedCarryover? {
        val pending =
            if (skippedSessions > 0 && skippedJankFrames > 0) {
                RaftScrollSkippedCarryover(
                    sessions = skippedSessions,
                    jankFrames = skippedJankFrames,
                    longestFrameMs = skippedLongestFrameMs,
                    lastList = skippedLastList,
                )
            } else {
                null
            }
        clearSkipped()
        return pending
    }

    private fun clearSkipped() {
        skippedSessions = 0
        skippedJankFrames = 0
        skippedLongestFrameMs = 0L
        skippedLastList = ""
        skippedWorstJankLongestFrameMs = -1L
    }
}

internal fun raftScrollFramesLine(
    pageName: String,
    list: String,
    reason: String,
    summary: RaftScrollFrameStats.Summary,
    endFrameIntervalNanos: Long = summary.endFrameIntervalNanos,
    skipped: RaftScrollSkippedCarryover = RaftScrollSkippedCarryover(),
): String {
    val firstFramePart = if (summary.firstFrameMs >= 0L) " firstFrameMs=${summary.firstFrameMs}" else ""
    val skippedStatsPart =
        if (skipped.sessions > 0) {
            " skippedJankFrames=${skipped.jankFrames} skippedLongestFrameMs=${skipped.longestFrameMs}"
        } else {
            ""
        }
    return "scroll_frames page=$pageName list=$list reason=$reason$firstFramePart " +
        summary.wireFields(endFrameIntervalNanos = endFrameIntervalNanos) +
        " skippedSessions=${skipped.sessions}$skippedStatsPart"
}

internal fun raftScrollFramesSkippedFlushLine(
    pageName: String,
    skipped: RaftScrollSkippedCarryover,
): String =
    "scroll_frames page=$pageName list=${skipped.lastList.ifEmpty { "unknown" }} reason=paused_skipped " +
        "skippedSessions=${skipped.sessions} skippedJankFrames=${skipped.jankFrames} " +
        "skippedLongestFrameMs=${skipped.longestFrameMs}"

/**
 * Pure accumulator for [RaftScrollFrameProbe]; all times are monotonic nanos. Frame gaps are
 * measured between consecutive Choreographer vsync timestamps and kept in a 1 ms histogram so p95
 * needs no per-frame allocation.
 */
internal class RaftScrollFrameStats(
    val startNanos: Long,
    private val frameIntervalNanos: Long,
) {
    private val histogram = IntArray(HistogramBuckets)
    private var hasAnchor = false
    private var lastVsyncNanos = 0L
    private var currentFrameIntervalNanos: Long = frameIntervalNanos
    private var previousFrameIntervalNanos: Long = frameIntervalNanos
    private var firstFrameGapNanos = -1L
    private var frames = 0
    private var jankFrames = 0
    private var longestGapNanos = 0L
    private var shortestGapNanos = Long.MAX_VALUE

    /** Updates the live vsync frame interval when LTPO/VRR shifts refresh rate mid-session. */
    fun onFrameIntervalChanged(nextFrameIntervalNanos: Long) {
        if (nextFrameIntervalNanos in MinValidFrameIntervalNanos..MaxValidFrameIntervalNanos) {
            currentFrameIntervalNanos = nextFrameIntervalNanos
        }
    }

    /**
     * Scroll-session callback: anchors on the first callback and measures the scroll-start ->
     * first-frame latency (`nowNanos - startNanos`). If that opening frame already missed a vsync
     * (>= 1.75x interval), it counts as a jank frame and updates [longestGapNanos] / [shortestGapNanos] /
     * p95 instead of being silently dropped. Normal sub-vsync partial first intervals are not fed
     * into [shortestGapNanos] so `minFrameMs` keeps reflecting steady vsync cadence.
     */
    fun onScrollVsync(
        frameTimeNanos: Long,
        nowNanos: Long,
        liveFrameIntervalNanos: Long = currentFrameIntervalNanos,
    ) {
        if (hasAnchor) {
            onVsync(frameTimeNanos, liveFrameIntervalNanos)
            return
        }
        onFrameIntervalChanged(liveFrameIntervalNanos)
        val effectiveInterval = maxOf(previousFrameIntervalNanos, currentFrameIntervalNanos)
        previousFrameIntervalNanos = currentFrameIntervalNanos
        hasAnchor = true
        val firstGap = (nowNanos - startNanos).coerceAtLeast(0L)
        firstFrameGapNanos = firstGap
        // If Choreographer handed us an un-clamped pre-stall vsync timestamp, advance the anchor
        // in whole effectiveInterval steps up to nowNanos so the next vsync does not double-count
        // the same stall or produce a sub-vsync minFrameMs.
        val lag = (nowNanos - frameTimeNanos).coerceAtLeast(0L)
        lastVsyncNanos =
            if (effectiveInterval > 0L && lag >= effectiveInterval) {
                frameTimeNanos + (lag / effectiveInterval) * effectiveInterval
            } else {
                frameTimeNanos
            }
        if (firstGap * 4 >= 7 * effectiveInterval) {
            frames += 1
            jankFrames += 1
            if (firstGap > longestGapNanos) longestGapNanos = firstGap
            if (firstGap < shortestGapNanos) shortestGapNanos = firstGap
            histogram[(firstGap / 1_000_000L).toInt().coerceIn(0, HistogramBuckets - 1)] += 1
        }
    }

    fun onVsync(
        frameTimeNanos: Long,
        liveFrameIntervalNanos: Long = currentFrameIntervalNanos,
    ) {
        onFrameIntervalChanged(liveFrameIntervalNanos)
        // Use the larger of previous & current intervals during LTPO/VRR transitions so a clean
        // 120 Hz -> 60 Hz downclock step never counts as a missed frame.
        val effectiveInterval = maxOf(previousFrameIntervalNanos, currentFrameIntervalNanos)
        previousFrameIntervalNanos = currentFrameIntervalNanos
        // The first callback only anchors the cadence; its gap to the scroll start is not a frame.
        if (!hasAnchor) {
            hasAnchor = true
            lastVsyncNanos = frameTimeNanos
            return
        }
        val previous = lastVsyncNanos
        if (frameTimeNanos > previous) {
            lastVsyncNanos = frameTimeNanos
        }
        val gap = (frameTimeNanos - previous).coerceAtLeast(0L)
        frames += 1
        if (gap > longestGapNanos) longestGapNanos = gap
        if (gap < shortestGapNanos) shortestGapNanos = gap
        // 1.75x: a missed vsync (~2 intervals) counts, a 60 Hz frame on a 90 Hz estimate (1.5x) does not.
        if (gap * 4 >= 7 * effectiveInterval) jankFrames += 1
        histogram[(gap / 1_000_000L).toInt().coerceIn(0, HistogramBuckets - 1)] += 1
    }

    fun summary(nowNanos: Long): Summary =
        Summary(
            durationMs = (nowNanos - startNanos).coerceAtLeast(0L) / 1_000_000L,
            frames = frames,
            jankFrames = jankFrames,
            longestFrameMs = longestGapNanos / 1_000_000L,
            shortestFrameMs = if (shortestGapNanos != Long.MAX_VALUE) shortestGapNanos / 1_000_000.0 else 0.0,
            p95FrameMs = percentileMs(95),
            frameIntervalNanos = frameIntervalNanos,
            firstFrameMs = if (firstFrameGapNanos >= 0L) firstFrameGapNanos / 1_000_000L else -1L,
            endFrameIntervalNanos = currentFrameIntervalNanos,
        )

    /** Upper edge (ms) of the histogram bucket holding the [percent]th frame; 0 with no frames. */
    private fun percentileMs(percent: Int): Long {
        if (frames == 0) return 0L
        val rank = (frames.toLong() * percent + 99) / 100
        var seen = 0L
        for (bucket in histogram.indices) {
            seen += histogram[bucket]
            if (seen >= rank) return bucket + 1L
        }
        return HistogramBuckets.toLong()
    }

    data class Summary(
        val durationMs: Long,
        val frames: Int,
        val jankFrames: Int,
        val longestFrameMs: Long,
        val shortestFrameMs: Double,
        val p95FrameMs: Long,
        val frameIntervalNanos: Long,
        val firstFrameMs: Long = -1L,
        val endFrameIntervalNanos: Long = frameIntervalNanos,
    ) {
        fun wireFields(endFrameIntervalNanos: Long = this.endFrameIntervalNanos): String =
            "durationMs=$durationMs frames=$frames jankFrames=$jankFrames " +
                "longestFrameMs=$longestFrameMs p95FrameMs=$p95FrameMs " +
                "minFrameMs=${oneDecimal(shortestFrameMs)} " +
                "vsyncMs=${oneDecimal(frameIntervalNanos / 1_000_000.0)} " +
                "vsyncEndMs=${oneDecimal(endFrameIntervalNanos / 1_000_000.0)}"
    }

    private companion object {
        const val HistogramBuckets = 128
    }
}

private fun oneDecimal(value: Double): Double = (value * 10).toLong() / 10.0
