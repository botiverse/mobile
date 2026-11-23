package build.raft.app.thread.experiment

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import build.raft.app.topVisibleItem
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Vertical alignment target for [VirtualMessageTimelineState.jumpToKey]. */
internal enum class VirtualTimelineAlign {
    Top,
    Center,
    End,
    FocusedThreadReplies,
}

/** Snapshot of one mounted/laid-out row from the latest measure pass. */
internal data class VirtualTimelineVisibleItem(
    val index: Int,
    val key: Any,
    val offsetPx: Int,
    val sizePx: Int,
    val isPinnedOffViewport: Boolean = false,
    val anchorable: Boolean = true,
)

internal data class VirtualTimelineLazyItemInfo(
    override val index: Int,
    override val key: Any,
    override val offset: Int,
    override val size: Int,
    override val contentType: Any?,
) : LazyListItemInfo

internal class VirtualTimelineLayoutInfo(
    override val visibleItemsInfo: List<LazyListItemInfo>,
    override val viewportStartOffset: Int,
    override val viewportEndOffset: Int,
    override val totalItemsCount: Int,
    override val viewportSize: IntSize,
    override val beforeContentPadding: Int,
    override val afterContentPadding: Int,
) : LazyListLayoutInfo {
    override val orientation: Orientation = Orientation.Vertical
    override val reverseLayout: Boolean = false
    override val mainAxisItemSpacing: Int = 0
}

private val EmptyVirtualTimelineLayoutInfo =
    VirtualTimelineLayoutInfo(
        visibleItemsInfo = emptyList(),
        viewportStartOffset = 0,
        viewportEndOffset = 0,
        totalItemsCount = 0,
        viewportSize = IntSize.Zero,
        beforeContentPadding = 0,
        afterContentPadding = 0,
    )

private sealed interface PendingJumpRequest {
    val remainingSettlePasses: Int

    data class ByKey(
        val key: Any,
        val align: VirtualTimelineAlign,
        val reserveBottomPx: Int = 0,
        override val remainingSettlePasses: Int,
    ) : PendingJumpRequest

    data class ByIndexOffset(
        val index: Int,
        val scrollOffsetPx: Int,
        override val remainingSettlePasses: Int,
    ) : PendingJumpRequest

    data class ToBottom(
        override val remainingSettlePasses: Int,
    ) : PendingJumpRequest
}

private data class PlacedRowEntry(
    val index: Int,
    val placeables: List<Placeable>,
    val yOffsetPx: Int,
)

/**
 * State holder for the `LazyLayout`-based [VirtualMessageTimeline] (full E2E experiment `#proj-mobile:#268`).
 *
 * Architectural properties ported from web `VirtualMessageTimeline.tsx` + `@botiverse/raft-ui`
 * `VirtualList` and `fluid-scroll`:
 * 1. **Hot-path decoupling**: Scroll deltas update [scrollOffsetPx] and request layout directly via
 *    [Remeasurement.forceRemeasure] without invalidating parent composition or unchanged item bodies.
 * 2. **Key-indexed persistent height cache + Fenwick tree** ([heightIndex]): O(log N) prefix-sum
 *    offsets & binary-lifting visible-range queries with zero hot-path heap allocations.
 * 3. **Single-pass pre-draw jump & tail convergence**: [jumpToKey], [scrollToIndexWithOffset], and
 *    [scrollToBottom] resolve target + neighbors in one measure pass (`< 2px` tolerance) before draw,
 *    with optional bottom-up tail pinning ([followBottom]) across appends, height growth, and IME
 *    viewport shrinks without needing `reverseLayout = true`.
 * 4. **Native prepend & height-delta anchor preservation**: When not following bottom, prepends and
 *    above-anchor/tapped-row height changes compensate [scrollOffsetPx] in the same measure pass
 *    before draw, eliminating `onGloballyPositioned` reading-anchor hacks.
 * 5. **Pinned off-viewport slots** ([explicitPinnedKeys] + [pinnedItemList]): Keeps active in-place
 *    text selection, open menus, and focused rows alive off-viewport without disposing them.
 * 6. **Reactive `LazyListLayoutInfo` snapshot** ([layoutInfo]): Enables seamless integration with
 *    existing `snapshotFlow` / `derivedStateOf` observers (read-tail, jump pills, outline, header
 *    original-row tracking, open-trace recorder) without triggering parent recompositions.
 */
