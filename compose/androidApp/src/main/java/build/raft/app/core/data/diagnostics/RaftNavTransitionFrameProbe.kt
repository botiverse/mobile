package build.raft.app.core.data.diagnostics

import android.app.Activity
import android.view.Choreographer
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
        val transition =
            ActiveTransition(
                kind = kind,
                fromPage = fromPage,
                toPage = toPage,
                stats = RaftScrollFrameStats(startNanos = requested ?: now, frameIntervalNanos = frameIntervalNanos()),
            )
        active = transition
        Choreographer.getInstance().postFrameCallback(transition)
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
        private val stats: RaftScrollFrameStats,
    ) : Choreographer.FrameCallback {
        private var finished = false
        private var firstFrameNanos = -1L

        override fun doFrame(frameTimeNanos: Long) {
            if (finished) return
            if (firstFrameNanos < 0L) firstFrameNanos = frameTimeNanos
            stats.onVsync(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun finish(outcome: RaftStackTransitionOutcome) {
            if (finished) return
            finished = true
            Choreographer.getInstance().removeFrameCallback(this)
            if (active === this) active = null
            RaftAppLog.w(
                Tag,
                raftNavTransitionFramesLine(
                    kind = kind,
                    outcome = outcome,
                    fromPage = fromPage,
                    toPage = toPage,
                    firstFrameMs = raftNavTransitionFirstFrameMs(stats.startNanos, firstFrameNanos),
                    summary = stats.summary(System.nanoTime()),
                    endFrameIntervalNanos = frameIntervalNanos(),
                ),
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
    endFrameIntervalNanos: Long = summary.frameIntervalNanos,
): String =
    "nav_transition_frames kind=${kind.wire} outcome=${outcome.wire} from=$fromPage to=$toPage " +
        "firstFrameMs=$firstFrameMs " + summary.wireFields(endFrameIntervalNanos = endFrameIntervalNanos)

/** Start to first frame in whole ms (a vsync stamped just before the start counts 0); -1 when no frame came. */
internal fun raftNavTransitionFirstFrameMs(startNanos: Long, firstFrameNanos: Long): Long =
    if (firstFrameNanos < 0L) -1L else (firstFrameNanos - startNanos).coerceAtLeast(0L) / 1_000_000L
