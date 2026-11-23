package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftMessageListJumpPillGroup
import build.raft.designsystem.components.RaftMessageListJumpPillDismiss
import build.raft.designsystem.components.RaftMessageListJumpPill
import build.raft.designsystem.components.RaftGlyph
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.onClick
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val ThreadFocusedMessageCenterAttempts = 6

/**
 * Phase 1 (#172) instrumentation helper: monotonic counter that yields
 * a fresh session id for each Thread entry. Keeping this as a separate
 * object (instead of a static field on a Composable scope) avoids any
 * recomposition surprises.
 */
internal object ThreadRowProbeNextSessionId {
    private var counter: Int = 0
    fun value(): String {
        counter += 1
        return "threadRowProbeSession-${counter}"
    }
}

/**
 * One-shot first-viewport probe for #202.
 *
 * This deliberately keeps mutable counters outside Compose state: the values are
 * diagnostic-only and must not invalidate row composition while measuring it.
 */
internal class ThreadFirstViewportProbe(
    val sessionMarker: String
) {
    private val composedMessageKeys = mutableSetOf<String>()
    private val placedMessageKeys = mutableSetOf<String>()
    private var firstComposedMessageIndex: Int = Int.MAX_VALUE
    private var lastComposedMessageIndex: Int = -1
    private var firstPlacedMessageIndex: Int = Int.MAX_VALUE
    private var lastPlacedMessageIndex: Int = -1
    private var logged: Boolean = false

    fun recordComposedMessage(messageId: String, index: Int) {
        if (composedMessageKeys.add(messageId)) {
            firstComposedMessageIndex = minOf(firstComposedMessageIndex, index)
            lastComposedMessageIndex = maxOf(lastComposedMessageIndex, index)
        }
    }

    fun recordPlacedMessage(messageId: String, index: Int) {
        if (placedMessageKeys.add(messageId)) {
            firstPlacedMessageIndex = minOf(firstPlacedMessageIndex, index)
            lastPlacedMessageIndex = maxOf(lastPlacedMessageIndex, index)
        }
    }

    fun markLogged(): Boolean {
        if (logged) return false
        logged = true
        return true
    }

    fun toDiagnosticsString(
        result: String,
        instanceId: String,
        timelineSlice: ThreadTimelineSlice,
        lazyItemCount: Int,
        stats: ThreadLazyWindowStats
    ): String =
        "thread_first_viewport_probe result=$result sessionMarker=$sessionMarker " +
            "threadId=${timelineSlice.sourceThreadId.orEmpty()} instanceId=$instanceId " +
            "originStatus=${timelineSlice.originStatus} hasOrigin=${timelineSlice.originMessage != null} " +
            "messagesTotal=${timelineSlice.messages.size} totalItems=$lazyItemCount " +
            "beyondBounds=${stats.beyondBoundsItems} " +
            "composedMessageRows=${composedMessageKeys.size} " +
            "composedMessageRange=${firstComposedMessageIndex.asThreadProbeRangeStart()}..$lastComposedMessageIndex " +
            "placedMessageRows=${placedMessageKeys.size} " +
            "placedMessageRange=${firstPlacedMessageIndex.asThreadProbeRangeStart()}..$lastPlacedMessageIndex " +
            "visibleItems=${stats.visibleItems} visibleItemRange=${stats.firstVisibleItem}..${stats.lastVisibleItem} " +
            "visibleMessages=${stats.visibleMessages} " +
            "visibleMessageRange=${stats.firstVisibleMessage}..${stats.lastVisibleMessage}"
}

internal class ThreadImeTailDrawProbe {
    private var nextGeneration: Long = 0L
    private var activeGeneration: Long = 0L
    private var targetMessageId: String? = null
    private var placedCount: Int = 0
    private var drawCount: Int = 0
    private var pixelProbeClaimedGeneration: Long = 0L

    fun beginOrContinue(targetMessageId: String?): Long {
        if (activeGeneration == 0L || this.targetMessageId != targetMessageId) {
            nextGeneration += 1L
            activeGeneration = nextGeneration
            this.targetMessageId = targetMessageId
            placedCount = 0
            drawCount = 0
            pixelProbeClaimedGeneration = 0L
        }
        return activeGeneration
    }

