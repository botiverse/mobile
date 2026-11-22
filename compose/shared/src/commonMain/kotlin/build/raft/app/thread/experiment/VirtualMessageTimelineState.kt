package build.raft.app.thread.experiment

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScope
import androidx.compose.foundation.lazy.layout.LazyLayoutPinnedItemList
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.layout.RemeasurementModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Vertical alignment target for [VirtualMessageTimelineState.jumpToKey]. */
internal enum class VirtualTimelineAlign {
    Top,
    Center,
    End,
}

/** Snapshot of one mounted/laid-out row from the latest measure pass. */
internal data class VirtualTimelineVisibleItem(
    val index: Int,
    val key: Any,
    val offsetPx: Int,
    val sizePx: Int,
    val isPinnedOffViewport: Boolean = false,
)

private data class PendingJumpRequest(
    val key: Any,
    val align: VirtualTimelineAlign,
    val remainingSettlePasses: Int,
)

private data class PlacedRowEntry(
    val index: Int,
    val placeables: List<Placeable>,
    val yOffsetPx: Int,
)

/**
 * State holder for the experimental `LazyLayout`-based [VirtualMessageTimeline].
 *
 * Key architectural properties:
 * 1. **Hot-path decoupling (`fluid-scroll` inspired)**: Scroll deltas update [scrollOffsetPx]
 *    and request layout directly via [Remeasurement.forceRemeasure] without invalidating parent
 *    composition or unchanged item bodies.
 * 2. **Key-indexed persistent height cache + Fenwick tree** ([heightIndex]): O(log N) prefix-sum
 *    offsets & binary-lifting visible-range queries with zero hot-path heap allocations.
 * 3. **Pre-draw key-based jump convergence** ([jumpToKey]): Resolves target row & neighbors in
 *    one measure pass (each `Measurable` measured at most once), converging to `< 2px` alignment
 *    tolerance for [VirtualTimelineAlign.Top], [VirtualTimelineAlign.Center], and
 *    [VirtualTimelineAlign.End] while suppressing auto-load edge sentinels until user scroll.
 * 4. **Pinned off-viewport slots** ([explicitPinnedKeys] + [pinnedItemList]): Keeps active
 *    in-place text selection / focus rows alive in composition and layout off-viewport without
 *    disposing their controllers or `SelectionState`, releasing them cleanly once unpinned.
 * 5. **Native scroll physics**: Implements [ScrollableState] directly for native Compose
 *    `Modifier.scrollable` + `ScrollableDefaults.flingBehavior()` (no custom bounce override).
 */