@Stable
internal class VirtualMessageTimelineState(
    initialEstimatedItemHeightPx: Int = VirtualTimelineHeightIndex.DefaultEstimatedItemHeightPx,
    initialOverscanPx: Int = DefaultOverscanPx,
    initialFollowBottom: Boolean = false,
    initialJumpKey: Any? = null,
    initialJumpAlign: VirtualTimelineAlign = VirtualTimelineAlign.Top,
    initialJumpReserveBottomPx: Int = 0,
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
     * `LazyListState.measurementScopeInvalidator`.
     */
    internal var measurementInvalidator: Int by mutableIntStateOf(0)
        private set

    var scrollOffsetPx: Float = 0f
        private set

    val scrollOffsetInt: Int
        get() = scrollOffsetPx.roundToInt()

    var viewportHeightPx: Int = 0
        private set

    private var topPaddingPx: Int = 0
    private var bottomPaddingPx: Int = 0

    var overscanPx: Int = initialOverscanPx.coerceAtLeast(0)

    val maxScrollOffsetPx: Int
        get() = (topPaddingPx + heightIndex.totalHeightPx + bottomPaddingPx - viewportHeightPx).coerceAtLeast(0)

    val totalEstimatedHeightPx: Int
        get() = heightIndex.totalHeightPx

    override var canScrollForward: Boolean by mutableStateOf(false)
        private set

    override var canScrollBackward: Boolean by mutableStateOf(false)
        private set

    var isJumpSettling: Boolean by mutableStateOf(initialJumpKey != null || initialFollowBottom)
        private set

    /**
     * Suppresses edge auto-load pagination callbacks while a programmatic jump settles and until
     * the next real user scroll gesture (mirrors web `sentinelAutoLoadBlockKey`).
     */
    var isAutoLoadSuppressed: Boolean by mutableStateOf(initialJumpKey != null)
        private set

    /**
     * When true (and no jump is pending), content growth (appends, image decode, typing spacer) and
     * viewport shrinks (IME rising) keep the tail pinned flush to the bottom in the same measure pass.
     */
    var followBottom: Boolean = initialFollowBottom && initialJumpKey == null
        private set

    /** Optional tapped row key kept visually pinned across in-place height changes when not following bottom. */
    var pinnedReadingAnchorKey: Any? = null
        private set

    var explicitPinnedKeys: Set<Any> = emptySet()
        private set

    /** Observable `LazyListLayoutInfo` snapshot updated once per measure pass (read in snapshotFlow/derivedStateOf). */
    var layoutInfo: LazyListLayoutInfo by mutableStateOf(EmptyVirtualTimelineLayoutInfo)
        private set

    val firstVisibleItemIndex: Int
        get() = topVisibleItem(layoutInfo)?.index ?: 0

    val firstVisibleItemScrollOffset: Int
        get() = topVisibleItem(layoutInfo)?.let { (-it.offset).coerceAtLeast(0) } ?: 0

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

    private var pendingJump: PendingJumpRequest? =
        when {
            initialJumpKey != null ->
                PendingJumpRequest.ByKey(
                    key = initialJumpKey,
                    align = initialJumpAlign,
                    reserveBottomPx = initialJumpReserveBottomPx.coerceAtLeast(0),
                    remainingSettlePasses = MaxJumpSettlePasses,
                )
            initialFollowBottom -> PendingJumpRequest.ToBottom(MaxJumpSettlePasses)
            else -> null
        }
    private var transientAnchorKey: Any? = null
    private var transientAnchorViewportTopPx: Int = 0
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
     * away from the top and not following bottom / jumping, preserves the reading anchor's screen Y
     * position (web `keepAnchorOnItemsChange`).
     */
    fun syncKeys(
        keys: List<Any>,
        preserveAnchorOnPrepend: Boolean = true,
        triggerMeasure: Boolean = true,
    ) {
        val shouldPreserveAnchor =
            preserveAnchorOnPrepend && pendingJump == null && !followBottom && lastVisibleItems.isNotEmpty()
        val previousVisible = if (shouldPreserveAnchor) lastVisibleItems else emptyList()
        val changed = heightIndex.syncKeys(keys)
        if (!changed) return

        if (followBottom && pendingJump == null) {
            transientAnchorKey = null
            scrollOffsetPx = maxScrollOffsetPx.toFloat()
        } else if (shouldPreserveAnchor) {
            val preferred = pinnedReadingAnchorKey
            val preferredCandidate =
                if (preferred != null) {
                    previousVisible.firstOrNull { item ->
                        !item.isPinnedOffViewport &&
                            item.key == preferred &&
                            heightIndex.indexOfKey(item.key) >= 0
                    }
                } else {
                    null
                }
            val chosen =
                preferredCandidate
                    ?: previousVisible.firstOrNull { item ->
                        !item.isPinnedOffViewport &&
                            item.anchorable &&
                            item.offsetPx >= 0 &&
                            heightIndex.indexOfKey(item.key) >= 0
                    }
                    ?: previousVisible.firstOrNull { item ->
                        !item.isPinnedOffViewport &&
                            item.anchorable &&
                            item.offsetPx + item.sizePx > 0 &&
                            heightIndex.indexOfKey(item.key) >= 0
                    }
                    ?: previousVisible.firstOrNull { item ->
                        !item.isPinnedOffViewport &&
                            item.offsetPx >= 0 &&
                            heightIndex.indexOfKey(item.key) >= 0
                    }
                    ?: previousVisible.firstOrNull { item ->
                        !item.isPinnedOffViewport &&
                            item.offsetPx + item.sizePx > 0 &&
                            heightIndex.indexOfKey(item.key) >= 0
                    }
            if (chosen != null) {
                val newIndex = heightIndex.indexOfKey(chosen.key)
                if (newIndex >= 0) {
                    val restoredScroll =
                        (heightIndex.offsetOf(newIndex) - chosen.offsetPx)
                            .coerceIn(0, maxScrollOffsetPx)
                    scrollOffsetPx = restoredScroll.toFloat()
                    transientAnchorKey = chosen.key
                    transientAnchorViewportTopPx = chosen.offsetPx
                }
            }
        } else if (pendingJump == null) {
            transientAnchorKey = null
            scrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollOffsetPx.toFloat())
        }
        if (triggerMeasure) {
            updateScrollBoundsFlags()
            invalidateMeasureSafe()
        }
    }

    /** Updates explicit pinned row keys (active in-place selection, open menus, media keep-alive). */
    fun updatePinnedKeys(keys: Set<Any>) {
        if (explicitPinnedKeys == keys) return
        explicitPinnedKeys = keys
        // Safe during BoxWithConstraints/SubcomposeLayout SideEffect: invalidates measure scope
        // without calling remeasurement.forceRemeasure() re-entrantly during measureAndLayout.
        invalidateMeasureSafe()
    }

    fun setFollowBottom(enabled: Boolean) {
        if (followBottom == enabled) return
        followBottom = enabled
        if (enabled) {
            pinnedReadingAnchorKey = null
            transientAnchorKey = null
        }
    }

    fun pinReadingAnchorKey(key: Any?) {
        pinnedReadingAnchorKey = key
        if (key != null) {
            followBottom = false
        }
    }

    /**
     * Jumps accurately to [key] with [align] alignment (and optional [reserveBottomPx] for
     * [VirtualTimelineAlign.FocusedThreadReplies] or [VirtualTimelineAlign.End] trailing space).
     */
    fun jumpToKey(
        key: Any,
        align: VirtualTimelineAlign = VirtualTimelineAlign.Center,
        reserveBottomPx: Int = 0,
        suppressAutoLoad: Boolean = true,
    ) {
        followBottom = false
        pinnedReadingAnchorKey = null
        transientAnchorKey = null
        pendingJump =
            PendingJumpRequest.ByKey(
                key = key,
                align = align,
                reserveBottomPx = reserveBottomPx.coerceAtLeast(0),
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
            val availableHeight = (viewportHeightPx - topPaddingPx).coerceAtLeast(1)
            val desiredTop = desiredViewportTopPx(estimatedHeight, availableHeight, align, reserveBottomPx.coerceAtLeast(0))
            scrollOffsetPx = (estimatedTop - desiredTop).coerceIn(0, maxScrollOffsetPx).toFloat()
            updateScrollBoundsFlags()
        }
        requestMeasure()
    }

    fun jumpToIndex(
        index: Int,
        align: VirtualTimelineAlign = VirtualTimelineAlign.Center,
        reserveBottomPx: Int = 0,
        suppressAutoLoad: Boolean = true,
    ) {
        if (index !in 0 until heightIndex.itemCount) return
        jumpToKey(
            key = heightIndex.keyAt(index),
            align = align,
            reserveBottomPx = reserveBottomPx,
            suppressAutoLoad = suppressAutoLoad,
        )
    }

    /**
     * Forward `LazyListState.scrollToItem(index, scrollOffset)` equivalent: places row [index] so
     * its forward top offset (relative to content start after top padding) equals `-scrollOffsetPx`,
     * settling in a single measure pass before draw.
     */
    fun scrollToIndexWithOffset(
        index: Int,
        scrollOffsetPx: Int = 0,
        suppressAutoLoad: Boolean = false,
    ) {
        if (heightIndex.itemCount == 0) return
        val clampedIndex = index.coerceIn(0, heightIndex.itemCount - 1)
        followBottom = false
        pinnedReadingAnchorKey = null
        transientAnchorKey = null
        pendingJump =
            PendingJumpRequest.ByIndexOffset(
                index = clampedIndex,
                scrollOffsetPx = scrollOffsetPx,
                remainingSettlePasses = MaxJumpSettlePasses,
            )
        isJumpSettling = true
        lastJumpConvergencePasses = 0
        if (suppressAutoLoad) {
            isAutoLoadSuppressed = true
        }
        if (viewportHeightPx > 0) {
            val estimatedTop = heightIndex.offsetOf(clampedIndex)
            this.scrollOffsetPx = (estimatedTop + scrollOffsetPx).coerceIn(0, maxScrollOffsetPx).toFloat()
            updateScrollBoundsFlags()
        }
        requestMeasure()
    }

    fun scrollToTop(suppressAutoLoad: Boolean = false) {
        if (heightIndex.itemCount == 0) return
        jumpToIndex(0, align = VirtualTimelineAlign.Top, suppressAutoLoad = suppressAutoLoad)
    }

    fun scrollToBottom(suppressAutoLoad: Boolean = false) {
        pinnedReadingAnchorKey = null
        transientAnchorKey = null
        followBottom = true
        pendingJump = PendingJumpRequest.ToBottom(remainingSettlePasses = MaxJumpSettlePasses)
        isJumpSettling = true
        lastJumpConvergencePasses = 0
        if (suppressAutoLoad) {
            isAutoLoadSuppressed = true
        }
        if (viewportHeightPx > 0) {
            scrollOffsetPx = maxScrollOffsetPx.toFloat()
            updateScrollBoundsFlags()
        }
        requestMeasure()
    }

    fun clearAutoLoadSuppression() {
        isAutoLoadSuppressed = false
    }

    /** Called on a real user drag gesture: cancels any settling jump and lifts the pagination barrier. */
    fun notifyUserScrollGesture() {
        pinnedReadingAnchorKey = null
        transientAnchorKey = null
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

    suspend fun programmaticScrollBy(deltaPx: Float): Float {
        transientAnchorKey = null
        if (deltaPx < 0f) {
            followBottom = false
        }
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

    suspend fun programmaticAnimateScrollBy(deltaPx: Float): Float {
        if (deltaPx == 0f || heightIndex.itemCount == 0) return 0f
        transientAnchorKey = null
        if (deltaPx < 0f) {
            followBottom = false
        }
        programmaticScrollDepth += 1
        return try {
            animateScrollBy(deltaPx)
        } finally {
            programmaticScrollDepth -= 1
        }
    }

    suspend fun animateScrollToIndex(index: Int, scrollOffsetPx: Int = 0) {
        if (heightIndex.itemCount == 0) return
        val clampedIndex = index.coerceIn(0, heightIndex.itemCount - 1)
        followBottom = false
        val targetOffset = (heightIndex.offsetOf(clampedIndex) + scrollOffsetPx).coerceIn(0, maxScrollOffsetPx).toFloat()
        val delta = targetOffset - this.scrollOffsetPx
        if (abs(delta) > 1f) {
            programmaticAnimateScrollBy(delta)
        }
        scrollToIndexWithOffset(clampedIndex, scrollOffsetPx)
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
            pinnedReadingAnchorKey = null
            transientAnchorKey = null
            if (delta < 0f) {
                followBottom = false
            }
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
        requestMeasure()
        return consumed
    }

    private fun invalidateMeasureSafe() {
        measurementInvalidator += 1
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
        topPaddingPx: Int = 0,
        bottomPaddingPx: Int = 0,
        startPaddingPx: Int = 0,
        endPaddingPx: Int = 0,
        sparseAnchorBottom: Boolean = false,
        isAnchorable: (Int) -> Boolean = { true },
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

        val safeTopPadding = topPaddingPx.coerceAtLeast(0)
        val safeBottomPadding = bottomPaddingPx.coerceAtLeast(0)
        val safeStartPadding = startPaddingPx.coerceAtLeast(0)
        val safeEndPadding = endPaddingPx.coerceAtLeast(0)
        this.topPaddingPx = safeTopPadding
        this.bottomPaddingPx = safeBottomPadding

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
                (safeTopPadding + heightIndex.totalHeightPx + safeBottomPadding)
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
            }
        viewportHeightPx = viewportHeight

        if (count == 0 || viewportHeight <= 0) {
            scrollOffsetPx = 0f
            lastMeasuredItemCount = 0
            lastPinnedOffViewportCount = 0
            lastVisibleItems = emptyList()
            updateScrollBoundsFlags()
            layoutInfo =
                VirtualTimelineLayoutInfo(
                    visibleItemsInfo = emptyList(),
                    viewportStartOffset = -safeTopPadding,
                    viewportEndOffset = viewportHeight - safeTopPadding,
                    totalItemsCount = 0,
                    viewportSize = IntSize(viewportWidth, viewportHeight),
                    beforeContentPadding = safeTopPadding,
                    afterContentPadding = safeBottomPadding,
                )
            return scope.layout(viewportWidth, viewportHeight) {}
        }

        val itemWidth = (viewportWidth - safeStartPadding - safeEndPadding).coerceAtLeast(0)
        val itemConstraints =
            Constraints(
                minWidth = itemWidth,
                maxWidth = itemWidth,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )
        val availableViewportHeight = (viewportHeight - safeTopPadding).coerceAtLeast(1)

        fun currentMaxScroll(): Int =
            (safeTopPadding + heightIndex.totalHeightPx + safeBottomPadding - viewportHeight).coerceAtLeast(0)

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
        val activeJumpIndex: Int =
            when (activeJump) {
                is PendingJumpRequest.ByKey -> itemProvider.getIndex(activeJump.key)
                is PendingJumpRequest.ByIndexOffset -> activeJump.index.takeIf { it in 0 until count } ?: -1
                is PendingJumpRequest.ToBottom -> count - 1
                null -> -1
            }

        var firstMountedIndex: Int
        var lastMountedIndex: Int

        if (activeJump is PendingJumpRequest.ToBottom || (activeJump == null && followBottom && !isScrollInProgress)) {
            // Bottom-up tail convergence: measures from newest row upward so cold opens, realtime
            // appends, async image growth, and IME viewport shrinks all stay pinned flush to the
            // bottom in a single measure pass before draw.
            lastMountedIndex = count - 1
            var filled = safeBottomPadding
            val fillLimit = viewportHeight + overscanPx
            var cursorUp = count - 1
            while (cursorUp >= 0 && filled < fillLimit) {
                filled += measureItemOnce(cursorUp)
                cursorUp -= 1
            }
            firstMountedIndex = (cursorUp + 1).coerceIn(0, lastMountedIndex)

            collectPinnedOffViewportIndices(
                itemProvider = itemProvider,
                firstMounted = firstMountedIndex,
                lastMounted = lastMountedIndex,
                count = count,
            )
            for (i in 0 until pinnedIndicesScratch.size) {
                measureItemOnce(pinnedIndicesScratch[i])
            }

            scrollOffsetPx = currentMaxScroll().toFloat()
            if (activeJump is PendingJumpRequest.ToBottom) {
                lastJumpAlignmentErrorPx = 0
                lastJumpConvergencePasses += 1
                pendingJump = null
                isJumpSettling = false
            }
        } else if (activeJump != null && activeJumpIndex in 0 until count) {
            // Single-pass exact convergence: measure target + neighbors around desired alignment,
            // updating the Fenwick tree in-place so target lands within < 2px before draw.
            val targetHeight = measureItemOnce(activeJumpIndex)
            val desiredTop =
                when (activeJump) {
                    is PendingJumpRequest.ByKey ->
                        desiredViewportTopPx(
                            itemHeightPx = targetHeight,
                            viewportHeightPx = availableViewportHeight,
                            align = activeJump.align,
                            reserveBottomPx = activeJump.reserveBottomPx,
                        )
                    is PendingJumpRequest.ByIndexOffset -> -activeJump.scrollOffsetPx
                    is PendingJumpRequest.ToBottom -> 0
                }

            var aboveFill = 0
            val aboveLimit = (desiredTop + safeTopPadding).coerceAtLeast(0) + overscanPx
            var cursorUp = activeJumpIndex - 1
            while (cursorUp >= 0 && aboveFill < aboveLimit) {
                aboveFill += measureItemOnce(cursorUp)
                cursorUp -= 1
            }
            firstMountedIndex = cursorUp + 1

            var belowFill = 0
            val belowLimit = (availableViewportHeight - desiredTop - targetHeight).coerceAtLeast(0) + overscanPx
            var cursorDown = activeJumpIndex + 1
            while (cursorDown < count && belowFill < belowLimit) {
                belowFill += measureItemOnce(cursorDown)
                cursorDown += 1
            }
            lastMountedIndex = cursorDown - 1

            var resolvedScroll =
                (heightIndex.offsetOf(activeJumpIndex) - desiredTop).coerceIn(0, currentMaxScroll())

            if (resolvedScroll == 0 && firstMountedIndex > 0) {
                while (firstMountedIndex > 0) {
                    firstMountedIndex -= 1
                    measureItemOnce(firstMountedIndex)
                }
                resolvedScroll =
                    (heightIndex.offsetOf(activeJumpIndex) - desiredTop).coerceIn(0, currentMaxScroll())
            }
            var contentEndOfMounted =
                heightIndex.offsetOf(lastMountedIndex) + heightIndex.heightOf(lastMountedIndex)
            while (lastMountedIndex + 1 < count && contentEndOfMounted < resolvedScroll + availableViewportHeight + overscanPx) {
                lastMountedIndex += 1
                measureItemOnce(lastMountedIndex)
                resolvedScroll =
                    (heightIndex.offsetOf(activeJumpIndex) - desiredTop).coerceIn(0, currentMaxScroll())
                contentEndOfMounted =
                    heightIndex.offsetOf(lastMountedIndex) + heightIndex.heightOf(lastMountedIndex)
            }
            var contentStartOfMounted = heightIndex.offsetOf(firstMountedIndex)
            while (firstMountedIndex > 0 && contentStartOfMounted > resolvedScroll - safeTopPadding - overscanPx) {
                firstMountedIndex -= 1
                measureItemOnce(firstMountedIndex)
                resolvedScroll =
                    (heightIndex.offsetOf(activeJumpIndex) - desiredTop).coerceIn(0, currentMaxScroll())
                contentStartOfMounted = heightIndex.offsetOf(firstMountedIndex)
            }
            // If upward measurement dropped resolvedScroll (e.g. near bottom/top edge), ensure bottom stays filled.
            contentEndOfMounted =
                heightIndex.offsetOf(lastMountedIndex) + heightIndex.heightOf(lastMountedIndex)
            while (lastMountedIndex + 1 < count && contentEndOfMounted < resolvedScroll + availableViewportHeight + overscanPx) {
                lastMountedIndex += 1
                measureItemOnce(lastMountedIndex)
                resolvedScroll =
                    (heightIndex.offsetOf(activeJumpIndex) - desiredTop).coerceIn(0, currentMaxScroll())
                contentEndOfMounted =
                    heightIndex.offsetOf(lastMountedIndex) + heightIndex.heightOf(lastMountedIndex)
            }

            collectPinnedOffViewportIndices(
                itemProvider = itemProvider,
                firstMounted = firstMountedIndex,
                lastMounted = lastMountedIndex,
                count = count,
            )
            for (i in 0 until pinnedIndicesScratch.size) {
                measureItemOnce(pinnedIndicesScratch[i])
            }

            val targetContentTop = heightIndex.offsetOf(activeJumpIndex)
            val maxScroll = currentMaxScroll()
            resolvedScroll = (targetContentTop - desiredTop).coerceIn(0, maxScroll)
            scrollOffsetPx = resolvedScroll.toFloat()

            val expectedClampedTop =
                targetContentTop - (targetContentTop - desiredTop).coerceIn(0, maxScroll)
            val actualTop = targetContentTop - resolvedScroll
            val errorPx = abs(actualTop - expectedClampedTop)
            lastJumpAlignmentErrorPx = errorPx
            lastJumpConvergencePasses += 1
            pendingJump = null
            isJumpSettling = false
        } else {
            // Normal scroll / layout pass: O(log N) zero-allocation visible range lookup with
            // in-pass above-anchor height compensation (including one-shot prepend anchor).
            val pendingPrependAnchorKey = transientAnchorKey
            val pendingPrependAnchorTopPx = transientAnchorViewportTopPx
            transientAnchorKey = null

            val currentScrollInt = scrollOffsetPx.roundToInt().coerceIn(0, currentMaxScroll())
            val prependAnchorIdx =
                pendingPrependAnchorKey?.let { key ->
                    val idx = heightIndex.indexOfKey(key)
                    if (idx in 0 until count) idx else -1
                } ?: -1
            val preferredAnchor =
                pinnedReadingAnchorKey?.let { key ->
                    val idx = heightIndex.indexOfKey(key)
                    if (idx in 0 until count) idx else -1
                } ?: -1
            val anchorIndex =
                when {
                    prependAnchorIdx >= 0 -> prependAnchorIdx
                    preferredAnchor >= 0 -> preferredAnchor
                    else -> heightIndex.indexAtOffset(currentScrollInt)
                }
            val anchorContentTopBefore = if (anchorIndex >= 0) heightIndex.offsetOf(anchorIndex) else 0
            val anchorViewportTopBefore =
                if (prependAnchorIdx >= 0) {
                    pendingPrependAnchorTopPx
                } else {
                    anchorContentTopBefore - currentScrollInt
                }
            val shouldCompensateAboveAnchor =
                (currentScrollInt > 0 || prependAnchorIdx >= 0 || preferredAnchor >= 0) && anchorIndex in 0 until count

            val windowTop = (currentScrollInt - safeTopPadding - overscanPx).coerceAtLeast(0)
            val windowBottom = currentScrollInt + availableViewportHeight + overscanPx
            heightIndex.visibleRange(windowTop, windowBottom, scratchVisibleRange)
            firstMountedIndex = scratchVisibleRange[0].coerceIn(0, count - 1)
            lastMountedIndex = scratchVisibleRange[1].coerceIn(firstMountedIndex, count - 1)

            var index = firstMountedIndex
            while (index <= lastMountedIndex) {
                val measuredHeight = measureItemOnce(index)
                // Stop early once measured items past the anchor already fill viewport + overscan,
                // preventing cold estimated ranges from over-composing far-below rows that would
                // be immediately disposed on the next scroll step.
                if (index >= anchorIndex) {
                    val liveScroll =
                        if (shouldCompensateAboveAnchor) {
                            (heightIndex.offsetOf(anchorIndex) - anchorViewportTopBefore).coerceIn(0, currentMaxScroll())
                        } else {
                            currentScrollInt
                        }
                    if (heightIndex.offsetOf(index) + measuredHeight >= liveScroll + availableViewportHeight + overscanPx) {
                        lastMountedIndex = index
                        break
                    }
                }
                index += 1
            }

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
            // (or above the explicitly pinned / prepended reading-anchor row) so async image decodes,
            // older-page prepends, reactions, and edits never jump the reader's screen.
            if (shouldCompensateAboveAnchor) {
                val anchorContentTopAfter = heightIndex.offsetOf(anchorIndex)
                val compensated =
                    (anchorContentTopAfter - anchorViewportTopBefore)
                        .coerceIn(0, currentMaxScroll())
                if (compensated != currentScrollInt) {
                    scrollOffsetPx = compensated.toFloat()
                }
            }

            while (lastMountedIndex + 1 < count) {
                val currentAdjusted = scrollOffsetPx.roundToInt().coerceIn(0, currentMaxScroll())
                val bottomOfLast =
                    heightIndex.offsetOf(lastMountedIndex) + heightIndex.heightOf(lastMountedIndex)
                if (bottomOfLast >= currentAdjusted + availableViewportHeight + overscanPx) break
                lastMountedIndex += 1
                measureItemOnce(lastMountedIndex)
            }
            while (firstMountedIndex > 0) {
                val currentAdjusted = scrollOffsetPx.roundToInt().coerceIn(0, currentMaxScroll())
                val topOfFirst = heightIndex.offsetOf(firstMountedIndex)
                if (topOfFirst <= currentAdjusted - safeTopPadding - overscanPx) break
                firstMountedIndex -= 1
                val prevBefore = if (anchorIndex in 0 until count) heightIndex.offsetOf(anchorIndex) else 0
                measureItemOnce(firstMountedIndex)
                if (shouldCompensateAboveAnchor && firstMountedIndex < anchorIndex) {
                    val deltaAbove = heightIndex.offsetOf(anchorIndex) - prevBefore
                    if (deltaAbove != 0) {
                        scrollOffsetPx = (scrollOffsetPx + deltaAbove).coerceIn(0f, currentMaxScroll().toFloat())
                    }
                }
            }
            collectPinnedOffViewportIndices(
                itemProvider = itemProvider,
                firstMounted = firstMountedIndex,
                lastMounted = lastMountedIndex,
                count = count,
            )
            scrollOffsetPx = scrollOffsetPx.coerceIn(0f, currentMaxScroll().toFloat())
        }

        val totalScrollableHeight = safeTopPadding + heightIndex.totalHeightPx + safeBottomPadding
        val finalMaxScroll = (totalScrollableHeight - viewportHeight).coerceAtLeast(0)
        scrollOffsetPx = scrollOffsetPx.coerceIn(0f, finalMaxScroll.toFloat())
        val finalScrollInt = scrollOffsetPx.roundToInt()
        val sparseBottomOffset =
            if (sparseAnchorBottom && totalScrollableHeight < viewportHeight) {
                viewportHeight - totalScrollableHeight
            } else {
                0
            }
        updateScrollBoundsFlags()

        val capacity = lastMountedIndex - firstMountedIndex + 1 + pinnedIndicesScratch.size
        val visibleSnapshot = ArrayList<VirtualTimelineVisibleItem>(capacity)
        val lazyVisibleItems = ArrayList<LazyListItemInfo>(lastMountedIndex - firstMountedIndex + 1)

        for (i in firstMountedIndex..lastMountedIndex) {
            val placeables = passPlaceables[i] ?: continue
            val itemHeight = passMeasuredHeights[i] ?: 0
            val contentOffset = sparseBottomOffset + heightIndex.offsetOf(i) - finalScrollInt
            val screenY = safeTopPadding + contentOffset
            placedRowsScratch.add(PlacedRowEntry(index = i, placeables = placeables, yOffsetPx = screenY))
            if (screenY + itemHeight > 0 && screenY < viewportHeight) {
                val key = itemProvider.getKey(i)
                visibleSnapshot.add(
                    VirtualTimelineVisibleItem(
                        index = i,
                        key = key,
                        offsetPx = contentOffset,
                        sizePx = itemHeight,
                        isPinnedOffViewport = false,
                        anchorable = isAnchorable(i),
                    )
                )
                lazyVisibleItems.add(
                    VirtualTimelineLazyItemInfo(
                        index = i,
                        key = key,
                        offset = contentOffset,
                        size = itemHeight,
                        contentType = itemProvider.getContentType(i),
                    )
                )
            }
        }

        for (p in 0 until pinnedIndicesScratch.size) {
            val pinnedIndex = pinnedIndicesScratch[p]
            val placeables = passPlaceables[pinnedIndex] ?: continue
            val itemHeight = passMeasuredHeights[pinnedIndex] ?: 0
            val rawScreenY = safeTopPadding + sparseBottomOffset + heightIndex.offsetOf(pinnedIndex) - finalScrollInt
            val clampedOffViewportY =
                if (pinnedIndex < firstMountedIndex) {
                    min(rawScreenY, -itemHeight - 1)
                } else {
                    max(rawScreenY, viewportHeight + 1)
                }
            placedRowsScratch.add(PlacedRowEntry(index = pinnedIndex, placeables = placeables, yOffsetPx = clampedOffViewportY))
            visibleSnapshot.add(
                VirtualTimelineVisibleItem(
                    index = pinnedIndex,
                    key = itemProvider.getKey(pinnedIndex),
                    offsetPx = clampedOffViewportY - safeTopPadding,
                    sizePx = itemHeight,
                    isPinnedOffViewport = true,
                )
            )
        }

        lastMeasuredItemCount = passPlaceables.size
        lastPinnedOffViewportCount = pinnedIndicesScratch.size
        lastVisibleItems = visibleSnapshot
        layoutInfo =
            VirtualTimelineLayoutInfo(
                visibleItemsInfo = lazyVisibleItems,
                viewportStartOffset = -safeTopPadding,
                viewportEndOffset = viewportHeight - safeTopPadding,
                totalItemsCount = count,
                viewportSize = IntSize(viewportWidth, viewportHeight),
                beforeContentPadding = safeTopPadding,
                afterContentPadding = safeBottomPadding,
            )

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
                    placeable.place(x = safeStartPadding, y = childY)
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
            reserveBottomPx: Int = 0,
        ): Int =
            when (align) {
                VirtualTimelineAlign.Top -> 0
                VirtualTimelineAlign.Center ->
                    if (itemHeightPx >= viewportHeightPx) 0 else (viewportHeightPx - itemHeightPx) / 2
                VirtualTimelineAlign.End ->
                    viewportHeightPx - itemHeightPx - reserveBottomPx
                VirtualTimelineAlign.FocusedThreadReplies -> {
                    val reserve =
                        max(reserveBottomPx, (viewportHeightPx * 0.25f).roundToInt())
                            .coerceIn(0, viewportHeightPx / 2)
                    val centredTop = (viewportHeightPx - itemHeightPx) / 2
                    val bottomLimitedTop = viewportHeightPx - reserve - itemHeightPx
                    min(centredTop, bottomLimitedTop)
                }
            }
    }
}

@Composable
internal fun rememberVirtualMessageTimelineState(
    key: Any? = Unit,
    estimatedItemHeight: Dp = 72.dp,
    overscan: Dp = 240.dp,
    initialFollowBottom: Boolean = false,
    initialJumpKey: Any? = null,
    initialJumpAlign: VirtualTimelineAlign = VirtualTimelineAlign.Top,
    initialJumpReserveBottomPx: Int = 0,
): VirtualMessageTimelineState {
    val density = LocalDensity.current
    val estimatedPx = with(density) { estimatedItemHeight.roundToPx().coerceAtLeast(1) }
    val overscanPx = with(density) { overscan.roundToPx().coerceAtLeast(0) }
    val state =
        remember(key) {
            VirtualMessageTimelineState(
                initialEstimatedItemHeightPx = estimatedPx,
                initialOverscanPx = overscanPx,
                initialFollowBottom = initialFollowBottom,
                initialJumpKey = initialJumpKey,
                initialJumpAlign = initialJumpAlign,
                initialJumpReserveBottomPx = initialJumpReserveBottomPx,
            )
        }
    state.heightIndex.updateEstimatedItemHeight(estimatedPx)
    state.overscanPx = overscanPx
    return state
}
