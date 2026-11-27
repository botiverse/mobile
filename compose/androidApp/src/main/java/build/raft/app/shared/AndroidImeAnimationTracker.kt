package build.raft.app.shared

import build.raft.app.compat.ui.RaftHostImeInsets

// Hands 1af517cc / 5f4a14be: how long a prepared IME animation may wait for onStart, how long past
// its stated duration a started one may run if no progress frames extend it, how far each
// onProgress frame slides the idle grace deadline, and the absolute upper bound for timed
// animations so a platform that drops onEnd never pins imeAnimationRunning forever.
internal const val IME_ANIMATION_START_GRACE_MILLIS = 300L
internal const val IME_ANIMATION_END_GRACE_MILLIS = 150L
internal const val IME_ANIMATION_PROGRESS_IDLE_GRACE_MILLIS = 250L
internal const val IME_ANIMATION_HARD_CAP_MILLIS = 3_000L

internal data class AndroidImeAnimationEndSummary(
    val durationMs: Long,
    val elapsedMs: Long,
    val progressFrames: Int,
    val maxProgressImePx: Int,
    val windowImePx: Int,
    val timedOut: Boolean,
)

/**
 * Tracks platform [android.view.WindowInsetsAnimation] IME animations and drives
 * [RaftHostImeInsets.imeAnimationRunning] with a single reusable timeout callback.
 *
 * OEM 120Hz spring curves (e.g. Xiaomi HyperOS Android 16, Hands 5f4a14be) routinely report
 * `durationMillis = 285` while emitting 53-54 `onProgress` frames over 450-900 ms. A fixed
 * `durationMillis + 150 ms` deadline expires mid-animation and snaps the composer to the settled
 * window inset before the keyboard finishes moving. Extending an idle grace timer on each
 * `onProgress` frame up to [hardCapMillis] keeps [RaftHostImeInsets.imeAnimationRunning] active
 * while frames keep arriving, while still guaranteeing recovery if the platform drops `onEnd`.
 */
