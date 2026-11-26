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
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import build.raft.app.data.RaftFrameMetricsTraceReporter
import kotlin.math.roundToInt

/**
 * One warn line per background -> foreground return summarising how the main thread behaved in
 * the first [WindowMillis]: time to the first drawn frame, how many vsync callbacks arrived late
 * (missed at least one vsync), the total time the main thread was blocked past a vsync, and the
 * longest single gap. Release builds drop info logs and keep JankStats off, so without this line a
 * "UI stalls after resume" report carried no frame evidence at all.
 *
 * Tracks live display refresh-rate switches (LTPO / VRR 120 -> 60 Hz idle downclock or 60 -> 90 Hz
 * ramp-up) via API 33+ [Choreographer.VsyncCallback] timelines and [DisplayManager.DisplayListener]
 * so idle LTPO downclocks are never miscounted as main-thread jank (#proj-mobile:#269).
 *
 * Cost: one Choreographer callback per vsync for [WindowMillis], one DisplayListener while active,
 * and one draw listener until the first draw. Runs on the main thread only; no allocation per frame.
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
        val initialIntervalNanos = currentDisplayFrameIntervalNanos(activity)
        val probe =
            ActiveProbe(
                activity = activity,
                pageName = pageName,
                backgroundMillis = pendingBackgroundMillis,
                startToResumeMillis = SystemClock.elapsedRealtime() - foregroundAt,
                stats = RaftResumeFrameStats(
                    startNanos = System.nanoTime(),
                    frameIntervalNanos = initialIntervalNanos,
                ),
                initialDisplayIntervalNanos = initialIntervalNanos,
            )
        active = probe
        probe.start()
    }

    fun onActivityPaused(activity: Activity) {
        val probe = active ?: return
        if (probe.activity === activity) probe.finish(reason = "paused")
    }

    private fun currentDisplay(activity: Activity): Display? =
        activity.window?.decorView?.display
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { activity.display }.getOrNull()
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager?.defaultDisplay
            }

    private fun currentDisplayFrameIntervalNanos(activity: Activity): Long {
        val refreshRate = currentDisplay(activity)?.refreshRate?.takeIf { it > 0f } ?: DefaultRefreshRateHz
        return (1_000_000_000.0 / refreshRate.toDouble()).toLong()
    }

    private class ActiveProbe(
        val activity: Activity,
        private val pageName: String,
        private val backgroundMillis: Long,
        private val startToResumeMillis: Long,
        private val stats: RaftResumeFrameStats,
        initialDisplayIntervalNanos: Long,
    ) : Choreographer.FrameCallback, ViewTreeObserver.OnDrawListener, DisplayManager.DisplayListener {
        private var finished = false
        private var drawListenerAttached = false
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
                // spacing so idle LTPO downclocks never count as jank regardless of which surface
                // the OEM updates first.
                onFrameSample(
                    nowNanos = System.nanoTime(),
                    liveFrameIntervalNanos =
                        maxOf(displayFrameIntervalNanos, timelineIntervalNanos ?: 0L),
                )
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
            val decor = activity.window?.decorView
            if (decor != null) {
                decor.viewTreeObserver.addOnDrawListener(this)
                drawListenerAttached = true
            }
            attachDisplayListener()
            postNextFrame()
        }

        private fun attachDisplayListener() {
            val display = currentDisplay(activity) ?: return
            val manager = activity.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
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
            val updated = currentDisplayFrameIntervalNanos(activity)
            displayFrameIntervalNanos = updated
            stats.onFrameIntervalChanged(updated)
        }

        override fun onDraw() {
            if (stats.firstDrawNanos >= 0L) return
            stats.onFirstDraw(System.nanoTime())
            // A draw listener cannot be removed from inside onDraw.
            activity.window?.decorView?.post { detachDrawListener() }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (finished) return
            onFrameSample(nowNanos = System.nanoTime(), liveFrameIntervalNanos = displayFrameIntervalNanos)
        }

        private fun onFrameSample(nowNanos: Long, liveFrameIntervalNanos: Long) {
            stats.onFrameCallback(nowNanos = nowNanos, liveFrameIntervalNanos = liveFrameIntervalNanos)
            if (nowNanos - stats.startNanos >= WindowMillis * 1_000_000L) {
                finish(reason = "window")
            } else {
                postNextFrame()
            }
        }

        private fun postNextFrame() {
            val callback = vsyncCallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && callback != null) {
                postVsyncCallbackApi33(callback)
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun removePendingFrameCallback() {
            val callback = vsyncCallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && callback != null) {
                removeVsyncCallbackApi33(callback)
            } else {
                Choreographer.getInstance().removeFrameCallback(this)
            }
        }

        fun finish(reason: String) {
            if (finished) return
            finished = true
            val hadLiveRateSource = displayListenerRegistered || vsyncCallback != null
            removePendingFrameCallback()
            detachDisplayListener()
            detachDrawListener()
            if (active === this) active = null
            if (!hadLiveRateSource) {
                stats.onFrameIntervalChanged(currentDisplayFrameIntervalNanos(activity))
            }
            val summary = stats.summary(System.nanoTime())
            RaftAppLog.w(
                Tag,
                "resume_frames page=$pageName reason=$reason backgroundMs=$backgroundMillis " +
                    "startToResumeMs=$startToResumeMillis " +
                    summary.wireFields()
            )
            RaftFrameMetricsTraceReporter.reportResume(
                page = pageName,
                reason = reason,
                backgroundMs = backgroundMillis,
                startToResumeMs = startToResumeMillis,
                firstFrameMs = summary.firstDrawMs,
                windowMs = summary.windowMs,
                frames = summary.frames,
                jankFrames = summary.jankFrames,
                blockedMs = summary.blockedMs,
                longestFrameMs = summary.longestGapMs,
                frameIntervalNanos = stats.frameIntervalNanos,
                endFrameIntervalNanos = summary.endFrameIntervalNanos,
                deviceModel = Build.MODEL,
                osVersion = Build.VERSION.RELEASE,
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

/** Valid hardware vsync interval bounds (250 Hz .. 10 Hz) to filter corrupt/zero timeline steps. */
internal const val MinValidFrameIntervalNanos: Long = 4_000_000L
internal const val MaxValidFrameIntervalNanos: Long = 100_000_000L