    fun recordPlaced(messageId: String) {
        if (messageId == targetMessageId && activeGeneration > 0L) placedCount += 1
    }

    fun recordDraw(messageId: String) {
        if (messageId == targetMessageId && activeGeneration > 0L) drawCount += 1
    }

    fun snapshot(): ThreadImeTailDrawProbeSnapshot =
        ThreadImeTailDrawProbeSnapshot(
            generation = activeGeneration,
            targetMessageId = targetMessageId,
            placedCount = placedCount,
            drawCount = drawCount
        )

    fun claimPixelProbe(): Long? =
        activeGeneration.takeIf { generation ->
            generation > 0L && pixelProbeClaimedGeneration != generation
        }?.also { generation -> pixelProbeClaimedGeneration = generation }

    fun finish() {
        activeGeneration = 0L
        targetMessageId = null
        placedCount = 0
        drawCount = 0
        pixelProbeClaimedGeneration = 0L
    }
}

internal class ThreadViewportPixelProbeBounds {
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set
    var width: Float = 0f
        private set
    var height: Float = 0f
        private set

    fun update(coordinates: LayoutCoordinates) {
        val position = coordinates.positionInRoot()
        x = position.x
        y = position.y
        width = coordinates.size.width.toFloat()
        height = coordinates.size.height.toFloat()
    }

    fun ready(): Boolean = width > 0f && height > 0f
}

internal data class ThreadImeTailDrawProbeSnapshot(
    val generation: Long,
    val targetMessageId: String?,
    val placedCount: Int,
    val drawCount: Int
)

internal class ThreadImeTailPixelProbeRunner(
    private val scope: CoroutineScope
) {
    fun launch(
        awaitSettlement: suspend () -> Unit,
        runProbe: suspend () -> Unit
    ): Job =
        scope.launch {
            awaitSettlement()
            runProbe()
        }
}

internal fun threadViewportPixelProbeOwnerCurrent(
    requestedThreadInstanceKey: String,
    currentThreadInstanceKey: String,
    requestedLayoutGeneration: Long,
    currentLayoutGeneration: Long,
    requestedProbeGeneration: Long,
    currentProbeGeneration: Long,
    requestedTargetMessageId: String?,
    currentTargetMessageId: String?,
    requestedFocusedMessageId: String?,
    currentFocusedMessageId: String?,
    requestedTailMessageId: String?,
    currentTailMessageId: String?
): Boolean =
    requestedThreadInstanceKey == currentThreadInstanceKey &&
        requestedLayoutGeneration == currentLayoutGeneration &&
        requestedProbeGeneration == currentProbeGeneration &&
        requestedTargetMessageId == currentTargetMessageId &&
        requestedFocusedMessageId == currentFocusedMessageId &&
        requestedTailMessageId == currentTailMessageId

private fun Int.asThreadProbeRangeStart(): Int =
    if (this == Int.MAX_VALUE) -1 else this

/**
 * Phase 1 instrumentation flag for #172 (Thread virtual rendering v1).
 *
 * When true, each `ThreadMessageCard` / `ThreadSystemMessageRow`
 * composition emits a single `thread_row_compose` log via the shared
 * bridge, gated by a per-row `LaunchedEffect(message.id, sessionMarker)`
 * so the log fires exactly once per row per Thread entry (no scroll
 * spam). This isolates the proof to a one-shot diagnostic path; the
 * flag is meant to be removed / feature-flagged off once the
 * composition-vs-payload contract is verified.
 */
internal const val DEBUG_THREAD_VIRTUAL_RENDER_LOG = false
internal data class ThreadVisibleMessageAnchor(
    val messageId: String,
    val offset: Int,
    val userScrollGeneration: Int
)

