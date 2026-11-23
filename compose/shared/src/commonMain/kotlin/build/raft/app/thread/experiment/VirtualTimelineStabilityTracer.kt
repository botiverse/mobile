package build.raft.app.thread.experiment

import kotlin.math.abs
import kotlin.math.max

/**
 * Classifies what changed between two consecutive [VirtualMessageTimelineState.measure] passes so
 * runtime anchor-drift and tail-pin telemetry can distinguish intentional motion (user drags,
 * programmatic jumps) from unexpected visual jumps during state transitions (`#proj-mobile:#268`).
 */
internal enum class VirtualTimelineTransitionCause(val wire: String) {
    InitialOpen("initial_open"),
    UserScroll("user_scroll"),
    ProgrammaticScroll("programmatic_scroll"),
    ProgrammaticJump("jump"),
    FollowBottom("follow_bottom"),
    Prepend("prepend"),
    Append("append"),
    KeyListChange("key_list_change"),
    ViewportResize("viewport_resize"),
    RowHeightChange("row_height_change"),
    Steady("steady"),
}

/** One recorded measure-pass transition (emitted on state changes or any unexpected jump). */
internal data class VirtualTimelineStabilityEvent(
    val passNumber: Int,
    val cause: VirtualTimelineTransitionCause,
    val anchorKey: String?,
    val anchorDriftPx: Int,
    val tailDriftPx: Int,
    val compensatedScrollDeltaPx: Int,
    val followBottom: Boolean,
    val previousItemCount: Int,
    val nextItemCount: Int,
    val previousViewportHeightPx: Int,
    val nextViewportHeightPx: Int,
    val measuredItemCount: Int,
    val unexpectedJump: Boolean,
) {
    fun toDiagnosticsLine(): String =
        buildString {
            append("vtl_stability status=")
            append(if (unexpectedJump) "JUMP" else "ok")
            append(" cause=")
            append(cause.wire)
            append(" anchor=")
            append(anchorKey ?: "none")
            append(" driftPx=")
            append(anchorDriftPx)
            append(" tailDriftPx=")
            append(tailDriftPx)
            append(" compPx=")
            append(compensatedScrollDeltaPx)
            append(" followBottom=")
            append(followBottom)
            append(" items=")
            append(previousItemCount)
            append("->")
            append(nextItemCount)
            append(" vpH=")
            append(previousViewportHeightPx)
            append("->")
            append(nextViewportHeightPx)
            append(" measured=")
            append(measuredItemCount)
            append(" pass=")
            append(passNumber)
        }
}

/** Aggregate session metrics logged when a [VirtualMessageTimeline] leaves composition (or read by tests). */
internal data class VirtualTimelineStabilitySummary(
    val totalPasses: Int,
    val stateChangePasses: Int,
    val compensatedPasses: Int,
    val unexpectedJumpCount: Int,
    val maxAnchorDriftPx: Int,
    val maxTailDriftPx: Int,
    val maxCompensatedPx: Int,
    val maxJumpAlignmentErrorPx: Int,
    val totalItemsMeasured: Int,
    val countsByCause: Map<VirtualTimelineTransitionCause, Int>,
) {
    fun toDiagnosticsLine(): String {
        val avgMeasured = if (totalPasses > 0) totalItemsMeasured / totalPasses else 0
        val causeBreakdown =
            countsByCause.entries
                .filter { it.value > 0 }
                .joinToString(",") { (cause, count) -> "${cause.wire}:$count" }
        return "vtl_session_summary passes=$totalPasses stateChanges=$stateChangePasses " +
            "compensated=$compensatedPasses unexpectedJumps=$unexpectedJumpCount " +
            "maxDriftPx=$maxAnchorDriftPx maxTailDriftPx=$maxTailDriftPx " +
            "maxCompPx=$maxCompensatedPx maxJumpErrPx=$maxJumpAlignmentErrorPx " +
            "avgMeasured=$avgMeasured causes=[$causeBreakdown]"
    }
}

/**
 * Plain-field, zero-recomposition runtime stability tracer for [VirtualMessageTimelineState].
 *
 * Tracks frame-to-frame anchor Y position and bottom-tail alignment across state transitions
 * (older-page prepends, realtime appends, async image/reaction/markdown height changes, IME /
 * viewport resizes, fold expand/collapse, programmatic jumps) without allocating on smooth scroll
 * frames or triggering any Compose snapshot invalidations.
 */
