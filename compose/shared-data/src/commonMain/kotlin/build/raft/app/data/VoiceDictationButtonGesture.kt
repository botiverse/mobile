package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Dual-mode single-button gesture controller for composer voice dictation (`#wg-mobile-sync:#334`).
// Mirrors web `packages/web/src/features/composer/voiceDictationGesture.ts`:
//
// - Short press (< 350 ms): starts immediately on pointer-down and locks into continuous dictation
//   on release; the next tap stops.
// - Press and hold (>= 350 ms): enters Holding ("Release to stop"); releasing stops dictation.
// - Keyboard / assistive-tech activation: toggles continuous dictation cleanly without double-firing
//   after pointer events.
// - Permission-dialog protection: if pointer is cancelled while PendingHold (e.g. an OS mic
//   permission prompt steals touch focus), stays Locked when active so granting permission flows
//   directly into listening. If cancelled while Holding, stops cleanly so the mic never sticks open.

/** Hold duration threshold separating tap-to-lock from push-to-talk (matches web 350 ms). */
@RaftCoreInternalApi
const val VOICE_DICTATION_HOLD_THRESHOLD_MS: Long = 350L

/**
 * Suppression window after `pointerUp` to absorb trailing `onClick` callbacks without double-toggling
 * (matches web 400 ms window so slow frames or desktop mouse clicks never immediately stop/reopen).
 */
@RaftCoreInternalApi
const val VOICE_DICTATION_CLICK_SUPPRESSION_RESET_MS: Long = 400L

/**
 * Presentation mode for the single-button voice dictation control:
 * - [Idle]: not dictating.
 * - [PendingHold]: pointer pressed down, waiting to see if this becomes a short tap or a hold.
 * - [Holding]: held past [VOICE_DICTATION_HOLD_THRESHOLD_MS] — UI shows non-modal "Release to stop" pill.
 * - [Locked]: continuous dictation active — next tap stops (no hold pill).
 */
@RaftCoreInternalApi
enum class VoiceDictationGestureMode {
    Idle,
    PendingHold,
    Holding,
    Locked,
}