internal fun ThreadListViewport.threadFirstVisibleMessageAnchor(
    timelineSlice: ThreadTimelineSlice,
    userScrollGeneration: Int
): ThreadVisibleMessageAnchor? {
    val firstMessageListItemIndex = timelineSlice.firstMessageListItemIndex()
    val messageCount = timelineSlice.messages.size
    if (messageCount <= 0) return null
    val item =
        layoutInfo.visibleItemsInfo
            .firstOrNull { item ->
                item.index >= firstMessageListItemIndex &&
                    item.index < firstMessageListItemIndex + messageCount
            }
            ?: return null
    val message = timelineSlice.messages.getOrNull(item.index - firstMessageListItemIndex) ?: return null
    return ThreadVisibleMessageAnchor(
        messageId = message.id,
        offset = item.offset,
        userScrollGeneration = userScrollGeneration
    )
}

/**
 * The reading anchor a settled drag hands the viewport controller ([threadReadingAnchorItem]): the
 * topmost message whose top is in view, else the first visible message. The controller's
 * HeightChange replays and the [ThreadReadingAnchorKeeper] then keep the same row.
 */
internal fun ThreadListViewport.threadReadingAnchorMessage(
    timelineSlice: ThreadTimelineSlice,
    userScrollGeneration: Int
): ThreadVisibleMessageAnchor? {
    val firstMessageListItemIndex = timelineSlice.firstMessageListItemIndex()
    val messageCount = timelineSlice.messages.size
    if (messageCount <= 0) return null
    val item =
        threadReadingAnchorItem(layoutInfo.visibleItemsInfo) { index ->
            index >= firstMessageListItemIndex && index < firstMessageListItemIndex + messageCount
        } ?: return null
    val message = timelineSlice.messages.getOrNull(item.index - firstMessageListItemIndex) ?: return null
    return ThreadVisibleMessageAnchor(
        messageId = message.id,
        offset = item.offset,
        userScrollGeneration = userScrollGeneration
    )
}

internal fun shouldRestoreThreadOlderPageAnchor(
    capturedUserScrollGeneration: Int,
    currentUserScrollGeneration: Int
): Boolean = capturedUserScrollGeneration == currentUserScrollGeneration

/** Task v2: whether any of the head at the top of the history boundary row is on screen ([taskHeadRowInView]). */
internal fun ThreadListViewport.threadTaskHeadRowInView(headHeightPx: Int): Boolean {
    val info = layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.key == ThreadOlderMessagesListItemKey }
    return taskHeadRowInView(
        rowOffsetPx = row?.offset,
        rowSizePx = row?.size ?: 0,
        headHeightPx = headHeightPx,
        viewportStartPx = info.viewportStartOffset,
        viewportEndPx = info.viewportEndOffset,
    )
}

internal fun ThreadListViewport.isThreadOlderAutoLoadThreshold(
    boundaryIndex: Int,
    userScrollGeneration: Int,
    lastSettledUserScrollGeneration: Int
): Boolean {
    if (boundaryIndex < 0) return false
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= boundaryIndex) return false
    val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return false
    return shouldThreadAutoLoadOlderMessages(
        boundaryIndex = boundaryIndex,
        firstVisibleIndex = firstVisibleIndex,
        userScrollGeneration = userScrollGeneration,
        lastSettledUserScrollGeneration = lastSettledUserScrollGeneration
    )
}

internal fun shouldThreadAutoLoadOlderMessages(
    boundaryIndex: Int,
    firstVisibleIndex: Int,
    userScrollGeneration: Int,
    lastSettledUserScrollGeneration: Int = 0
): Boolean {
    if (boundaryIndex < 0 || firstVisibleIndex < 0) return false
    if (userScrollGeneration <= lastSettledUserScrollGeneration) return false
    return firstVisibleIndex <= boundaryIndex + ThreadOlderAutoLoadVisibleItemThreshold
}

internal suspend fun ThreadListViewport.awaitThreadMessagesLayoutFrame(): Boolean {
    repeat(ThreadLayoutStabilityMaxFrames) {
        val layoutInfo = layoutInfo
        if (layoutInfo.totalItemsCount > 0 &&
            layoutInfo.visibleItemsInfo.isNotEmpty() &&
            layoutInfo.viewportEndOffset > layoutInfo.viewportStartOffset
        ) {
            return true
        }
        delay(ThreadLayoutStabilityFrameDelayMs)
    }
    return false
}

