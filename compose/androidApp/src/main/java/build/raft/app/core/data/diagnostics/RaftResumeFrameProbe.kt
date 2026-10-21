package build.raft.app.core.data.diagnostics

import android.app.Activity
import android.os.SystemClock
import android.view.Choreographer
import android.view.ViewTreeObserver

/**
 * One warn line per background -> foreground return summarising how the main thread behaved in
 * the first [WindowMillis]: time to the first drawn frame, how many vsync callbacks arrived late
 * (missed at least one vsync), the total time the main thread was blocked past a vsync, and the
 * longest single gap. Release builds drop info logs and keep JankStats off, so without this line a
 * "UI stalls after resume" report carried no frame evidence at all.
 *
 * Cost: one Choreographer callback per vsync for [WindowMillis] and one draw listener until the
 * first draw. Runs on the main thread only; no allocation per frame.
 */
object RaftResumeFrameProbe {
    private const val Tag = "SlockResumeFrames"
    private const val WindowMillis = 3_000L
    private const val DefaultRefreshRateHz = 60f

    // Main-thread only.
    private var hiddenAtMillis: Long? = null
    private var pendingForegroundAtMillis: Long? = null
    private var pendingBackgroundMillis: Long = -1L
    private var active: ActiveProbe? = null

    /** Process went to background (last started activity stopped). */
    fun onAppHidden() {
        hiddenAtMillis = SystemClock.elapsedRealtime()
        pendingForegroundAtMillis = null
        active?.finish(reason = "hidden")
    }

    /** Process came to foreground (first activity started). Only a return from background arms the probe. */
    fun onAppShown() {
        val hiddenAt = hiddenAtMillis ?: return
        hiddenAtMillis = null
        val now = SystemClock.elapsedRealtime()
        pendingForegroundAtMillis = now
        pendingBackgroundMillis = now - hiddenAt
    }

    /** Called from the resumed activity; starts sampling when the process just returned to foreground. */
    fun onActivityResumed(activity: Activity, pageName: String) {
        val foregroundAt = pendingForegroundAtMillis ?: return
        pendingForegroundAtMillis = null
        active?.finish(reason = "superseded")
        val refreshRate =
            activity.window?.decorView?.display?.refreshRate?.takeIf { it > 0f } ?: DefaultRefreshRateHz
        val probe =
            ActiveProbe(
                activity = activity,
                pageName = pageName,
                backgroundMillis = pendingBackgroundMillis,
                startToResumeMillis = SystemClock.elapsedRealtime() - foregroundAt,
                stats = RaftResumeFrameStats(
                    startNanos = SystemClock.uptimeNanos(),
                    frameIntervalNanos = (1_000_000_000.0 / refreshRate.toDouble()).toLong()
                )
            )
        active = probe
        probe.start()
    }

    fun onActivityPaused(activity: Activity) {
        val probe = active ?: return
        if (probe.activity === activity) probe.finish(reason = "paused")
    }

    private class ActiveProbe(
        val activity: Activity,
        private val pageName: String,
        private val backgroundMillis: Long,
        private val startToResumeMillis: Long,
        private val stats: RaftResumeFrameStats,
    ) : Choreographer.FrameCallback, ViewTreeObserver.OnDrawListener {
        private var finished = false
        private var drawListenerAttached = false

        fun start() {
            val decor = activity.window?.decorView
            if (decor != null) {
                decor.viewTreeObserver.addOnDrawListener(this)
                drawListenerAttached = true
            }
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onDraw() {
            if (stats.firstDrawNanos >= 0L) return
            stats.onFirstDraw(SystemClock.uptimeNanos())
            // A draw listener cannot be removed from inside onDraw.
            activity.window?.decorView?.post { detachDrawListener() }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (finished) return
            val now = SystemClock.uptimeNanos()
            stats.onFrameCallback(now)
            if (now - stats.startNanos >= WindowMillis * 1_000_000L) {
                finish(reason = "window")
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun finish(reason: String) {
            if (finished) return
            finished = true
            Choreographer.getInstance().removeFrameCallback(this)
            detachDrawListener()
            if (active === this) active = null
            val summary = stats.summary(SystemClock.uptimeNanos())
            RaftAppLog.w(
                Tag,
                "resume_frames page=$pageName reason=$reason backgroundMs=$backgroundMillis " +
                    "startToResumeMs=$startToResumeMillis " +
                    summary.wireFields()
            )
        }

        private fun detachDrawListener() {
            if (!drawListenerAttached) return
            drawListenerAttached = false
            val observer = activity.window?.decorView?.viewTreeObserver ?: return
            if (observer.isAlive) runCatching { observer.removeOnDrawListener(this) }
        }
    }
}

/** Pure accumulator for [RaftResumeFrameProbe]; all times are monotonic nanos. */
internal class RaftResumeFrameStats(
    val startNanos: Long,
    private val frameIntervalNanos: Long,
) {
    var firstDrawNanos: Long = -1L
        private set
    private var lastCallbackNanos = -1L
    private var frames = 0
    private var jankFrames = 0
    private var blockedNanos = 0L
    private var longestGapNanos = 0L

    fun onFirstDraw(nowNanos: Long) {
        if (firstDrawNanos < 0L) firstDrawNanos = nowNanos
    }

    fun onFrameCallback(nowNanos: Long) {
        val previous = if (lastCallbackNanos >= 0L) lastCallbackNanos else startNanos
        val gap = (nowNanos - previous).coerceAtLeast(0L)
        lastCallbackNanos = nowNanos
        frames += 1
        if (gap > longestGapNanos) longestGapNanos = gap
        // The first callback measures resume -> first vsync, which includes the resume work itself.
        if (gap >= 2 * frameIntervalNanos) {
            jankFrames += 1
            blockedNanos += gap - frameIntervalNanos
        }
    }

    fun summary(nowNanos: Long): Summary =
        Summary(
            windowMs = (nowNanos - startNanos).coerceAtLeast(0L) / 1_000_000L,
            firstDrawMs = if (firstDrawNanos >= 0L) (firstDrawNanos - startNanos) / 1_000_000L else -1L,
            frames = frames,
            jankFrames = jankFrames,
            blockedMs = blockedNanos / 1_000_000L,
            longestGapMs = longestGapNanos / 1_000_000L,
            frameIntervalMs = frameIntervalNanos / 1_000_000.0,
        )

    data class Summary(
        val windowMs: Long,
        val firstDrawMs: Long,
        val frames: Int,
        val jankFrames: Int,
        val blockedMs: Long,
        val longestGapMs: Long,
        val frameIntervalMs: Double,
    ) {
        fun wireFields(): String =
            "firstFrameMs=$firstDrawMs windowMs=$windowMs frames=$frames jankFrames=$jankFrames " +
                "blockedMs=$blockedMs longestFrameMs=$longestGapMs " +
                "vsyncMs=${(frameIntervalMs * 10).toLong() / 10.0}"
    }
}