@Stable
internal class VirtualMessageTimelineState(
    initialEstimatedItemHeightPx: Int = VirtualTimelineHeightIndex.DefaultEstimatedItemHeightPx,
    initialOverscanPx: Int = DefaultOverscanPx,
) : ScrollableState {
    val heightIndex: VirtualTimelineHeightIndex = VirtualTimelineHeightIndex(initialEstimatedItemHeightPx)
    val pinnedItemList: LazyLayoutPinnedItemList = LazyLayoutPinnedItemList()
    val prefetchState: LazyLayoutPrefetchState = LazyLayoutPrefetchState()
    val interactionSource: MutableInteractionSource = MutableInteractionSource()

    internal var remeasurement: Remeasurement? = null
        private set

    internal val remeasurementModifier: RemeasurementModifier =
        object : RemeasurementModifier {
            override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
                this@VirtualMessageTimelineState.remeasurement = remeasurement
            }
        }

    /**
     * Observed ONLY inside `LazyLayoutMeasurePolicy.measure` (never in composition), mirroring
     * `LazyListState.measurementScopeInvalidator` so jumps/pin updates trigger measure without
     * recomposing parent scopes.
     */
    internal var measurementInvalidator: Int by mutableIntStateOf(0)
        private set

    var scrollOffsetPx: Float = 0f
        private set

    val scrollOffsetInt: Int
        get() = scrollOffsetPx.roundToInt()

    var viewportHeightPx: Int = 0
        private set

    var overscanPx: Int = initialOverscanPx.coerceAtLeast(0)

    val maxScrollOffsetPx: Int
        get() = (heightIndex.totalHeightPx - viewportHeightPx).coerceAtLeast(0)

    val totalEstimatedHeightPx: Int
        get() = heightIndex.totalHeightPx

    override var canScrollForward: Boolean by mutableStateOf(false)
        private set

    override var canScrollBackward: Boolean by mutableStateOf(false)
        private set

    var isJumpSettling: Boolean by mutableStateOf(false)
        private set

    /**
     * Suppresses edge auto-load pagination callbacks while a programmatic jump settles and until
     * the next real user scroll gesture (mirrors web `sentinelAutoLoadBlockKey`).
     */
    var isAutoLoadSuppressed: Boolean by mutableStateOf(false)
        private set

    var explicitPinnedKeys: Set<Any> = emptySet()
        private set

    // Diagnostics & comparison instrumentation (plain fields: never invalidate composition).
    var measurePassCount: Int = 0
        private set
    var totalItemMeasureCalls: Int = 0
        private set
    var lastMeasuredItemCount: Int = 0
        private set
    var lastPinnedOffViewportCount: Int = 0
        private set
    var lastJumpAlignmentErrorPx: Int = 0
        private set
    var lastJumpConvergencePasses: Int = 0
        private set
    var lastVisibleItems: List<VirtualTimelineVisibleItem> = emptyList()
        private set

    private var pendingJump: PendingJumpRequest? = null
    private var programmaticScrollDepth: Int = 0

    // Reusable per-measure scratch structures (avoid hot-path allocations).
    private val scratchVisibleRange = IntArray(2)
    private val passPlaceables = HashMap<Int, List<Placeable>>(32)
    private val passMeasuredHeights = HashMap<Int, Int>(32)
    private val placedRowsScratch = ArrayList<PlacedRowEntry>(32)
    private val pinnedIndicesScratch = ArrayList<Int>(4)
    private val pinnedKeySetScratch = HashSet<Any>(8)

    private val scrollableStateDelegate: ScrollableState =
        ScrollableState { delta -> consumeScrollDelta(delta) }

    override val isScrollInProgress: Boolean
        get() = scrollableStateDelegate.isScrollInProgress

    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit,
    ) {
        scrollableStateDelegate.scroll(scrollPriority, block)
    }

    override fun dispatchRawDelta(delta: Float): Float =
        scrollableStateDelegate.dispatchRawDelta(delta)

    /**
     * Synchronizes ordered virtual row keys into [heightIndex]. When prepending rows while scrolled
     * away from the top and no jump is pending, preserves the topmost visible anchor's screen Y
     * position (web `keepAnchorOnItemsChange`).
     */
    fun syncKeys(
        keys: List<Any>,
        preserveAnchorOnPrepend: Boolean = true,
        triggerMeasure: Boolean = true,
    ) {
        val anchorBefore =
            if (preserveAnchorOnPrepend && pendingJump == null && scrollOffsetPx > 0f) {
                lastVisibleItems.firstOrNull { !it.isPinnedOffViewport && it.offsetPx + it.sizePx > 0 }
            } else {
                null
            }
        val changed = heightIndex.syncKeys(keys)
        if (!changed) return

        if (anchorBefore != null) {
            val newIndex = heightIndex.indexOfKey(anchorBefore.key)
            if (newIndex >= 0) {
                val restoredScroll =
                    (heightIndex.offsetOf(newIndex) - anchorBefore.offsetPx)
                        .coerceIn(0, maxScrollOffsetPx)
                scrollOffsetPx = restoredScroll.toFloat()
            }
        } else if (pendingJump == null) {
            scrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollOffsetPx.toFloat())
        }
        if (triggerMeasure) {
            updateScrollBoundsFlags()
            requestMeasure()
        }
    }

    /** Updates explicit pinned row keys (active in-place selection, focus, media keep-alive). */
    fun updatePinnedKeys(keys: Set<Any>) {
        if (explicitPinnedKeys == keys) return
        explicitPinnedKeys = keys
        requestMeasure()
    }

    /**
     * Jumps accurately to [key] with [align] alignment.
     *
     * If [key] is already present in [heightIndex], pre-positions to the cold estimate and arms
     * pre-draw convergence so the next measure pass measures the target + neighbors and converges
     * to `< 2px` before drawing. If [key] is not loaded yet (e.g. awaiting search context window),
     * keeps the request armed until [syncKeys] brings it in.
     */
    fun jumpToKey(
        key: Any,
        align: VirtualTimelineAlign = VirtualTimelineAlign.Center,
        suppressAutoLoad: Boolean = true,
    ) {
        pendingJump =
            PendingJumpRequest(
                key = key,
                align = align,
                remainingSettlePasses = MaxJumpSettlePasses,
            )
        isJumpSettling = true
        lastJumpConvergencePasses = 0
        if (suppressAutoLoad) {
            isAutoLoadSuppressed = true
        }
        val index = heightIndex.indexOfKey(key)
        if (index >= 0 && viewportHeightPx > 0) {
            val estimatedTop = heightIndex.offsetOf(index)
            val estimatedHeight = heightIndex.heightOf(index)
            val desiredTop = desiredViewportTopPx(estimatedHeight, viewportHeightPx, align)
            scrollOffsetPx = (estimatedTop - desiredTop).coerceIn(0, maxScrollOffsetPx).toFloat()
            updateScrollBoundsFlags()
        }
        requestMeasure()
    }

    fun jumpToIndex(
        index: Int,
        align: VirtualTimelineAlign = VirtualTimelineAlign.Center,
        suppressAutoLoad: Boolean = true,
    ) {
        if (index !in 0 until heightIndex.itemCount) return
        jumpToKey(heightIndex.keyAt(index), align = align, suppressAutoLoad = suppressAutoLoad)
    }

    fun scrollToTop(suppressAutoLoad: Boolean = false) {
        if (heightIndex.itemCount == 0) return
        jumpToIndex(0, align = VirtualTimelineAlign.Top, suppressAutoLoad = suppressAutoLoad)
    }

    fun scrollToBottom(suppressAutoLoad: Boolean = false) {
        if (heightIndex.itemCount == 0) return
        jumpToIndex(heightIndex.itemCount - 1, align = VirtualTimelineAlign.End, suppressAutoLoad = suppressAutoLoad)
    }

    fun clearAutoLoadSuppression() {
        isAutoLoadSuppressed = false
    }

    /** Called on a real user drag gesture: cancels any settling jump and lifts the pagination barrier. */
    fun notifyUserScrollGesture() {
        if (pendingJump != null) {
            pendingJump = null
            isJumpSettling = false
        }
        if (isAutoLoadSuppressed) {
            isAutoLoadSuppressed = false
        }
    }

    fun visibleItemForKey(key: Any): VirtualTimelineVisibleItem? =
        lastVisibleItems.firstOrNull { it.key == key }

    fun visibleItemForIndex(index: Int): VirtualTimelineVisibleItem? =
        lastVisibleItems.firstOrNull { it.index == index }

    /**
     * Programmatic scroll delta helper (used in tests/controllers to distinguish programmatic
     * movement from direct user gestures when desired).
     */
    suspend fun programmaticScrollBy(deltaPx: Float): Float {
        programmaticScrollDepth += 1
        return try {
            var consumed = 0f
            scroll(MutatePriority.Default) {
                consumed = scrollBy(deltaPx)
            }
            consumed
        } finally {
            programmaticScrollDepth -= 1
        }
    }

    private fun consumeScrollDelta(delta: Float): Float {
        if (delta == 0f || heightIndex.itemCount == 0) return 0f
        val maxScroll = maxScrollOffsetPx.toFloat()
        val next = (scrollOffsetPx + delta).coerceIn(0f, maxScroll)
        val consumed = next - scrollOffsetPx
        if (consumed == 0f) {
            updateScrollBoundsFlags()
            return 0f
        }
        scrollOffsetPx = next
        if (programmaticScrollDepth == 0) {
            // User scroll owns the viewport: retire pending jump and release pagination barrier.
            if (pendingJump != null) {
                pendingJump = null
                isJumpSettling = false
            }
            if (isAutoLoadSuppressed) {
                isAutoLoadSuppressed = false
            }
        }
        updateScrollBoundsFlags()
        val activeRemeasurement = remeasurement
        if (activeRemeasurement != null) {
            activeRemeasurement.forceRemeasure()
        } else {
            measurementInvalidator += 1
        }
        return consumed
    }

    private fun requestMeasure() {
        val activeRemeasurement = remeasurement
        if (activeRemeasurement != null) {
            activeRemeasurement.forceRemeasure()
        } else {
            measurementInvalidator += 1
        }
    }

    private fun updateScrollBoundsFlags() {
        val maxScroll = maxScrollOffsetPx.toFloat()
        val forward = scrollOffsetPx < maxScroll - 0.5f
        val backward = scrollOffsetPx > 0.5f
        if (canScrollForward != forward) canScrollForward = forward
        if (canScrollBackward != backward) canScrollBackward = backward
    }

    internal fun measure(
        scope: LazyLayoutMeasureScope,
        constraints: Constraints,
        itemProvider: LazyLayoutItemProvider,
        onNearStartEdge: (() -> Unit)?,
        onNearEndEdge: (() -> Unit)?,
        edgeLoadThresholdPx: Int,
    ): MeasureResult {
        // Subscribe this measure pass (NOT composition) to jump/pin invalidations.
        @Suppress("UNUSED_VARIABLE")
        val invalidationToken = measurementInvalidator

        measurePassCount += 1
        passPlaceables.clear()
        passMeasuredHeights.clear()
        placedRowsScratch.clear()
        pinnedIndicesScratch.clear()

        val count = itemProvider.itemCount
        val viewportWidth =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                constraints.minWidth
            }
        val viewportHeight =
            if (constraints.hasBoundedHeight) {
                constraints.maxHeight
            } else {
                heightIndex.totalHeightPx.coerceIn(constraints.minHeight, constraints.maxHeight)
            }
        viewportHeightPx = viewportHeight

        if (count == 0 || viewportHeight <= 0) {
            scrollOffsetPx = 0f
            lastMeasuredItemCount = 0
            lastPinnedOffViewportCount = 0
            lastVisibleItems = emptyList()
            updateScrollBoundsFlags()
            return scope.layout(viewportWidth, viewportHeight) {}
        }

        val itemConstraints =
            Constraints(
                minWidth = viewportWidth,
                maxWidth = viewportWidth,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )

        fun measureItemOnce(index: Int): Int {
            passMeasuredHeights[index]?.let { return it }
            val measurables = scope.compose(index)
            val placeables =
                if (measurables.size == 1) {
                    listOf(measurables[0].measure(itemConstraints))
                } else {
                    measurables.map { measurable -> measurable.measure(itemConstraints) }
                }
            var measuredHeight = 0
            for (i in placeables.indices) {
                measuredHeight += placeables[i].height
            }
            passPlaceables[index] = placeables
            passMeasuredHeights[index] = measuredHeight
            totalItemMeasureCalls += 1
            val itemKey = itemProvider.getKey(index)
            heightIndex.recordMeasuredHeight(index, itemKey, measuredHeight)
            return measuredHeight
        }

        val activeJump = pendingJump
        val activeJumpIndex = if (activeJump != null) itemProvider.getIndex(activeJump.key) else -1

        var firstMountedIndex: Int
        var lastMountedIndex: Int

        if (activeJump != null && activeJumpIndex in 0 until count) {
            // Single-pass exact convergence: measure target + neighbors around desired alignment,
            // updating the Fenwick tree in-place so target lands within < 2px before draw.
            val targetHeight = measureItemOnce(activeJumpIndex)
            val desiredTop = desiredViewportTopPx(targetHeight, viewportHeight, activeJump.align)

            var aboveFill = 0
            val aboveLimit = desiredTop + overscanPx
            var cursorUp = activeJumpIndex - 1
            while (cursorUp >= 0 && aboveFill < aboveLimit) {
                aboveFill += measureItemOnce(cursorUp)
                cursorUp -= 1
            }
            firstMountedIndex = cursorUp + 1

            var belowFill = 0
            val belowLimit = (viewportHeight - desiredTop - targetHeight).coerceAtLeast(0) + overscanPx
            var cursorDown = activeJumpIndex + 1
            while (cursorDown < count && belowFill < belowLimit) {
                belowFill += measureItemOnce(cursorDown)
                cursorDown += 1
            }
            lastMountedIndex = cursorDown - 1

            // Compute exact scroll offset and handle boundary clamping (near top/bottom of list).
            var targetContentTop = heightIndex.offsetOf(activeJumpIndex)
            var maxScroll = (heightIndex.totalHeightPx - viewportHeight).coerceAtLeast(0)
            var resolvedScroll = (targetContentTop - desiredTop).coerceIn(0, maxScroll)

            // If clamping near start or end exposed additional viewport rows, measure them once.
            if (resolvedScroll == 0 && firstMountedIndex > 0) {
                while (firstMountedIndex > 0) {
                    firstMountedIndex -= 1
                    measureItemOnce(firstMountedIndex)
                }
            }
            var contentEndOfMounted =
                heightIndex.offsetOf(lastMountedIndex) + heightIndex.heightOf(lastMountedIndex)
            while (lastMountedIndex + 1 < count && contentEndOfMounted < resolvedScroll + viewportHeight + overscanPx) {
                lastMountedIndex += 1
                measureItemOnce(lastMountedIndex)
                contentEndOfMounted =
                    heightIndex.offsetOf(lastMountedIndex) + heightIndex.heightOf(lastMountedIndex)
            }
            var contentStartOfMounted = heightIndex.offsetOf(firstMountedIndex)
            while (firstMountedIndex > 0 && contentStartOfMounted > resolvedScroll - overscanPx) {
                firstMountedIndex -= 1
                measureItemOnce(firstMountedIndex)
                contentStartOfMounted = heightIndex.offsetOf(firstMountedIndex)
            }

            // Measure pinned off-viewport rows BEFORE resolving final jump scroll so any height
            // delta on a pinned row above activeJumpIndex is already reflected in offsetOf().
            collectPinnedOffViewportIndices(
                itemProvider = itemProvider,
                firstMounted = firstMountedIndex,
                lastMounted = lastMountedIndex,
                count = count,
            )
            for (i in 0 until pinnedIndicesScratch.size) {
                measureItemOnce(pinnedIndicesScratch[i])
            }

            // Recompute after boundary-exposed & pinned rows updated the Fenwick tree.
            targetContentTop = heightIndex.offsetOf(activeJumpIndex)
            maxScroll = (heightIndex.totalHeightPx - viewportHeight).coerceAtLeast(0)
            resolvedScroll = (targetContentTop - desiredTop).coerceIn(0, maxScroll)
            scrollOffsetPx = resolvedScroll.toFloat()

            val expectedClampedTop =
                targetContentTop - (targetContentTop - desiredTop).coerceIn(0, maxScroll)
            val actualTop = targetContentTop - resolvedScroll
            val errorPx = abs(actualTop - expectedClampedTop)
            lastJumpAlignmentErrorPx = errorPx
            lastJumpConvergencePasses += 1

            if (errorPx <= JumpAlignmentTolerancePx || activeJump.remainingSettlePasses <= 1) {
                pendingJump = null
                isJumpSettling = false
            } else {
                pendingJump = activeJump.copy(remainingSettlePasses = activeJump.remainingSettlePasses - 1)
            }
        } else {
            // Normal scroll / layout pass: O(log N) zero-allocation visible range lookup.
            val currentScrollInt = scrollOffsetPx.roundToInt().coerceIn(0, maxScrollOffsetPx)
            val anchorIndex = heightIndex.indexAtOffset(currentScrollInt)
            val anchorContentTopBefore = if (anchorIndex >= 0) heightIndex.offsetOf(anchorIndex) else 0
            val anchorViewportTopBefore = anchorContentTopBefore - currentScrollInt

            val windowTop = (currentScrollInt - overscanPx).coerceAtLeast(0)
            val windowBottom = currentScrollInt + viewportHeight + overscanPx
            heightIndex.visibleRange(windowTop, windowBottom, scratchVisibleRange)
            firstMountedIndex = scratchVisibleRange[0].coerceIn(0, count - 1)
            lastMountedIndex = scratchVisibleRange[1].coerceIn(firstMountedIndex, count - 1)

            // Measure initial estimated range, recording any above-anchor height shifts.
            var index = firstMountedIndex
            while (index <= lastMountedIndex) {
                measureItemOnce(index)
                index += 1
            }

            // Measure pinned off-viewport rows too so any pinned row above anchorIndex is included
            // in the single above-anchor height compensation step below.
            collectPinnedOffViewportIndices(
                itemProvider = itemProvider,
                firstMounted = firstMountedIndex,
                lastMounted = lastMountedIndex,
                count = count,
            )
            for (i in 0 until pinnedIndicesScratch.size) {
                measureItemOnce(pinnedIndicesScratch[i])
            }

            // Compensate scrollOffsetPx for real-height deltas strictly above the viewport anchor
            // so newly measured rows above the view never cause visual scroll jumps.
            if (currentScrollInt > 0 && anchorIndex in 0 until count) {
                val anchorContentTopAfter = heightIndex.offsetOf(anchorIndex)
                val heightShiftAboveAnchor = anchorContentTopAfter - anchorContentTopBefore
                if (heightShiftAboveAnchor != 0) {
                    val compensated =
                        (anchorContentTopAfter - anchorViewportTopBefore)
                            .coerceIn(0, (heightIndex.totalHeightPx - viewportHeight).coerceAtLeast(0))
                    scrollOffsetPx = compensated.toFloat()
                }
            }

            // Expand downward or upward if newly measured items were shorter than estimated.
            val adjustedScroll = scrollOffsetPx.roundToInt().coerceIn(0, maxScrollOffsetPx)
            while (lastMountedIndex + 1 < count) {
                val bottomOfLast =
                    heightIndex.offsetOf(lastMountedIndex) + heightIndex.heightOf(lastMountedIndex)
                if (bottomOfLast >= adjustedScroll + viewportHeight + overscanPx) break
                lastMountedIndex += 1
                measureItemOnce(lastMountedIndex)
            }
            while (firstMountedIndex > 0) {
                val topOfFirst = heightIndex.offsetOf(firstMountedIndex)
                if (topOfFirst <= adjustedScroll - overscanPx) break
                firstMountedIndex -= 1
                val prevBefore = heightIndex.offsetOf(anchorIndex)
                measureItemOnce(firstMountedIndex)
                if (currentScrollInt > 0 && anchorIndex in 0 until count && firstMountedIndex < anchorIndex) {
                    val deltaAbove = heightIndex.offsetOf(anchorIndex) - prevBefore
                    if (deltaAbove != 0) {
                        scrollOffsetPx = (scrollOffsetPx + deltaAbove).coerceIn(0f, maxScrollOffsetPx.toFloat())
                    }
                }
            }
            // Refresh pinned off-viewport set in case expansion above absorbed a pinned row into
            // the mounted window.
            collectPinnedOffViewportIndices(
                itemProvider = itemProvider,
                firstMounted = firstMountedIndex,
                lastMounted = lastMountedIndex,
                count = count,
            )
            scrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollOffsetPx.toFloat())
        }

        // Clamp final scroll offset and build placement + visible-item snapshots.
        val finalMaxScroll = (heightIndex.totalHeightPx - viewportHeight).coerceAtLeast(0)
        scrollOffsetPx = scrollOffsetPx.coerceIn(0f, finalMaxScroll.toFloat())
        val finalScrollInt = scrollOffsetPx.roundToInt()
        updateScrollBoundsFlags()

        val visibleSnapshot = ArrayList<VirtualTimelineVisibleItem>(lastMountedIndex - firstMountedIndex + 1 + pinnedIndicesScratch.size)

        for (i in firstMountedIndex..lastMountedIndex) {
            val placeables = passPlaceables[i] ?: continue
            val itemHeight = passMeasuredHeights[i] ?: 0
            val y = heightIndex.offsetOf(i) - finalScrollInt
            placedRowsScratch.add(PlacedRowEntry(index = i, placeables = placeables, yOffsetPx = y))
            if (y + itemHeight > 0 && y < viewportHeight) {
                visibleSnapshot.add(
                    VirtualTimelineVisibleItem(
                        index = i,
                        key = itemProvider.getKey(i),
                        offsetPx = y,
                        sizePx = itemHeight,
                        isPinnedOffViewport = false,
                    )
                )
            }
        }

        for (p in 0 until pinnedIndicesScratch.size) {
            val pinnedIndex = pinnedIndicesScratch[p]
            val placeables = passPlaceables[pinnedIndex] ?: continue
            val itemHeight = passMeasuredHeights[pinnedIndex] ?: 0
            val rawY = heightIndex.offsetOf(pinnedIndex) - finalScrollInt
            // Keep off-viewport pinned items strictly outside [0, viewportHeight) so clipToBounds
            // hides them cleanly while preserving full composition/LayoutNode attachment.
            val clampedOffViewportY =
                if (pinnedIndex < firstMountedIndex) {
                    min(rawY, -itemHeight - 1)
                } else {
                    max(rawY, viewportHeight + 1)
                }
            placedRowsScratch.add(PlacedRowEntry(index = pinnedIndex, placeables = placeables, yOffsetPx = clampedOffViewportY))
            visibleSnapshot.add(
                VirtualTimelineVisibleItem(
                    index = pinnedIndex,
                    key = itemProvider.getKey(pinnedIndex),
                    offsetPx = clampedOffViewportY,
                    sizePx = itemHeight,
                    isPinnedOffViewport = true,
                )
            )
        }

        lastMeasuredItemCount = passPlaceables.size
        lastPinnedOffViewportCount = pinnedIndicesScratch.size
        lastVisibleItems = visibleSnapshot

        // Edge auto-load callbacks stay suppressed during jump settling and until user scroll.
        if (!isAutoLoadSuppressed && !isJumpSettling) {
            if (onNearStartEdge != null && finalScrollInt <= edgeLoadThresholdPx) {
                onNearStartEdge()
            }
            if (onNearEndEdge != null && (finalMaxScroll - finalScrollInt) <= edgeLoadThresholdPx) {
                onNearEndEdge()
            }
        }

        return scope.layout(viewportWidth, viewportHeight) {
            for (r in 0 until placedRowsScratch.size) {
                val entry = placedRowsScratch[r]
                var childY = entry.yOffsetPx
                val placeables = entry.placeables
                for (p in 0 until placeables.size) {
                    val placeable = placeables[p]
                    placeable.place(x = 0, y = childY)
                    childY += placeable.height
                }
            }
        }
    }

    private fun collectPinnedOffViewportIndices(
        itemProvider: LazyLayoutItemProvider,
        firstMounted: Int,
        lastMounted: Int,
        count: Int,
    ) {
        pinnedIndicesScratch.clear()
        pinnedKeySetScratch.clear()
        for (key in explicitPinnedKeys) {
            pinnedKeySetScratch.add(key)
        }
        for (i in 0 until pinnedItemList.size) {
            val pinned = pinnedItemList[i]
            val pinnedKey = pinned.key
            if (pinnedKey != null) {
                pinnedKeySetScratch.add(pinnedKey)
            } else {
                val idx = pinned.index
                if (idx in 0 until count && (idx < firstMounted || idx > lastMounted)) {
                    if (idx !in pinnedIndicesScratch) {
                        pinnedIndicesScratch.add(idx)
                    }
                }
            }
        }
        for (key in pinnedKeySetScratch) {
            val idx = itemProvider.getIndex(key)
            if (idx in 0 until count && (idx < firstMounted || idx > lastMounted)) {
                if (idx !in pinnedIndicesScratch) {
                    pinnedIndicesScratch.add(idx)
                }
            }
        }
    }

    companion object {
        const val DefaultOverscanPx: Int = 240
        const val JumpAlignmentTolerancePx: Int = 1
        private const val MaxJumpSettlePasses: Int = 3

        internal fun desiredViewportTopPx(
            itemHeightPx: Int,
            viewportHeightPx: Int,
            align: VirtualTimelineAlign,
        ): Int =
            when (align) {
                VirtualTimelineAlign.Top -> 0
                VirtualTimelineAlign.Center ->
                    if (itemHeightPx >= viewportHeightPx) 0 else (viewportHeightPx - itemHeightPx) / 2
                VirtualTimelineAlign.End ->
                    (viewportHeightPx - itemHeightPx).coerceAtLeast(0)
            }
    }
}

@Composable
internal fun rememberVirtualMessageTimelineState(
    estimatedItemHeight: Dp = 72.dp,
    overscan: Dp = 240.dp,
): VirtualMessageTimelineState {
    val density = LocalDensity.current
    val estimatedPx = with(density) { estimatedItemHeight.roundToPx().coerceAtLeast(1) }
    val overscanPx = with(density) { overscan.roundToPx().coerceAtLeast(0) }
    val state = remember { VirtualMessageTimelineState(estimatedPx, overscanPx) }
    state.heightIndex.updateEstimatedItemHeight(estimatedPx)
    state.overscanPx = overscanPx
    return state
}