internal suspend fun ThreadListViewport.scrollLatestItemToRenderedBottom(
    latestItemIndex: Int,
    trailingPaddingSize: Int = 0,
    commandStillCurrent: () -> Boolean
): Boolean {
    if (latestItemIndex < 0) return false
    if (itemCount <= latestItemIndex) {
        return false
    }
    if (!commandStillCurrent()) return false
    if (isVirtual) {
        scrollToVirtualBottom(suppressAutoLoad = false)
        return commandStillCurrent()
    }
    val offset =
        threadRenderedBottomScrollOffset(
            info = layoutInfo,
            itemIndex = latestItemIndex,
            trailingPaddingSize = trailingPaddingSize
        ) ?: 0
    if (!commandStillCurrent()) return false
    scrollToItem(latestItemIndex, offset)
    return commandStillCurrent()
}

/**
 * Places row [itemIndex] in the focused viewport over a few frames. [commandStillCurrent] is checked
 * after every suspension and before every scroll; production folds the owner lifetime
 * ([scrollOwnerLive]) into it, and [ThreadListViewport] itself refuses to scroll from a cancelling
 * job or a retired list.
 */
internal suspend fun ThreadListViewport.scrollThreadItemToFocusedViewport(
    itemIndex: Int,
    focusedScrollOffset: (itemSize: Int, info: LazyListLayoutInfo) -> Int = { itemSize, info ->
        threadFocusedItemScrollOffset(
            itemSize = itemSize,
            viewportHeight = info.viewportEndOffset - info.viewportStartOffset
        )
    },
    virtualAlign: build.raft.app.thread.experiment.VirtualTimelineAlign = build.raft.app.thread.experiment.VirtualTimelineAlign.Center,
    virtualReserveBottomPx: Int = 0,
    commandStillCurrent: () -> Boolean
): Boolean {
    if (itemIndex < 0 || itemCount <= itemIndex) {
        return false
    }
    if (isVirtual) {
        if (!commandStillCurrent()) return false
        return jumpToVirtualIndex(
            index = itemIndex,
            align = virtualAlign,
            reserveBottomPx = virtualReserveBottomPx,
            suppressAutoLoad = true,
        ) && commandStillCurrent()
    }
    var didScroll = false
    repeat(ThreadFocusedMessageCenterAttempts) {
        if (!commandStillCurrent()) return false
        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        if (item == null) {
            scrollToItem(itemIndex)
            if (!commandStillCurrent()) return false
            delay(ThreadLayoutStabilityFrameDelayMs)
            if (!commandStillCurrent()) return false
            return@repeat
        }
        // Re-read each attempt: the first scroll measures the row, later ones see its real size.
        val offset = focusedScrollOffset(item.size, layoutInfo)
        scrollToItem(itemIndex, offset)
        if (!commandStillCurrent()) return false
        didScroll = true
        delay(ThreadLayoutStabilityFrameDelayMs)
        if (!commandStillCurrent()) return false
    }
    return didScroll
}

internal suspend fun ThreadListViewport.scrollThreadItemToRenderedBottom(
    itemIndex: Int,
    trailingPaddingSize: Int,
    commandStillCurrent: () -> Boolean
): Boolean {
    if (itemIndex < 0 || itemCount <= itemIndex) return false
    if (isVirtual) {
        if (!commandStillCurrent()) return false
        return jumpToVirtualIndex(
            index = itemIndex,
            align = build.raft.app.thread.experiment.VirtualTimelineAlign.End,
            reserveBottomPx = trailingPaddingSize.coerceAtLeast(0),
            suppressAutoLoad = true,
        ) && commandStillCurrent()
    }
    var didScroll = false
    repeat(ThreadFocusedMessageCenterAttempts) {
        if (!commandStillCurrent()) return false
        val offset =
            threadRenderedBottomScrollOffset(
                info = layoutInfo,
                itemIndex = itemIndex,
                trailingPaddingSize = trailingPaddingSize
            )
        if (offset == null) {
            scrollToItem(itemIndex)
            if (!commandStillCurrent()) return false
            delay(ThreadLayoutStabilityFrameDelayMs)
            if (!commandStillCurrent()) return false
            return@repeat
        }
        scrollToItem(itemIndex, offset)
        if (!commandStillCurrent()) return false
        didScroll = true
        delay(ThreadLayoutStabilityFrameDelayMs)
        if (!commandStillCurrent()) return false
    }
    return didScroll
}