/** Pure, coroutine-driven state machine for the dual-mode voice dictation button. */
@RaftCoreInternalApi
class VoiceDictationButtonGesture(
    private val scope: CoroutineScope,
    private val isActive: () -> Boolean,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onModeChange: (VoiceDictationGestureMode) -> Unit = {},
    private val holdThresholdMs: Long = VOICE_DICTATION_HOLD_THRESHOLD_MS,
) {
    var mode: VoiceDictationGestureMode = VoiceDictationGestureMode.Idle
        private set

    private var activePointerId: Long? = null
    private var stopOnPointerUp: Boolean = false
    private var suppressNextClick: Boolean = false
    private var holdTimerJob: Job? = null
    private var clickResetJob: Job? = null

    /**
     * Synchronizes gesture state when the underlying dictation session ends externally
     * (server done, error, mic unplugged, channel switch, Escape).
     */
    fun syncSessionInactive() {
        clearHoldTimer()
        activePointerId = null
        stopOnPointerUp = false
        setMode(VoiceDictationGestureMode.Idle)
    }

    /** Handles pointer down on the dictation button. Returns true if the gesture claimed this pointer. */
    fun handlePointerDown(
        pointerId: Long = 0L,
        isPrimary: Boolean = true,
    ): Boolean {
        if (!isPrimary) return false

        activePointerId = pointerId
        clearClickResetTimer()
        suppressNextClick = true

        if (isActive()) {
            // Already dictating (locked mode): stop cleanly when this tap releases.
            stopOnPointerUp = true
            return true
        }

        stopOnPointerUp = false
        setMode(VoiceDictationGestureMode.PendingHold)
        onStart()

        clearHoldTimer()
        holdTimerJob =
            scope.launch {
                delay(holdThresholdMs.coerceAtLeast(0L))
                holdTimerJob = null
                if (mode != VoiceDictationGestureMode.PendingHold) return@launch
                if (!isActive()) {
                    syncSessionInactive()
                    return@launch
                }
                setMode(VoiceDictationGestureMode.Holding)
            }
        return true
    }

    /** Handles pointer release for a previously claimed pointer. */
    fun handlePointerUp(pointerId: Long = 0L) {
        if (activePointerId == null || activePointerId != pointerId) return
        activePointerId = null
        clearHoldTimer()
        scheduleClickSuppressionReset()

        if (stopOnPointerUp) {
            stopOnPointerUp = false
            setMode(VoiceDictationGestureMode.Idle)
            onStop()
            return
        }

        if (mode == VoiceDictationGestureMode.Holding) {
            // Push-to-talk release: stop and drain transcript in the background.
            setMode(VoiceDictationGestureMode.Idle)
            onStop()
            return
        }

        if (mode == VoiceDictationGestureMode.PendingHold) {
            // Short press (< 350 ms): lock into continuous toggle mode until next tap.
            if (isActive()) {
                setMode(VoiceDictationGestureMode.Locked)
            } else {
                setMode(VoiceDictationGestureMode.Idle)
            }
        }
    }

    /**
     * Handles pointer cancellation (OS gesture, window blur, or native mic permission dialog):
     * - While [VoiceDictationGestureMode.Holding]: stops cleanly so the mic never sticks open.
     * - While [VoiceDictationGestureMode.PendingHold]: keeps [VoiceDictationGestureMode.Locked]
     *   when active so an OS mic permission dialog interrupting touch never aborts dictation.
     */
    fun handlePointerCancel(pointerId: Long? = null) {
        if (pointerId != null && activePointerId != null && activePointerId != pointerId) return
        activePointerId = null
        clearHoldTimer()
        clearClickResetTimer()
        suppressNextClick = false

        if (stopOnPointerUp) {
            stopOnPointerUp = false
            return
        }

        if (mode == VoiceDictationGestureMode.Holding) {
            setMode(VoiceDictationGestureMode.Idle)
            onStop()
            return
        }

        if (mode == VoiceDictationGestureMode.PendingHold) {
            setMode(if (isActive()) VoiceDictationGestureMode.Locked else VoiceDictationGestureMode.Idle)
        }
    }

    /**
     * Handles semantic click activation (keyboard Enter/Space or assistive tech). Suppresses the
     * trailing click generated after a pointer gesture so `pointerDown -> pointerUp -> click`
     * never double-toggles.
     */
    fun handleClick() {
        if (suppressNextClick) {
            clearClickResetTimer()
            suppressNextClick = false
            return
        }
        clearHoldTimer()
        activePointerId = null
        stopOnPointerUp = false

        if (isActive()) {
            setMode(VoiceDictationGestureMode.Idle)
            onStop()
        } else {
            setMode(VoiceDictationGestureMode.Locked)
            onStart()
        }
    }

    /** Cancels timers and resets internal gesture state on disposal. */
    fun dispose() {
        clearHoldTimer()
        clearClickResetTimer()
        activePointerId = null
        stopOnPointerUp = false
        suppressNextClick = false
        mode = VoiceDictationGestureMode.Idle
    }

    private fun clearHoldTimer() {
        holdTimerJob?.cancel()
        holdTimerJob = null
    }

    private fun clearClickResetTimer() {
        clickResetJob?.cancel()
        clickResetJob = null
    }

    private fun scheduleClickSuppressionReset() {
        clearClickResetTimer()
        // If a pointer drifted off the button before release, no trailing onClick fires; clear the
        // suppression flag on the next tick so a later keyboard activation is never swallowed.
        clickResetJob =
            scope.launch {
                delay(VOICE_DICTATION_CLICK_SUPPRESSION_RESET_MS)
                clickResetJob = null
                suppressNextClick = false
            }
    }

    private fun setMode(next: VoiceDictationGestureMode) {
        if (mode == next) return
        mode = next
        onModeChange(next)
    }
}