internal class AndroidImeAnimationTracker<Token : Any>(
    private val state: RaftHostImeInsets,
    private val nowMillis: () -> Long,
    private val scheduleExpiry: (delayMillis: Long, onExpire: () -> Unit) -> Unit,
    private val cancelExpiry: () -> Unit,
    private val startGraceMillis: Long = IME_ANIMATION_START_GRACE_MILLIS,
    private val endGraceMillis: Long = IME_ANIMATION_END_GRACE_MILLIS,
    private val progressIdleGraceMillis: Long = IME_ANIMATION_PROGRESS_IDLE_GRACE_MILLIS,
    private val hardCapMillis: Long = IME_ANIMATION_HARD_CAP_MILLIS,
) {
    private val runningAnimations = HashSet<Token>()
    private val userDrivenAnimations = HashSet<Token>()
    private val timedOutAnimations = HashSet<Token>()
    private var preparedAtMs = 0L
    private var progressFrames = 0
    private var maxProgressImePx = 0
    private var currentDeadlineAtMs = 0L
    private var hardCapAtMs = 0L
    private var expiryScheduled = false
    private var timerGeneration = 0
    private var disposed = false

    fun onPrepare(token: Token) {
        if (disposed) return
        val now = nowMillis()
        val maxSpan = hardCapMillis.coerceAtLeast(endGraceMillis)
        val isNewBatch = runningAnimations.isEmpty()
        if (isNewBatch) {
            timedOutAnimations.clear()
            preparedAtMs = now
            progressFrames = 0
            maxProgressImePx = 0
            currentDeadlineAtMs = 0L
            hardCapAtMs = now + maxSpan
        } else if (token !in runningAnimations) {
            // A newly prepared overlapping animation (e.g. reversal near prior cap) gets a fresh
            // hard-cap window from now so it is not starved of startGraceMillis.
            hardCapAtMs = maxOf(hardCapAtMs, now + maxSpan)
        }
        runningAnimations += token
        state.imeAnimationRunning = true
        val startSpan = startGraceMillis.coerceAtMost(maxSpan)
        currentDeadlineAtMs = minOf(maxOf(currentDeadlineAtMs, now + startSpan), hardCapAtMs)
        if (userDrivenAnimations.isEmpty()) {
            armTimer(now, currentDeadlineAtMs)
        }
    }

    fun onStart(token: Token, durationMillis: Long) {
        if (disposed) return
        val now = nowMillis()
        if (runningAnimations.isEmpty()) {
            timedOutAnimations.clear()
            preparedAtMs = now
            progressFrames = 0
            maxProgressImePx = 0
            currentDeadlineAtMs = 0L
            hardCapAtMs = 0L
        }
        runningAnimations += token
        state.imeAnimationRunning = true
        if (durationMillis < 0L) {
            // User-driven (e.g. predictive back / scroll-driven IME): duration is -1 and only
            // onEnd tells us when the gesture finishes; suspend timed expiry while it is active.
            userDrivenAnimations += token
            cancelPendingExpiry()
            return
        }
        userDrivenAnimations -= token
        val maxSpan = hardCapMillis.coerceAtLeast(endGraceMillis)
        // Clamp stated duration against maxSpan before adding endGraceMillis so huge or bogus
        // OEM durations neither overflow Long nor defeat the absolute hard cap.
        val clampedDuration = durationMillis.coerceAtMost(maxSpan - endGraceMillis)
        val desiredInitialSpan = clampedDuration + endGraceMillis
        // Each newly started timed animation (e.g. a show reversing a running hide) gets a fresh
        // hard-cap window from its start; onProgress never extends hardCapAtMs.
        hardCapAtMs = maxOf(hardCapAtMs, now + maxSpan)
        // Always persist currentDeadlineAtMs even while a user-driven animation overlaps, so if
        // the user-driven gesture ends before this timed animation finishes it keeps its full span.
        currentDeadlineAtMs = minOf(maxOf(currentDeadlineAtMs, now + desiredInitialSpan), hardCapAtMs)
        if (userDrivenAnimations.isEmpty()) {
            armTimer(now, currentDeadlineAtMs)
        }
    }

    fun onProgress(imeBottomPx: Int) {
        if (disposed) return
        if (runningAnimations.isNotEmpty() || timedOutAnimations.isNotEmpty()) {
            progressFrames++
            maxProgressImePx = maxOf(maxProgressImePx, imeBottomPx)
        }
        // Never resurrect an animation after it already timed out or ended.
        if (!state.imeAnimationRunning || runningAnimations.isEmpty() || userDrivenAnimations.isNotEmpty()) return
        val now = nowMillis()
        if (hardCapAtMs <= now) {
            expireNow()
            return
        }
        val targetDeadline = minOf(maxOf(currentDeadlineAtMs, now + progressIdleGraceMillis), hardCapAtMs)
        if (!expiryScheduled || targetDeadline > currentDeadlineAtMs) {
            armTimer(now, targetDeadline)
        }
    }

    /**
     * Records an animation end. Returns an [AndroidImeAnimationEndSummary] when the last tracked
     * IME animation ends (either normally or as a late `onEnd` after timeout), and null when
     * another overlapping IME animation is still running or when [token] was never tracked /
     * already completed.
     */
    fun onEnd(token: Token, durationMillis: Long): AndroidImeAnimationEndSummary? {
        if (disposed) return null
        val wasRunning = runningAnimations.remove(token)
        userDrivenAnimations -= token
        if (runningAnimations.isNotEmpty()) {
            // If a user-driven animation just ended while a timed animation is still running,
            // make sure the remaining timed animation has a bounded expiry timer.
            if (state.imeAnimationRunning && userDrivenAnimations.isEmpty() && !expiryScheduled) {
                val now = nowMillis()
                val maxSpan = hardCapMillis.coerceAtLeast(endGraceMillis)
                val effectiveCap = hardCapAtMs.takeIf { it > 0L } ?: (now + maxSpan)
                hardCapAtMs = effectiveCap
                if (effectiveCap <= now) {
                    expireNow()
                } else {
                    val targetDeadline = minOf(maxOf(currentDeadlineAtMs, now + progressIdleGraceMillis), effectiveCap)
                    armTimer(now, targetDeadline)
                }
            }
            return null
        }
        if (wasRunning) {
            val summary =
                AndroidImeAnimationEndSummary(
                    durationMs = durationMillis,
                    elapsedMs = (nowMillis() - preparedAtMs).coerceAtLeast(0L),
                    progressFrames = progressFrames,
                    maxProgressImePx = maxProgressImePx,
                    windowImePx = state.imeBottomPx,
                    timedOut = false,
                )
            cancelPendingExpiry()
            currentDeadlineAtMs = 0L
            hardCapAtMs = 0L
            state.imeAnimationRunning = false
            return summary
        }
        if (timedOutAnimations.remove(token)) {
            if (timedOutAnimations.isNotEmpty()) return null
            return AndroidImeAnimationEndSummary(
                durationMs = durationMillis,
                elapsedMs = (nowMillis() - preparedAtMs).coerceAtLeast(0L),
                progressFrames = progressFrames,
                maxProgressImePx = maxProgressImePx,
                windowImePx = state.imeBottomPx,
                timedOut = true,
            )
        }
        return null
    }

    fun dispose() {
        disposed = true
        cancelPendingExpiry()
        runningAnimations.clear()
        userDrivenAnimations.clear()
        timedOutAnimations.clear()
        currentDeadlineAtMs = 0L
        hardCapAtMs = 0L
        state.imeAnimationRunning = false
    }

    private fun armTimer(now: Long, targetDeadlineAtMs: Long) {
        val delay = (targetDeadlineAtMs - now).coerceAtLeast(0L)
        if (delay == 0L) {
            expireNow()
            return
        }
        currentDeadlineAtMs = targetDeadlineAtMs
        expiryScheduled = true
        val generation = ++timerGeneration
        scheduleExpiry(delay) {
            if (!disposed && generation == timerGeneration) {
                expireNow()
            }
        }
    }

    private fun cancelPendingExpiry() {
        timerGeneration++
        if (expiryScheduled) {
            expiryScheduled = false
            cancelExpiry()
        }
    }

    private fun expireNow() {
        cancelPendingExpiry()
        timedOutAnimations.addAll(runningAnimations)
        runningAnimations.clear()
        userDrivenAnimations.clear()
        currentDeadlineAtMs = 0L
        hardCapAtMs = 0L
        state.imeAnimationRunning = false
    }
}