internal class VirtualTimelineStabilityTracer(
    private val maxHistory: Int = DefaultMaxHistory,
) {
    private var hasPreviousPass: Boolean = false
    private var prevItemCount: Int = 0
    private var prevViewportHeightPx: Int = 0
    private var prevTopPaddingPx: Int = 0
    private var prevBottomPaddingPx: Int = 0
    private var prevScrollInt: Int = 0
    private var prevMaxScroll: Int = 0
    private var prevFollowBottom: Boolean = false
    private var prevAnchorKey: Any? = null
    private var prevAnchorContentOffsetPx: Int = 0

    private var pendingKeyChangeCause: VirtualTimelineTransitionCause? = null
    private var userScrolledSinceLastPass: Boolean = false
    private var programmaticScrolledSinceLastPass: Boolean = false

    private var totalPasses: Int = 0
    private var stateChangePasses: Int = 0
    private var compensatedPasses: Int = 0
    private var unexpectedJumpCount: Int = 0
    private var maxAnchorDriftPx: Int = 0
    private var maxTailDriftPx: Int = 0
    private var maxCompensatedPx: Int = 0
    private var totalItemsMeasured: Int = 0
    private val causeCounts = IntArray(VirtualTimelineTransitionCause.entries.size)
    private val history = ArrayDeque<VirtualTimelineStabilityEvent>(maxHistory.coerceAtLeast(1))

    /** Current tracked reference anchor key (for tests/syncKeys classification). */
    val trackedAnchorKey: Any?
        get() = prevAnchorKey

    fun recentEvents(): List<VirtualTimelineStabilityEvent> = history.toList()

    fun noteUserScrollDelta() {
        userScrolledSinceLastPass = true
    }

    fun noteProgrammaticScrollDelta() {
        programmaticScrolledSinceLastPass = true
    }

    fun noteKeyListChange(cause: VirtualTimelineTransitionCause) {
        val current = pendingKeyChangeCause
        // Preserve the strongest structural classification when multiple syncKeys coalesce before measure.
        pendingKeyChangeCause =
            when {
                current == null -> cause
                current == VirtualTimelineTransitionCause.Prepend || cause == VirtualTimelineTransitionCause.Prepend ->
                    VirtualTimelineTransitionCause.Prepend
                current == VirtualTimelineTransitionCause.KeyListChange || cause == VirtualTimelineTransitionCause.KeyListChange ->
                    VirtualTimelineTransitionCause.KeyListChange
                else -> cause
            }
    }

    fun onMeasureFinished(
        passNumber: Int,
        count: Int,
        viewportHeightPx: Int,
        topPaddingPx: Int,
        bottomPaddingPx: Int,
        finalScrollInt: Int,
        finalMaxScroll: Int,
        sparseAnchorBottom: Boolean,
        followBottom: Boolean,
        isScrollInProgress: Boolean,
        wasProgrammaticJump: Boolean,
        anyMeasuredRowHeightChanged: Boolean,
        measuredItemCount: Int,
        pinnedReadingAnchorKey: Any?,
        visibleSnapshot: List<VirtualTimelineVisibleItem>,
        trackedAnchorFallbackOffsetPx: Int? = null,
        tailFallbackBottomPx: Int? = null,
        onEvent: ((VirtualTimelineStabilityEvent) -> Unit)? = null,
    ): VirtualTimelineStabilityEvent? {
        totalPasses += 1
        totalItemsMeasured += measuredItemCount

        val keyHint = pendingKeyChangeCause
        val userScrolled = userScrolledSinceLastPass
        val programmaticScrolled = programmaticScrolledSinceLastPass
        pendingKeyChangeCause = null
        userScrolledSinceLastPass = false
        programmaticScrolledSinceLastPass = false

        val viewportResized =
            hasPreviousPass &&
                prevViewportHeightPx > 0 &&
                (
                    viewportHeightPx != prevViewportHeightPx ||
                        topPaddingPx != prevTopPaddingPx ||
                        bottomPaddingPx != prevBottomPaddingPx
                )

        val cause =
            when {
                count == 0 || viewportHeightPx <= 0 -> VirtualTimelineTransitionCause.Steady
                (!hasPreviousPass || prevItemCount == 0) && count > 0 -> VirtualTimelineTransitionCause.InitialOpen
                wasProgrammaticJump -> VirtualTimelineTransitionCause.ProgrammaticJump
                programmaticScrolled -> VirtualTimelineTransitionCause.ProgrammaticScroll
                isScrollInProgress || userScrolled -> VirtualTimelineTransitionCause.UserScroll
                keyHint != null -> keyHint
                viewportResized -> VirtualTimelineTransitionCause.ViewportResize
                anyMeasuredRowHeightChanged -> VirtualTimelineTransitionCause.RowHeightChange
                followBottom && finalScrollInt != prevScrollInt -> VirtualTimelineTransitionCause.FollowBottom
                else -> VirtualTimelineTransitionCause.Steady
            }

        causeCounts[cause.ordinal] += 1

        var anchorFound = false
        var anchorDriftPx = 0
        val trackedKey = prevAnchorKey
        if (hasPreviousPass && trackedKey != null && count > 0) {
            for (i in 0 until visibleSnapshot.size) {
                val item = visibleSnapshot[i]
                if (!item.isPinnedOffViewport && item.key == trackedKey) {
                    anchorDriftPx = item.offsetPx - prevAnchorContentOffsetPx
                    anchorFound = true
                    break
                }
            }
            if (!anchorFound && trackedAnchorFallbackOffsetPx != null) {
                // Tracked anchor still exists in heightIndex but was pushed off-screen: compute
                // its true off-screen content-offset drift so large jumps are never missed.
                anchorDriftPx = trackedAnchorFallbackOffsetPx - prevAnchorContentOffsetPx
                anchorFound = true
            }
        }

        val tailPinnedExpected = followBottom && count > 0 && viewportHeightPx > 0 && (sparseAnchorBottom || finalMaxScroll > 0)
        var tailDriftPx = 0
        if (tailPinnedExpected) {
            var tailItem: VirtualTimelineVisibleItem? = null
            for (i in visibleSnapshot.size - 1 downTo 0) {
                val item = visibleSnapshot[i]
                if (!item.isPinnedOffViewport && item.index == count - 1) {
                    tailItem = item
                    break
                }
            }
            val expectedTailBottom = viewportHeightPx - topPaddingPx - bottomPaddingPx
            tailDriftPx =
                when {
                    tailItem != null -> (tailItem.offsetPx + tailItem.sizePx) - expectedTailBottom
                    tailFallbackBottomPx != null -> tailFallbackBottomPx - expectedTailBottom
                    else -> viewportHeightPx
                }
        }

        val compensatedScrollDeltaPx =
            if (hasPreviousPass && !isScrollInProgress && !userScrolled && !programmaticScrolled && !wasProgrammaticJump) {
                finalScrollInt - prevScrollInt
            } else {
                0
            }

        val structuralResizeOrHeightChange =
            cause == VirtualTimelineTransitionCause.KeyListChange ||
                cause == VirtualTimelineTransitionCause.RowHeightChange ||
                cause == VirtualTimelineTransitionCause.ViewportResize

        val clampedAtBottomEdge =
            finalScrollInt == finalMaxScroll &&
                finalScrollInt < prevScrollInt &&
                structuralResizeOrHeightChange

        val clampedAtTopEdge =
            finalScrollInt == 0 &&
                structuralResizeOrHeightChange

        val clampedAtEdge = clampedAtBottomEdge || clampedAtTopEdge

        val isNonGestureNonJumpStateTransition =
            hasPreviousPass &&
                cause != VirtualTimelineTransitionCause.InitialOpen &&
                cause != VirtualTimelineTransitionCause.UserScroll &&
                cause != VirtualTimelineTransitionCause.ProgrammaticScroll &&
                cause != VirtualTimelineTransitionCause.ProgrammaticJump

        val unexpectedTailJump =
            tailPinnedExpected &&
                !wasProgrammaticJump &&
                !programmaticScrolled &&
                !isScrollInProgress &&
                !userScrolled &&
                abs(tailDriftPx) > DriftTolerancePx

        val unexpectedAnchorJump =
            isNonGestureNonJumpStateTransition &&
                !followBottom &&
                !prevFollowBottom &&
                prevItemCount > 0 &&
                count > 0 &&
                anchorFound &&
                prevMaxScroll > 0 &&
                finalMaxScroll > 0 &&
                !clampedAtEdge &&
                abs(anchorDriftPx) > DriftTolerancePx

        val unexpectedJump = unexpectedTailJump || unexpectedAnchorJump

        val isStateChange =
            cause != VirtualTimelineTransitionCause.UserScroll &&
                cause != VirtualTimelineTransitionCause.ProgrammaticScroll &&
                cause != VirtualTimelineTransitionCause.Steady

        if (isStateChange) {
            stateChangePasses += 1
        }
        if (isNonGestureNonJumpStateTransition && compensatedScrollDeltaPx != 0) {
            compensatedPasses += 1
            maxCompensatedPx = max(maxCompensatedPx, abs(compensatedScrollDeltaPx))
        }
        if (isNonGestureNonJumpStateTransition && !followBottom && !prevFollowBottom && anchorFound && !clampedAtEdge && prevMaxScroll > 0 && finalMaxScroll > 0) {
            maxAnchorDriftPx = max(maxAnchorDriftPx, abs(anchorDriftPx))
        }
        if (tailPinnedExpected && !wasProgrammaticJump && !programmaticScrolled && !isScrollInProgress && !userScrolled) {
            maxTailDriftPx = max(maxTailDriftPx, abs(tailDriftPx))
        }
        if (unexpectedJump) {
            unexpectedJumpCount += 1
        }

        val emittedEvent: VirtualTimelineStabilityEvent? =
            if (unexpectedJump || isStateChange) {
                val event =
                    VirtualTimelineStabilityEvent(
                        passNumber = passNumber,
                        cause = cause,
                        anchorKey = trackedKey?.toString()?.take(24),
                        anchorDriftPx = anchorDriftPx,
                        tailDriftPx = tailDriftPx,
                        compensatedScrollDeltaPx = compensatedScrollDeltaPx,
                        followBottom = followBottom,
                        previousItemCount = prevItemCount,
                        nextItemCount = count,
                        previousViewportHeightPx = prevViewportHeightPx,
                        nextViewportHeightPx = viewportHeightPx,
                        measuredItemCount = measuredItemCount,
                        unexpectedJump = unexpectedJump,
                    )
                if (maxHistory > 0) {
                    if (history.size >= maxHistory) history.removeFirst()
                    history.addLast(event)
                }
                onEvent?.invoke(event)
                event
            } else {
                null
            }

        // Update tracked reference anchor for the next pass without allocations.
        val nextAnchor = selectReferenceAnchor(visibleSnapshot, pinnedReadingAnchorKey)
        prevAnchorKey = nextAnchor?.key
        prevAnchorContentOffsetPx = nextAnchor?.offsetPx ?: 0
        prevItemCount = count
        prevViewportHeightPx = viewportHeightPx
        prevTopPaddingPx = topPaddingPx
        prevBottomPaddingPx = bottomPaddingPx
        prevScrollInt = finalScrollInt
        prevMaxScroll = finalMaxScroll
        prevFollowBottom = followBottom
        hasPreviousPass = count > 0 && viewportHeightPx > 0

        return emittedEvent
    }

    fun summary(maxJumpAlignmentErrorPx: Int = 0): VirtualTimelineStabilitySummary {
        val byCause = buildMap {
            for (cause in VirtualTimelineTransitionCause.entries) {
                val count = causeCounts[cause.ordinal]
                if (count > 0) put(cause, count)
            }
        }
        return VirtualTimelineStabilitySummary(
            totalPasses = totalPasses,
            stateChangePasses = stateChangePasses,
            compensatedPasses = compensatedPasses,
            unexpectedJumpCount = unexpectedJumpCount,
            maxAnchorDriftPx = maxAnchorDriftPx,
            maxTailDriftPx = maxTailDriftPx,
            maxCompensatedPx = maxCompensatedPx,
            maxJumpAlignmentErrorPx = maxJumpAlignmentErrorPx,
            totalItemsMeasured = totalItemsMeasured,
            countsByCause = byCause,
        )
    }

    private fun selectReferenceAnchor(
        visibleSnapshot: List<VirtualTimelineVisibleItem>,
        pinnedReadingAnchorKey: Any?,
    ): VirtualTimelineVisibleItem? {
        if (pinnedReadingAnchorKey != null) {
            for (i in 0 until visibleSnapshot.size) {
                val item = visibleSnapshot[i]
                if (!item.isPinnedOffViewport && item.key == pinnedReadingAnchorKey) return item
            }
        }
        var firstAnchorablePartial: VirtualTimelineVisibleItem? = null
        var firstAnyFull: VirtualTimelineVisibleItem? = null
        var firstAnyPartial: VirtualTimelineVisibleItem? = null
        for (i in 0 until visibleSnapshot.size) {
            val item = visibleSnapshot[i]
            if (item.isPinnedOffViewport) continue
            if (item.anchorable && item.offsetPx >= 0) return item
            if (item.anchorable && firstAnchorablePartial == null && item.offsetPx + item.sizePx > 0) {
                firstAnchorablePartial = item
            }
            if (firstAnyFull == null && item.offsetPx >= 0) {
                firstAnyFull = item
            }
            if (firstAnyPartial == null && item.offsetPx + item.sizePx > 0) {
                firstAnyPartial = item
            }
        }
        return firstAnchorablePartial ?: firstAnyFull ?: firstAnyPartial
    }

    companion object {
        const val DriftTolerancePx: Int = 1
        private const val DefaultMaxHistory: Int = 64
    }
}
