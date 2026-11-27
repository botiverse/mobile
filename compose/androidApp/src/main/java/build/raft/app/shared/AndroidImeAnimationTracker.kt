package build.raft.app.shared

import build.raft.app.compat.ui.RaftHostImeInsets

// Frame-based (vsync-aligned, zero hardcoded milliseconds) settlement counts for broken OEMs that
// drop WindowInsetsAnimation lifecycle callbacks:
// - Android's InsetsController dispatches onPrepare -> onApplyWindowInsets -> onStart within the
//   same traversal; waiting 2 live Choreographer frames covers any cross-frame prepare while still
//   recovering an orphaned onPrepare (e.g. Huawei 1af517cc) within 2 frames.
// - During a timed animation, onProgress runs on display vsyncs; waiting 4 consecutive live frames
//   without any onProgress supports animations stepped down to 1/4 display refresh rate (e.g. 30Hz on
//   a 120Hz screen) and main-thread jank pauses (Choreographer only fires once when unblocked), while
//   settling cleanly if an OEM drops onEnd.
internal const val IME_PREPARED_VSYNC_FRAMES_TO_SETTLE = 2
internal const val IME_IDLE_VSYNC_FRAMES_TO_SETTLE = 4

internal data class AndroidImeAnimationEndSummary(
    val durationMs: Long,
    val elapsedMs: Long,
    val progressFrames: Int,
    val maxProgressImePx: Int,
    val windowImePx: Int,
    val timedOut: Boolean,
)

/**
 * First-principles single-source Android IME inset tracker (Hands 1af517cc / 5f4a14be).
 *
 * Instead of switching between two IME sources (`WindowInsets.ime` vs `hostImeInsets.imeBottomPx`)
 * via `imeAnimationRunning` and relying on hardcoded millisecond timeouts to unstick broken OEMs,
 * Android keeps `state.imeAnimationRunning = false` and drives `state.imeBottomPx` directly as the
 * single authoritative value:
 * - Settled / non-animated `onApplyWindowInsets` dispatches write `state.imeBottomPx` immediately.
 * - While a `WindowInsetsAnimation` is active, `onApplyWindowInsets` records the target height
 *   without snapping, and each `onProgress` frame writes the live interpolated IME height directly
 *   into `state.imeBottomPx` — regardless of refresh rate or spring curve length (60Hz/120Hz/240Hz,
 *   285ms or 900ms+).
 * - `onEnd` (or a `Choreographer` vsync watcher if an OEM drops `onStart` / `onEnd`) reconciles
 *   `state.imeBottomPx` with the authoritative live window inset (`readSettledWindowImePx`).
 */