internal fun ThreadListViewport.threadFocusedTailFit(
    targetItemIndex: Int,
    latestMessageItemIndex: Int,
    bottomPaddingItemIndex: Int,
    viewportHeightPx: Int,
    trailingPaddingPx: Int
): ThreadFocusedTailFit {
    val visibleItems = layoutInfo.visibleItemsInfo
    val target = visibleItems.firstOrNull { item -> item.index == targetItemIndex }
    val latestMessage = visibleItems.firstOrNull { item -> item.index == latestMessageItemIndex }
    return resolveThreadFocusedTailFit(
        targetItemIndex = targetItemIndex,
        latestMessageItemIndex = latestMessageItemIndex,
        bottomPaddingItemIndex = bottomPaddingItemIndex,
        targetOffsetPx = target?.offset,
        targetSizePx = target?.size,
        latestMessageOffsetPx = latestMessage?.offset,
        latestMessageSizePx = latestMessage?.size,
        viewportHeightPx = viewportHeightPx,
        trailingPaddingPx = trailingPaddingPx
    )
}

internal suspend fun ThreadListViewport.awaitThreadMessagesLayoutStability(
    latestItemIndex: Int
): Boolean {
    var previous: ThreadMessagesLayoutStabilitySnapshot? = null
    var stableFrames = 0
    repeat(ThreadLayoutStabilityMaxFrames) {
        val current = threadMessagesLayoutStabilitySnapshot(latestItemIndex)
        if (current.canAnchor && current == previous) {
            stableFrames += 1
            if (stableFrames >= ThreadLayoutStabilityRequiredFrames) {
                return true
            }
        } else {
            previous = current
            stableFrames = 0
        }
        delay(ThreadLayoutStabilityFrameDelayMs)
    }
    return false
}

private fun ThreadListViewport.threadMessagesLayoutStabilitySnapshot(
    latestItemIndex: Int
): ThreadMessagesLayoutStabilitySnapshot {
    val layoutInfo = layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val first = visibleItems.firstOrNull()
    val last = visibleItems.lastOrNull()
    val latest = visibleItems.firstOrNull { it.index == latestItemIndex }
    return ThreadMessagesLayoutStabilitySnapshot(
        totalItemCount = layoutInfo.totalItemsCount,
        viewportStartOffset = layoutInfo.viewportStartOffset,
        viewportEndOffset = layoutInfo.viewportEndOffset,
        visibleItemCount = visibleItems.size,
        firstItemIndex = first?.index ?: -1,
        firstItemOffset = first?.offset ?: 0,
        firstItemSize = first?.size ?: 0,
        lastItemIndex = last?.index ?: -1,
        lastItemOffset = last?.offset ?: 0,
        lastItemSize = last?.size ?: 0,
        latestItemIndex = latest?.index ?: -1,
        latestItemOffset = latest?.offset ?: 0,
        latestItemSize = latest?.size ?: 0,
        canAnchor = latestItemIndex >= 0 &&
            layoutInfo.totalItemsCount > latestItemIndex &&
            visibleItems.isNotEmpty() &&
            layoutInfo.viewportEndOffset > layoutInfo.viewportStartOffset
    )
}

private data class ThreadMessagesLayoutStabilitySnapshot(
    val totalItemCount: Int,
    val viewportStartOffset: Int,
    val viewportEndOffset: Int,
    val visibleItemCount: Int,
    val firstItemIndex: Int,
    val firstItemOffset: Int,
    val firstItemSize: Int,
    val lastItemIndex: Int,
    val lastItemOffset: Int,
    val lastItemSize: Int,
    val latestItemIndex: Int,
    val latestItemOffset: Int,
    val latestItemSize: Int,
    val canAnchor: Boolean
)