/** Extracts the smallest valid positive spacing between consecutive expected presentation times (allocation-free). */
internal inline fun raftFrameTimelineIntervalNanos(
    count: Int,
    expectedPresentationTimeAt: (Int) -> Long,
): Long? {
    if (count < 2) return null
    var minStep = Long.MAX_VALUE
    var previous = expectedPresentationTimeAt(0)
    for (i in 1 until count) {
        val current = expectedPresentationTimeAt(i)
        val step = current - previous
        previous = current
        if (step in MinValidFrameIntervalNanos..MaxValidFrameIntervalNanos && step < minStep) {
            minStep = step
        }
    }
    return minStep.takeIf { it != Long.MAX_VALUE }
}

internal fun raftFrameTimelineIntervalNanos(expectedPresentationTimesNanos: LongArray): Long? =
    raftFrameTimelineIntervalNanos(expectedPresentationTimesNanos.size) { expectedPresentationTimesNanos[it] }

private fun sanitizeResumeFrameIntervalNanos(intervalNanos: Long): Long? =
    intervalNanos.takeIf { it in MinValidFrameIntervalNanos..MaxValidFrameIntervalNanos }

private fun frameIntervalNanosToRoundedHz(intervalNanos: Long): Int =
    if (intervalNanos > 0L) (1_000_000_000.0 / intervalNanos.toDouble()).roundToInt() else 0

/** Pure accumulator for [RaftResumeFrameProbe]; all times are monotonic nanos. */
internal class RaftResumeFrameStats(
    val startNanos: Long,
    val frameIntervalNanos: Long,
) {
    var firstDrawNanos: Long = -1L
        private set
    private var lastCallbackNanos = -1L
    private var currentFrameIntervalNanos: Long = frameIntervalNanos
    private var frames = 0
    private var jankFrames = 0
    private var blockedNanos = 0L
    private var longestGapNanos = 0L
    private var rateSwitches = 0
    private var lastRoundedHz: Int = frameIntervalNanosToRoundedHz(frameIntervalNanos)

    fun onFirstDraw(nowNanos: Long) {
        if (firstDrawNanos < 0L) firstDrawNanos = nowNanos
    }

    /** Updates the live display/vsync frame interval when LTPO/VRR changes refresh rate mid-window. */
    fun onFrameIntervalChanged(nextFrameIntervalNanos: Long) {
        val sanitized = sanitizeResumeFrameIntervalNanos(nextFrameIntervalNanos) ?: return
        currentFrameIntervalNanos = sanitized
        val nextHz = frameIntervalNanosToRoundedHz(sanitized)
        if (nextHz > 0 && lastRoundedHz > 0 && nextHz != lastRoundedHz) {
            rateSwitches += 1
            lastRoundedHz = nextHz
        }
    }

    fun onFrameCallback(
        nowNanos: Long,
        liveFrameIntervalNanos: Long = currentFrameIntervalNanos,
    ) {
        if (liveFrameIntervalNanos != currentFrameIntervalNanos) {
            onFrameIntervalChanged(liveFrameIntervalNanos)
        }
        val effectiveInterval = currentFrameIntervalNanos
        val previous = if (lastCallbackNanos >= 0L) lastCallbackNanos else startNanos
        val gap = (nowNanos - previous).coerceAtLeast(0L)
        lastCallbackNanos = nowNanos
        frames += 1
        if (gap > longestGapNanos) longestGapNanos = gap
        // The first callback measures resume -> first vsync, which includes the resume work itself.
        if (effectiveInterval > 0L && gap >= 2 * effectiveInterval) {
            jankFrames += 1
            blockedNanos += gap - effectiveInterval
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
            endFrameIntervalNanos = currentFrameIntervalNanos,
            rateSwitches = rateSwitches,
        )

    data class Summary(
        val windowMs: Long,
        val firstDrawMs: Long,
        val frames: Int,
        val jankFrames: Int,
        val blockedMs: Long,
        val longestGapMs: Long,
        val frameIntervalMs: Double,
        val endFrameIntervalNanos: Long = (frameIntervalMs * 1_000_000.0).toLong(),
        val rateSwitches: Int = 0,
    ) {
        val endFrameIntervalMs: Double
            get() = endFrameIntervalNanos / 1_000_000.0

        fun wireFields(): String =
            "firstFrameMs=$firstDrawMs windowMs=$windowMs frames=$frames jankFrames=$jankFrames " +
                "blockedMs=$blockedMs longestFrameMs=$longestGapMs " +
                "vsyncMs=${(frameIntervalMs * 10).toLong() / 10.0} " +
                "vsyncEndMs=${(endFrameIntervalMs * 10).toLong() / 10.0}" +
                if (rateSwitches > 0) " rateSwitches=$rateSwitches" else ""
    }
}