internal class AndroidImeAnimationTracker<Token : Any>(
    private val state: RaftHostImeInsets,
    private val nowMillis: () -> Long,
    private val scheduleNextFrame: (onFrame: () -> Unit) -> Unit,
    private val cancelNextFrame: () -> Unit,
    private val readSettledWindowImePx: () -> Int? = { null },
    private val onStateChanged: () -> Unit = {},
    private val preparedVsyncFramesToSettle: Int = IME_PREPARED_VSYNC_FRAMES_TO_SETTLE,
    private val idleVsyncFramesToSettle: Int = IME_IDLE_VSYNC_FRAMES_TO_SETTLE,
) {
    private val preparedAnimations = HashSet<Token>()
    private val runningAnimations = HashSet<Token>()
    private val userDrivenAnimations = HashSet<Token>()
    private val timedOutAnimations = HashSet<Token>()

    private var settledWindowImePx = state.imeBottomPx.coerceAtLeast(0)
    private var preparedAtMs = 0L
    private var progressFrames = 0
    private var maxProgressImePx = 0
    private var preparedIdleFrames = 0
    private var consecutiveIdleFrames = 0
    private var sawProgressSinceLastFrame = false
    private var frameScheduled = false
    private var frameGeneration = 0
    private var disposed = false

    init {
        clearImeAnimationRunning()
    }

    /**
     * Records a platform `onApplyWindowInsets` IME height. Written immediately to
     * `state.imeBottomPx` when no animation is prepared or running; deferred (held as the settled
     * target) while an IME animation is in flight so the composer slides instead of snapping.
     */
    fun onWindowInsetsApplied(imeBottomPx: Int) {
        if (disposed) return
        val clamped = imeBottomPx.coerceAtLeast(0)
        settledWindowImePx = clamped
        if (preparedAnimations.isEmpty() && runningAnimations.isEmpty()) {
            writeImeBottomPx(clamped)
        }
    }

    fun onPrepare(token: Token) {
        if (disposed) return
        if (preparedAnimations.isEmpty() && runningAnimations.isEmpty()) {
            resetBatchMetrics()
        }
        preparedAnimations += token
        preparedIdleFrames = 0
        if (userDrivenAnimations.isEmpty()) {
            armFrameWatcherIfNeeded()
        }
    }

    fun onStart(token: Token, durationMillis: Long) {
        if (disposed) return
        val wasIdle = preparedAnimations.isEmpty() && runningAnimations.isEmpty()
        preparedAnimations -= token
        if (wasIdle) {
            resetBatchMetrics()
        }
        runningAnimations += token
        if (durationMillis < 0L) {
            // User-driven gesture (e.g. scroll-driven IME / predictive back): finger may pause
            // indefinitely mid-drag, so suspend idle-vsync settlement while active.
            userDrivenAnimations += token
            cancelFrameWatcher()
            return
        }
        userDrivenAnimations -= token
        sawProgressSinceLastFrame = true
        consecutiveIdleFrames = 0
        if (userDrivenAnimations.isEmpty()) {
            armFrameWatcherIfNeeded()
        }
    }

    fun onProgress(imeBottomPx: Int) {
        if (disposed) return
        val clamped = imeBottomPx.coerceAtLeast(0)
        if (runningAnimations.isNotEmpty() || timedOutAnimations.isNotEmpty()) {
            progressFrames++
            maxProgressImePx = maxOf(maxProgressImePx, clamped)
        }
        // Never resurrect an animation that has already settled or ended.
        if (runningAnimations.isEmpty()) return
        writeImeBottomPx(clamped)
        sawProgressSinceLastFrame = true
        consecutiveIdleFrames = 0
        if (userDrivenAnimations.isEmpty()) {
            armFrameWatcherIfNeeded()
        }
    }

    /**
     * Records an animation end. Returns an [AndroidImeAnimationEndSummary] when the last tracked
     * IME animation ends (either normally or as a late `onEnd` after vsync settlement), and null
     * when another overlapping IME animation is still active or when [token] was never tracked.
     */
    fun onEnd(token: Token, durationMillis: Long): AndroidImeAnimationEndSummary? {
        if (disposed) return null
        val wasPrepared = preparedAnimations.remove(token)
        val wasRunning = runningAnimations.remove(token)
        val wasUserDriven = userDrivenAnimations.remove(token)
        if (!wasPrepared && !wasRunning) {
            if (
                timedOutAnimations.remove(token) &&
                    preparedAnimations.isEmpty() &&
                    runningAnimations.isEmpty() &&
                    timedOutAnimations.isEmpty()
            ) {
                val finalWindowImePx = resolveAuthoritativeWindowImePx()
                writeImeBottomPx(finalWindowImePx)
                return AndroidImeAnimationEndSummary(
                    durationMs = durationMillis,
                    elapsedMs = (nowMillis() - preparedAtMs).coerceAtLeast(0L),
                    progressFrames = progressFrames,
                    maxProgressImePx = maxProgressImePx,
                    windowImePx = finalWindowImePx,
                    timedOut = true,
                )
            }
            return null
        }
        if (preparedAnimations.isNotEmpty() || runningAnimations.isNotEmpty()) {
            // A user-driven gesture may have just ended while a timed/prepared animation remains.
            if (wasUserDriven && userDrivenAnimations.isEmpty()) {
                sawProgressSinceLastFrame = true
                consecutiveIdleFrames = 0
                preparedIdleFrames = 0
                armFrameWatcherIfNeeded()
            }
            return null
        }
        cancelFrameWatcher()
        val finalWindowImePx = resolveAuthoritativeWindowImePx()
        writeImeBottomPx(finalWindowImePx)
        val summary =
            AndroidImeAnimationEndSummary(
                durationMs = durationMillis,
                elapsedMs = (nowMillis() - preparedAtMs).coerceAtLeast(0L),
                progressFrames = progressFrames,
                maxProgressImePx = maxProgressImePx,
                windowImePx = finalWindowImePx,
                timedOut = false,
            )
        resetIdleCounters()
        return summary
    }

    fun dispose() {
        disposed = true
        cancelFrameWatcher()
        preparedAnimations.clear()
        runningAnimations.clear()
        userDrivenAnimations.clear()
        timedOutAnimations.clear()
        resetIdleCounters()
        clearImeAnimationRunning()
    }

    private fun writeImeBottomPx(px: Int) {
        if (state.imeBottomPx != px) {
            state.imeBottomPx = px
            onStateChanged()
        }
    }

    private fun clearImeAnimationRunning() {
        if (state.imeAnimationRunning) {
            state.imeAnimationRunning = false
            onStateChanged()
        }
    }

    private fun resetBatchMetrics() {
        timedOutAnimations.clear()
        preparedAtMs = nowMillis()
        progressFrames = 0
        maxProgressImePx = 0
        resetIdleCounters()
    }

    private fun resetIdleCounters() {
        preparedIdleFrames = 0
        consecutiveIdleFrames = 0
        sawProgressSinceLastFrame = false
    }

    private fun resolveAuthoritativeWindowImePx(): Int {
        val live = readSettledWindowImePx()?.coerceAtLeast(0)
        if (live != null) {
            settledWindowImePx = live
            return live
        }
        return settledWindowImePx
    }

    private fun armFrameWatcherIfNeeded() {
        if (disposed || frameScheduled || userDrivenAnimations.isNotEmpty()) return
        if (preparedAnimations.isEmpty() && runningAnimations.isEmpty()) return
        frameScheduled = true
        val generation = ++frameGeneration
        scheduleNextFrame {
            if (disposed || generation != frameGeneration) return@scheduleNextFrame
            frameScheduled = false
            onVsyncFrame()
        }
    }

    private fun cancelFrameWatcher() {
        frameGeneration++
        if (frameScheduled) {
            frameScheduled = false
            cancelNextFrame()
        }
    }

    private fun onVsyncFrame() {
        if (disposed || userDrivenAnimations.isNotEmpty()) return
        if (preparedAnimations.isEmpty() && runningAnimations.isEmpty()) return

        if (runningAnimations.isEmpty()) {
            // Only prepared animations exist (onStart has not arrived yet).
            preparedIdleFrames++
            if (preparedIdleFrames >= preparedVsyncFramesToSettle.coerceAtLeast(1)) {
                settleOrphaned()
            } else {
                armFrameWatcherIfNeeded()
            }
            return
        }

        if (sawProgressSinceLastFrame) {
            sawProgressSinceLastFrame = false
            consecutiveIdleFrames = 0
            armFrameWatcherIfNeeded()
            return
        }

        consecutiveIdleFrames++
        if (consecutiveIdleFrames >= idleVsyncFramesToSettle.coerceAtLeast(1)) {
            settleOrphaned()
        } else {
            armFrameWatcherIfNeeded()
        }
    }

    private fun settleOrphaned() {
        cancelFrameWatcher()
        timedOutAnimations.addAll(preparedAnimations)
        timedOutAnimations.addAll(runningAnimations)
        preparedAnimations.clear()
        runningAnimations.clear()
        userDrivenAnimations.clear()
        resetIdleCounters()
        writeImeBottomPx(resolveAuthoritativeWindowImePx())
    }
}