internal fun threadLatestItemBottomAnchorScrollOffset(
    itemSize: Int,
    viewportHeight: Int,
    visibleItemCount: Int,
    totalItemCount: Int,
    trailingPaddingSize: Int = 0
): Int {
    if (itemSize <= 0 || viewportHeight <= 0) return 0
    return itemSize + trailingPaddingSize.coerceAtLeast(0) - viewportHeight
}

internal fun threadInitialBottomAnchorScrollDelta(
    itemOffset: Int,
    itemSize: Int,
    trailingPaddingSize: Int,
    viewportEndOffset: Int
): Int =
    itemOffset +
        itemSize.coerceAtLeast(0) +
        trailingPaddingSize.coerceAtLeast(0) -
        viewportEndOffset

internal fun threadFocusedItemScrollOffset(
    itemSize: Int,
    viewportHeight: Int
): Int {
    return 0
}

/**
 * The focus placement for a request: the unread-thread pill's own request (its nonce is the
 * store's [SharedThreadState.unreadThreadJumpFocusNonce]) lands on the replies summary; any other
 * focus is top-aligned. Nonces are process-unique and positive, so 0 (no jump yet) never matches.
 */
internal fun threadFocusedAnchorBasePlacement(
    requestNonce: Int,
    unreadThreadJumpFocusNonce: Int
): LazyViewportAnchorPlacement =
    if (unreadThreadJumpFocusNonce != 0 && requestNonce == unreadThreadJumpFocusNonce) {
        LazyViewportAnchorPlacement.FocusedThreadReplies
    } else {
        LazyViewportAnchorPlacement.Focused
    }

/** Share of the viewport kept below a jumped-to thread parent (task #73). */
internal const val ThreadRepliesFocusBottomReserveFraction = 0.25f

/**
 * Unread-thread jump (artin task #73): the forward `scrollToItem` offset for a thread parent row of
 * [itemSize] whose replies summary block is its bottom part, in a viewport whose visible content
 * spans `0..`[viewportEnd] (the rendered bottom, as the bottom anchors use).
 *
 * The row's bottom never sits lower than `viewportEnd - reserve`, with `reserve` the larger of
 * [ThreadRepliesFocusBottomReserveFraction] of the viewport and [minBottomReservePx] (room for the
 * jump pills over the list end), capped at half the viewport. Within that limit a short row is
 * centred whole; a taller one is pinned bottom-up at the limit, so its top scrolls off and the
 * replies block sits around the middle-lower part of the viewport instead of at the bottom edge.
 * Web centres the whole row (`scrollToMessage(id, { align: "center" })`), which for a tall parent
 * leaves its replies block below the fold.
 */
internal fun threadRepliesFocusedItemScrollOffset(
    itemSize: Int,
    viewportEnd: Int,
    minBottomReservePx: Int
): Int {
    if (itemSize <= 0 || viewportEnd <= 0) return 0
    val reserve =
        maxOf(minBottomReservePx, (viewportEnd * ThreadRepliesFocusBottomReserveFraction).roundToInt())
            .coerceIn(0, viewportEnd / 2)
    val centredTop = (viewportEnd - itemSize) / 2
    val bottomLimitedTop = viewportEnd - reserve - itemSize
    return -minOf(centredTop, bottomLimitedTop)
}

internal data class ThreadBackToBottomViewportSignals(
    val canScrollTowardBottom: Boolean,
    val latestItemOffBottom: Boolean,
    /**
     * The layout has measured row `latestItemIndex`, i.e. the current list. False for the
     * composition in which rows were appended (the layout still holds the previous list), when
     * [latestItemOffBottom] reads false whatever the scroll position.
     */
    val latestItemLaidOut: Boolean = true
)

/**
 * The scroll-position inputs of the "Back to bottom" pill as a derived state, so a caller's
 * composition scope is invalidated only when a value flips, not on every scroll frame.
 */
