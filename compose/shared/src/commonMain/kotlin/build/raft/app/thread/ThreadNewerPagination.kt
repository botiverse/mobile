package build.raft.app

internal const val ThreadNewerAutoLoadVisibleItemThreshold = 8

internal sealed interface ThreadNewerPageLoadOutcome {
    data class Applied(val addedMessageIds: List<String>) : ThreadNewerPageLoadOutcome {
        init {
            require(addedMessageIds.isNotEmpty()) { "addedMessageIds must not be empty" }
        }
    }

    data object Empty : ThreadNewerPageLoadOutcome

    data object Failed : ThreadNewerPageLoadOutcome

    data object Ignored : ThreadNewerPageLoadOutcome
}

internal sealed interface ThreadNewerViewportSettlementResult {
    data object Await : ThreadNewerViewportSettlementResult

    data object Reject : ThreadNewerViewportSettlementResult

    data class Restore(
        val expectedViewportEpoch: Long,
        val expectedViewportIntent: LazyViewportIntent,
        val requestAnchor: LazyViewportAnchorTarget?,
        val requestAnchorOffset: Int
    ) : ThreadNewerViewportSettlementResult
}

internal fun resolveThreadNewerViewportSettlement(
    requestViewportEpoch: Long,
    requestViewportIntent: LazyViewportIntent,
    currentViewportEpoch: Long,
    currentViewportIntent: LazyViewportIntent,
    pointerGestureActive: Boolean,
    pageProjectionCommitted: Boolean,
    requestAnchor: LazyViewportAnchorTarget?,
    requestAnchorOffset: Int
): ThreadNewerViewportSettlementResult {
    if (pointerGestureActive) return ThreadNewerViewportSettlementResult.Reject
    val expectedOwnerEpoch =
        requestViewportEpoch + if (requestViewportIntent == LazyViewportIntent.UserDragging) 1L else 0L
    if (currentViewportEpoch > expectedOwnerEpoch) return ThreadNewerViewportSettlementResult.Reject
    if (currentViewportEpoch < expectedOwnerEpoch || !pageProjectionCommitted) {
        return ThreadNewerViewportSettlementResult.Await
    }
    return if (currentViewportIntent == LazyViewportIntent.UserDragging) {
        ThreadNewerViewportSettlementResult.Reject
    } else {
        ThreadNewerViewportSettlementResult.Restore(
            expectedViewportEpoch = currentViewportEpoch,
            expectedViewportIntent = currentViewportIntent,
            requestAnchor = requestAnchor,
            requestAnchorOffset = requestAnchorOffset
        )
    }
}

/**
 * Converts direct pointer gestures into one-shot demand for a newer page.
 *
 * The general Thread user-scroll generation intentionally counts every direct
 * move because it owns viewport arbitration. Newer pagination needs a narrower
 * signal: only an upward finger move beyond platform touch slop (toward
 * higher/newer list indices) may authorize a page, and one continuous gesture
 * may authorize at most one page.
 */
internal class ThreadNewerGestureDemandTracker(
    initialGeneration: Int = 0
) {
    init {
        require(initialGeneration >= 0) { "initialGeneration must be non-negative" }
    }

    var generation: Int = initialGeneration
        private set

    private var gestureActive: Boolean = false
    private var emittedForGesture: Boolean = false
    private var gestureStartPointerY: Float? = null

    /**
     * Records a pointer event and returns the current newer-demand generation.
     * A Y smaller than the gesture's Down point by more than [touchSlop] is a
     * deliberate net upward finger move. Comparing against Down prevents taps,
     * long presses, and mostly downward/away gestures from authorizing a page
     * because of pointer jitter between Move events.
     */
    fun onTouch(
        type: TouchType,
        pointerY: Float,
        touchSlop: Float
    ): Int {
        when (type) {
            TouchType.Down -> {
                gestureActive = true
                emittedForGesture = false
                gestureStartPointerY = pointerY.takeIf { it.isFinite() }
            }

            TouchType.Move -> {
                if (!gestureActive || !pointerY.isFinite()) return generation
                val startY = gestureStartPointerY
                val minimumDistance = touchSlop.takeIf { it.isFinite() && it >= 0f } ?: return generation
                if (
                    !emittedForGesture &&
                    startY != null &&
                    startY - pointerY > minimumDistance
                ) {
                    generation += 1
                    emittedForGesture = true
                }
            }

            TouchType.Up -> {
                gestureActive = false
                emittedForGesture = false
                gestureStartPointerY = null
            }
        }
        return generation
    }
}

/**
 * Pure gate for one newer-page request.
 *
 * The caller consumes all demand observed through an active page cycle by
 * advancing [lastSettledNewerDemandGeneration] to the current demand
 * generation when that cycle settles. This prevents append settlement from
 * cascading into another page without a fresh gesture.
 */
internal fun shouldThreadAutoLoadNewerMessages(
    initialBottomAnchorReady: Boolean,
    canLoadMoreAfter: Boolean,
    newerMessagesStatus: String,
    newerPageCycleActive: Boolean,
    lastVisibleIndex: Int,
    latestMessageListItemIndex: Int,
    newerDemandGeneration: Int,
    lastSettledNewerDemandGeneration: Int,
    thresholdItems: Int = ThreadNewerAutoLoadVisibleItemThreshold
): Boolean {
    if (!initialBottomAnchorReady || !canLoadMoreAfter) return false
    if (newerMessagesStatus != "idle" || newerPageCycleActive) return false
    if (newerDemandGeneration <= lastSettledNewerDemandGeneration) return false
    if (lastVisibleIndex < 0 || latestMessageListItemIndex < 0 || thresholdItems < 0) return false
    return lastVisibleIndex >= latestMessageListItemIndex - thresholdItems
}

/**
 * A page-cycle completion may touch the viewport only while both its route
 * identity and cycle id still own the current Thread surface.
 */
internal fun shouldSettleThreadNewerPageCycle(
    cycleThreadInstanceKey: String,
    currentThreadInstanceKey: String,
    cycleId: Long,
    activeCycleId: Long?
): Boolean =
    cycleThreadInstanceKey == currentThreadInstanceKey &&
        cycleId == activeCycleId

internal fun shouldRestoreThreadNewerViewportAfterSettlement(
    cycleThreadInstanceKey: String,
    currentThreadInstanceKey: String,
    cycleId: Long,
    activeCycleId: Long?,
    outcome: ThreadNewerPageLoadOutcome
): Boolean =
    shouldSettleThreadNewerPageCycle(
        cycleThreadInstanceKey = cycleThreadInstanceKey,
        currentThreadInstanceKey = currentThreadInstanceKey,
        cycleId = cycleId,
        activeCycleId = activeCycleId
    ) && outcome is ThreadNewerPageLoadOutcome.Applied
