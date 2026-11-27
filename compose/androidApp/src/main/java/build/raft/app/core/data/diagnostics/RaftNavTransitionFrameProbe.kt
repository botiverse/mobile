package build.raft.app.core.data.diagnostics

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display
import androidx.annotation.RequiresApi
import build.raft.app.data.RaftFrameMetricsTraceReporter
import build.raft.app.nav.RaftStackTransitionKind
import build.raft.app.nav.RaftStackTransitionListener
import build.raft.app.nav.RaftStackTransitionOutcome
import java.lang.ref.WeakReference

/**
 * One warn line per Navigation 3 stack transition of the shell window (push, pop, predictive back
 * gesture completed or cancelled), the [RaftScrollFrameProbe] measure applied to page motion:
 * vsync cadence from the transition's start to its settled motion (frames, late frames that missed
 * at least one vsync, longest and p95 frame gap, refresh rate at start and end). Page names only.
 *
 * Cost: one Choreographer callback per vsync only while a transition runs; no allocation per frame.
 * Transitions are user driven (one at a time, each a few hundred ms), so every one gets its line.
 */
class RaftNavTransitionFrameProbe(activity: Activity) : RaftStackTransitionListener {
    private val activityRef = WeakReference(activity)
    private var active: ActiveTransition? = null
    private var navigateNanos = -1L

    /** The stack was asked to change (open / close): the start of the next push or pop's first frame latency. */
    fun onNavigate() {
        if (navigateNanos < 0L) navigateNanos = System.nanoTime()
    }

    override fun onTransitionStarted(kind: RaftStackTransitionKind, fromPage: String, toPage: String) {
        active?.finish(RaftStackTransitionOutcome.Interrupted)
        val now = System.nanoTime()
        // A push / pop starts at the navigate call (its first frame may have to build the page); a
        // gesture preview at its first seek. A stale stamp (no transition followed it) is dropped.
        val requested = navigateNanos.takeIf { it >= 0L && kind != RaftStackTransitionKind.PredictiveBack && now - it < StaleNavigateNanos }
        navigateNanos = -1L
        val initialInterval = frameIntervalNanos()
        val transition =
            ActiveTransition(
                kind = kind,
                fromPage = fromPage,
                toPage = toPage,
                initialDisplayIntervalNanos = initialInterval,
                stats = RaftScrollFrameStats(startNanos = requested ?: now, frameIntervalNanos = initialInterval),
            )
        active = transition
        transition.start()
    }

    override fun onTransitionEnded(outcome: RaftStackTransitionOutcome) {
        navigateNanos = -1L
        active?.finish(outcome)
    }

    /** The window left the screen mid transition: its line ends there. */
    fun onPaused() {
        active?.finish(RaftStackTransitionOutcome.Interrupted)
    }

    private fun frameIntervalNanos(): Long {
        val refreshRate =
            activityRef.get()?.window?.decorView?.display?.refreshRate?.takeIf { it > 0f } ?: DefaultRefreshRateHz
        return (1_000_000_000.0 / refreshRate.toDouble()).toLong()
    }

    private inner class ActiveTransition(
        private val kind: RaftStackTransitionKind,
        private val fromPage: String,
        private val toPage: String,
        initialDisplayIntervalNanos: Long,
        private val stats: RaftScrollFrameStats,
    ) : Choreographer.FrameCallback, DisplayManager.DisplayListener {
        private var finished = false
        private var firstFrameNanos = -1L
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
                val liveInterval = maxOf(displayFrameIntervalNanos, timelineIntervalNanos ?: 0L)
                onVsyncSample(frameData.frameTimeNanos, liveInterval)
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
            val currentActivity = activityRef.get() ?: return
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
            val updated = frameIntervalNanos()
            displayFrameIntervalNanos = updated
            stats.onFrameIntervalChanged(updated)
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (finished) return
            onVsyncSample(frameTimeNanos, displayFrameIntervalNanos)
        }

        private fun onVsyncSample(frameTimeNanos: Long, liveFrameIntervalNanos: Long) {
            if (firstFrameNanos < 0L) firstFrameNanos = frameTimeNanos
            stats.onVsync(frameTimeNanos, liveFrameIntervalNanos)
            postNextFrame()
        }

        fun finish(outcome: RaftStackTransitionOutcome) {
            if (finished) return
            finished = true
            removeNextFrame()
            val hadLiveRateTracking = displayListenerRegistered || vsyncCallback != null
            detachDisplayListener()
            if (active === this) active = null
            if (!hadLiveRateTracking) {
                stats.onFrameIntervalChanged(frameIntervalNanos())
            }
            val firstFrameMs = raftNavTransitionFirstFrameMs(stats.startNanos, firstFrameNanos)
            val summary = stats.summary(System.nanoTime())
            val endFrameIntervalNanos = summary.endFrameIntervalNanos
            RaftAppLog.w(
                Tag,
                raftNavTransitionFramesLine(
                    kind = kind,
                    outcome = outcome,
                    fromPage = fromPage,
                    toPage = toPage,
                    firstFrameMs = firstFrameMs,
                    summary = summary,
                    endFrameIntervalNanos = endFrameIntervalNanos,
                ),
            )
            RaftFrameMetricsTraceReporter.reportNavTransition(
                navKind = kind.wire,
                outcome = outcome.wire,
                fromPage = fromPage,
                toPage = toPage,
                firstFrameMs = firstFrameMs,
                durationMs = summary.durationMs,
                frames = summary.frames,
                jankFrames = summary.jankFrames,
                longestFrameMs = summary.longestFrameMs,
                p95FrameMs = summary.p95FrameMs,
                frameIntervalNanos = summary.frameIntervalNanos,
                endFrameIntervalNanos = endFrameIntervalNanos,
                deviceModel = Build.MODEL,
                osVersion = Build.VERSION.RELEASE,
            )
        }
    }

    private companion object {
        const val Tag = "SlockNavTransitionFrames"
        const val DefaultRefreshRateHz = 60f
        const val StaleNavigateNanos = 1_000_000_000L
    }
}

/**
 * The `nav_transition_frames` line: kind, outcome, the two page names, [firstFrameMs] (navigate call
 * or gesture start to the first frame after it; -1 with none), then the scroll_frames fields.
 * durationMs runs from the same start, so it includes the first frame's latency.
 */
internal fun raftNavTransitionFramesLine(
    kind: RaftStackTransitionKind,
    outcome: RaftStackTransitionOutcome,
    fromPage: String,
    toPage: String,
    firstFrameMs: Long,
    summary: RaftScrollFrameStats.Summary,
    endFrameIntervalNanos: Long = summary.endFrameIntervalNanos,
): String =
    "nav_transition_frames kind=${kind.wire} outcome=${outcome.wire} from=$fromPage to=$toPage " +
        "firstFrameMs=$firstFrameMs " + summary.wireFields(endFrameIntervalNanos = endFrameIntervalNanos)

/** Start to first frame in whole ms (a vsync stamped just before the start counts 0); -1 when no frame came. */
internal fun raftNavTransitionFirstFrameMs(startNanos: Long, firstFrameNanos: Long): Long =
    if (firstFrameNanos < 0L) -1L else (firstFrameNanos - startNanos).coerceAtLeast(0L) / 1_000_000L