@Composable
internal fun rememberThreadBackToBottomViewportSignals(
    listState: ThreadListViewport,
    latestItemIndex: Int
): State<ThreadBackToBottomViewportSignals> =
    remember(listState, latestItemIndex) {
        derivedStateOf {
            ThreadBackToBottomViewportSignals(
                canScrollTowardBottom = listState.canScrollForward,
                latestItemOffBottom = listState.shouldShowThreadBackToBottom(latestItemIndex),
                latestItemLaidOut = latestItemIndex <= 0 || listState.layoutInfo.totalItemsCount > latestItemIndex
            )
        }
    }

/**
 * Web ChatPanel `newMessageCount`: reduces the arrivals below while the Back to Bottom pill shows
 * ([reduceThreadNewMessagesBelow]), once per change of the list's tail or the pill's inputs.
 * [arrivals] is read only when it runs, not on every composition.
 */
@Composable
internal fun ThreadNewMessagesBelowEffect(
    instanceKey: Any?,
    tailSignature: Any?,
    awayFromBottom: Boolean,
    canLoadMoreAfter: Boolean,
    viewportMeasured: Boolean,
    current: () -> ThreadNewMessagesBelow,
    onChange: (ThreadNewMessagesBelow) -> Unit,
    arrivals: () -> List<ThreadJumpArrival>,
) {
    LaunchedEffect(instanceKey, tailSignature, awayFromBottom, canLoadMoreAfter, viewportMeasured) {
        val state = current()
        val next =
            reduceThreadNewMessagesBelow(
                state = state,
                messages = arrivals(),
                awayFromBottom = awayFromBottom,
                canLoadMoreAfter = canLoadMoreAfter,
                viewportMeasured = viewportMeasured,
            )
        if (next != state) onChange(next)
    }
}

internal fun ThreadListViewport.shouldShowThreadBackToBottom(latestItemIndex: Int): Boolean {
    if (latestItemIndex <= 0) return false
    if (!canScrollForward) return false
    val layoutInfo = layoutInfo
    if (layoutInfo.totalItemsCount <= latestItemIndex) return false
    val latestItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == latestItemIndex }
    val latestBottom = latestItem?.let { it.offset + it.size } ?: return true
    return latestBottom < layoutInfo.viewportEndOffset - ThreadBackToBottomTolerancePx
}

/**
 * Whether row [itemIndex] (plus [trailingPaddingSize]) ends at the rendered bottom: the forward
 * [LazyListLayoutInfo.viewportEndOffset], the edge every bottom-anchor owner aims at.
 */
internal fun threadItemAtRenderedBottom(
    info: LazyListLayoutInfo,
    itemIndex: Int,
    trailingPaddingSize: Int = 0
): Boolean {
    if (info.totalItemsCount <= itemIndex) return false
    val item = info.visibleItemsInfo.firstOrNull { it.index == itemIndex } ?: return false
    val bottom = item.offset + item.size + trailingPaddingSize.coerceAtLeast(0)
    return kotlin.math.abs(bottom - info.viewportEndOffset) <= ThreadBackToBottomTolerancePx
}

/**
 * The forward `scrollToItem` offset that puts row [itemIndex] (plus [trailingPaddingSize]) at the
 * rendered bottom, or null while the row is not laid out.
 *
 * Forward offsets are measured from the content start, after the top content padding, so the
 * rendered bottom is [LazyListLayoutInfo.viewportEndOffset] - the same edge
 * [threadItemAtRenderedBottom] checks - not the full viewport height (`end - start`), which counts
 * the top padding again. Hands e0a5248d: with the offline stand-in's 40dp top inset on a reversed
 * list, the old full-height target parked the newest row 40dp below the composer, the anchored
 * check rejected that, and the initial-anchor retry and the composer-inset relayout moved the list
 * back and forth by the inset on every command until the network answer removed it.
 */
internal fun threadRenderedBottomScrollOffset(
    info: LazyListLayoutInfo,
    itemIndex: Int,
    trailingPaddingSize: Int = 0
): Int? {
    val item = info.visibleItemsInfo.firstOrNull { it.index == itemIndex } ?: return null
    return threadLatestItemBottomAnchorScrollOffset(
        itemSize = item.size,
        viewportHeight = info.viewportEndOffset,
        visibleItemCount = info.visibleItemsInfo.size,
        totalItemCount = info.totalItemsCount,
        trailingPaddingSize = trailingPaddingSize
    )
}

