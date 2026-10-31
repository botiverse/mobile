package build.raft.app.core.data.diagnostics

import android.app.Activity
import android.os.SystemClock
import android.view.Choreographer
import build.raft.app.RaftScrollFrameSignal
import java.lang.ref.WeakReference

/**
 * One warn line per scroll session (a list's `isScrollInProgress` true -> false, fling included)
 * summarising vsync cadence while it scrolled: frames, late frames (missed at least one vsync),
 * longest and p95 frame gap, and the display refresh rate at start and end (90 <-> 60 Hz switches
 * show up as `vsyncMs` != `vsyncEndMs`). [RaftResumeFrameProbe] only covers the seconds after a
 * resume, so a "scrolling drops frames" report carried no scroll frame evidence.
 *
 * Cost: one Choreographer callback per vsync only while a list is scrolling; no allocation per
 * frame; at most one line per [MinLineIntervalMillis] (skipped sessions are counted on the next line).
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
    private var lastLineAtMillis = -MinLineIntervalMillis
    private var skippedSessions = 0

    fun onActivityResumed(activity: Activity, pageName: String) {
        activityRef = WeakReference(activity)
        this.pageName = pageName
        RaftScrollFrameSignal.sink = ::onScrollChanged
    }

    fun onActivityPaused(activity: Activity) {
        if (activityRef?.get() !== activity) return
        active?.finish(reason = "paused")
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
        val probe =
            ActiveProbe(
                activity = WeakReference(activity),
                pageName = pageName,
                list = list,
                stats = RaftScrollFrameStats(
                    startNanos = System.nanoTime(),
                    frameIntervalNanos = frameIntervalNanos(activity)
                )
            )
        active = probe
        Choreographer.getInstance().postFrameCallback(probe)
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
        private val stats: RaftScrollFrameStats,
    ) : Choreographer.FrameCallback {
        private var finished = false

        override fun doFrame(frameTimeNanos: Long) {
            if (finished) return
            stats.onVsync(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun finish(reason: String) {
            if (finished) return
            finished = true
            Choreographer.getInstance().removeFrameCallback(this)
            if (active === this) active = null
            val summary = stats.summary(System.nanoTime())
            if (summary.frames < MinFrames) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastLineAtMillis < MinLineIntervalMillis) {
                skippedSessions += 1
                return
            }
            lastLineAtMillis = now
            val skipped = skippedSessions
            skippedSessions = 0
            RaftAppLog.w(
                Tag,
                "scroll_frames page=$pageName list=$list reason=$reason " +
                    summary.wireFields(endFrameIntervalNanos = frameIntervalNanos(activity.get())) +
                    " skippedSessions=$skipped"
            )
        }
    }
}

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
    private var lastVsyncNanos = -1L
    private var frames = 0
    private var jankFrames = 0
    private var longestGapNanos = 0L
    private var shortestGapNanos = Long.MAX_VALUE

    fun onVsync(frameTimeNanos: Long) {
        val previous = lastVsyncNanos
        lastVsyncNanos = frameTimeNanos
        // The first callback only anchors the cadence; its gap to the scroll start is not a frame.
        if (previous < 0L) return
        val gap = (frameTimeNanos - previous).coerceAtLeast(0L)
        frames += 1
        if (gap > longestGapNanos) longestGapNanos = gap
        if (gap < shortestGapNanos) shortestGapNanos = gap
        // 1.75x: a missed vsync (~2 intervals) counts, a 60 Hz frame on a 90 Hz estimate (1.5x) does not.
        if (gap * 4 >= 7 * frameIntervalNanos) jankFrames += 1
        histogram[(gap / 1_000_000L).toInt().coerceIn(0, HistogramBuckets - 1)] += 1
    }

    fun summary(nowNanos: Long): Summary =
        Summary(
            durationMs = (nowNanos - startNanos).coerceAtLeast(0L) / 1_000_000L,
            frames = frames,
            jankFrames = jankFrames,
            longestFrameMs = longestGapNanos / 1_000_000L,
            shortestFrameMs = if (frames > 0) shortestGapNanos / 1_000_000.0 else 0.0,
            p95FrameMs = percentileMs(95),
            frameIntervalNanos = frameIntervalNanos,
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
    ) {
        fun wireFields(endFrameIntervalNanos: Long = frameIntervalNanos): String =
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