/** The action the unread-thread pill sends: focus that parent message. */
internal const val ThreadJumpToUnreadThreadParentAction = "focusUnreadThreadParent"

/** The thread timeline's row or tick: focus that message (ThreadOutline.kt). */
internal const val ThreadOutlineJumpAction = "focusThreadOutlineMessage"

/** The pill's thread has its parent past the history window and replies inside it: open the thread. */
internal const val ThreadOpenUnreadThreadPastHistoryAction = "openUnreadThreadPastHistory"

/** The pill's thread is wholly past the history window: land on the paywall and read the thread. */
internal const val ThreadJumpToUnreadThreadPastHistoryAction = "focusUnreadThreadPastHistory"

/** raft-ui MessageListJumpPillGroup: the unread-thread pill with its dismiss. */
@Composable
internal fun ThreadUnreadThreadJumpPill(
    jump: ThreadUnreadThreadJump,
    onJump: () -> Unit,
    onDismiss: () -> Unit,
    /** Web ThreadUnreadJumpPill busy (#11358): "Loading Thread…" with the spinner, taps ignored. */
    busy: Boolean = false,
) {
    RaftMessageListJumpPillGroup {
        RaftMessageListJumpPill(
            text =
                when {
                    busy -> stringResource(Res.string.thread_jump_unread_thread_loading)
                    jump.direction == ThreadUnreadThreadJumpDirection.Next ->
                        stringResource(Res.string.thread_jump_next_unread_thread, jump.count)
                    else -> stringResource(Res.string.thread_jump_earliest_unread_thread, jump.count)
                },
            onClick = onJump,
            icon = if (jump.direction == ThreadUnreadThreadJumpDirection.Earliest) RaftGlyph.ArrowUp else RaftGlyph.ArrowDown,
            busy = busy,
        )
        RaftMessageListJumpPillDismiss(
            label = stringResource(Res.string.thread_jump_unread_thread_dismiss),
            onClick = onDismiss,
        )
    }
}

internal fun threadVisibleReadTailMessageIndex(
    visibleListItemIndexes: List<Int>,
    firstMessageListItemIndex: Int,
    messageCount: Int,
): Int? {
    if (messageCount <= 0) return null
    val lastMessageListItemIndex = firstMessageListItemIndex + messageCount - 1
    return visibleListItemIndexes
        .asSequence()
        .filter { index -> index in firstMessageListItemIndex..lastMessageListItemIndex }
        .maxOrNull()
        ?.minus(firstMessageListItemIndex)
}

internal const val ThreadBackToBottomTolerancePx = 24
internal const val ThreadBottomAnchorMinimumAttempts = 2
private const val ThreadInitialBottomAnchorAttempts = 12
/** At least 80 ms each (plus the command settlement): the initial bottom anchor gives up after ~5 s. */
internal const val ThreadInitialBottomAnchorMaxRetries = 60
private const val ThreadInitialBottomAnchorMinimumConsumedDeltaPx = 0.5f
internal const val ThreadLayoutStabilityFrameDelayMs = 16L
private const val ThreadLayoutStabilityMaxFrames = 30
private const val ThreadLayoutStabilityRequiredFrames = 3
private const val ThreadBackToBottomAnchorAttempts = 12
private const val ThreadKeyboardBottomAnchorAttempts = 8
private const val ThreadOlderAutoLoadVisibleItemThreshold = 8
internal const val ThreadShareCaptureTopScrollOffsetPx = -12
internal const val ThreadShareCaptureScrollSettleMs = 96L

/**
 * Waits [frames] frames so capture-mode recomposition and relayout land before pixels are taken.
 * Returns false when there is no frame clock to wait on; the caller must then not capture.
 */
internal suspend fun awaitThreadShareCaptureFrames(frames: Int = 2): Boolean {
    if (currentCoroutineContext()[MonotonicFrameClock] == null) return false
    repeat(frames) { withFrameNanos { } }
    return true
}
