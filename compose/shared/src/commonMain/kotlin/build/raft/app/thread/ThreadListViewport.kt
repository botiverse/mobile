package build.raft.app

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize
import build.raft.app.nav.LocalRaftStackPageLeaving
import build.raft.app.thread.experiment.VirtualMessageTimelineState
import build.raft.app.thread.experiment.VirtualTimelineAlign
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** Whether this thread route lays its message list out bottom-up (always, on a thread page); see [ThreadListViewport]. */
internal val LocalThreadReverseLayout = staticCompositionLocalOf { false }

/**
 * Experimental switch (`#proj-mobile:#268`): replaces `ThreadMessagesList`'s `LazyColumn` with
 * `VirtualMessageTimeline` (`LazyLayout`, forward-ordered, persistent Fenwick height index,
 * coalesced system rows, native bottom pinning & reading anchor compensation).
 */
val LocalThreadUseVirtualTimeline = staticCompositionLocalOf { false }

/**
 * The thread message list seen in forward (top-to-bottom, oldest-first) terms, whichever backend
 * (`LazyListState` or [VirtualMessageTimelineState]) lays it out.
 */
internal class ThreadListViewport private constructor(
    private val rawState: LazyListState?,
    val reverse: Boolean,
    val virtualState: VirtualMessageTimelineState?,
) {
    constructor(raw: LazyListState, reverse: Boolean) : this(rawState = raw, reverse = reverse, virtualState = null)

    constructor(virtualState: VirtualMessageTimelineState) : this(
        rawState = null,
        reverse = false,
        virtualState = virtualState,
    )

    val isVirtual: Boolean
        get() = virtualState != null

    val raw: LazyListState
        get() = rawState ?: error("ThreadListViewport is backed by VirtualMessageTimelineState, not LazyListState")

    val interactionSource: InteractionSource
        get() = virtualState?.interactionSource ?: raw.interactionSource

    var isRetired: Boolean = false
        private set

    /** Scrolls this adapter has passed to its backing list; the owner-lifetime tests read it. */
    var issuedScrolls: Int = 0
        private set

    fun retire() {
        isRetired = true
    }

    // Open-trace attribution (ThreadOpenTrace.kt), observe-only: which viewport owner issued the
    // last scroll. Plain fields written on the main thread; reading them changes no scroll.
    private var pendingDispatchOwner: String? = null
    private var pendingDispatchAttempt = 0
    private var activeOwnerTag: String = ThreadOpenShiftCause.Unknown
    private var activeOwnerAttempt = 0

    /** Scrolls issued by a named owner (this adapter's, and raw writes noted with [noteRawViewportWrite]). */
    var ownedScrollCount: Int = 0
        private set
    var lastScrollOwnerTag: String = ThreadOpenShiftCause.Unknown
        private set
    var lastScrollOwnerAttempt: Int = 0
        private set

    var userNavigationCount: Int = 0
        private set

    fun noteUserNavigation() {
        userNavigationCount += 1
        virtualState?.notifyUserScrollGesture()
    }

    fun noteDispatchOwner(tag: String, attempt: Int = 0) {
        pendingDispatchOwner = tag
        pendingDispatchAttempt = attempt
    }

    fun noteCommandOwner(defaultTag: String) {
        val pending = pendingDispatchOwner
        activeOwnerTag = pending ?: defaultTag
        activeOwnerAttempt = if (pending != null) pendingDispatchAttempt else 0
        pendingDispatchOwner = null
    }

    fun noteViewportOwner(tag: String) {
        activeOwnerTag = tag
        activeOwnerAttempt = 0
        pendingDispatchOwner = null
    }

    fun noteRawViewportWrite(tag: String) {
        lastScrollOwnerTag = tag
        lastScrollOwnerAttempt = 0
        ownedScrollCount += 1
    }

    private suspend fun admitScroll(): Boolean {
        currentCoroutineContext().ensureActive()
        if (isRetired) return false
        issuedScrolls += 1
        ownedScrollCount += 1
        lastScrollOwnerTag = activeOwnerTag
        lastScrollOwnerAttempt = activeOwnerAttempt
        return true
    }

    val layoutInfo: LazyListLayoutInfo
        get() =
            virtualState?.layoutInfo
                ?: raw.layoutInfo.let { info -> if (reverse) ReversedThreadLayoutInfo(info) else info }

    /** Live item count, reflecting newly-synced virtual rows before the next measure pass runs. */
    val itemCount: Int
        get() = virtualState?.heightIndex?.itemCount ?: layoutInfo.totalItemsCount

    val isScrollInProgress: Boolean
        get() = virtualState?.isScrollInProgress ?: raw.isScrollInProgress

    /** Toward the end of the forward list, i.e. toward the newest row. */
    val canScrollForward: Boolean
        get() =
            virtualState?.canScrollForward
                ?: if (reverse) raw.canScrollBackward else raw.canScrollForward

    val canScrollBackward: Boolean
        get() =
            virtualState?.canScrollBackward
                ?: if (reverse) raw.canScrollForward else raw.canScrollBackward

    /** The top-most visible row, as in `LazyListState.firstVisibleItemIndex`. */
    val firstVisibleItemIndex: Int
        get() =
            virtualState?.firstVisibleItemIndex
                ?: if (reverse) topVisibleItem(layoutInfo)?.index ?: 0 else raw.firstVisibleItemIndex

    /** How far the top-most visible row is scrolled past the viewport top, as in `LazyListState`. */
    val firstVisibleItemScrollOffset: Int
        get() =
            virtualState?.firstVisibleItemScrollOffset
                ?: if (reverse) {
                    val info = layoutInfo
                    topVisibleItem(info)?.let { (-it.offset).coerceAtLeast(0) } ?: 0
                } else {
                    raw.firstVisibleItemScrollOffset
                }

    /** Forward semantics: positive moves the content up, revealing newer rows. */
    suspend fun scrollBy(value: Float): Float {
        if (!admitScroll()) return 0f
        virtualState?.let { return it.programmaticScrollBy(value) }
        return if (reverse) -raw.scrollBy(-value) else raw.scrollBy(value)
    }

    /** [scrollBy], animated (default spring); a drag cancels it through the scroll mutex. */
    suspend fun animateScrollBy(value: Float): Float {
        if (!admitScroll()) return 0f
        virtualState?.let { return it.programmaticAnimateScrollBy(value) }
        return if (reverse) -raw.animateScrollBy(-value) else raw.animateScrollBy(value)
    }

    /**
     * Forward semantics, as `LazyListState.scrollToItem`: afterwards row [index] has `offset == -scrollOffset`,
     * measured from the content start after the top padding.
     */
    suspend fun scrollToItem(index: Int, scrollOffset: Int = 0) {
        virtualState?.let { virtual ->
            if (!admitScroll()) return
            virtual.scrollToIndexWithOffset(index, scrollOffset)
            awaitVirtualJumpSettled(index)
            return
        }
        if (!reverse) {
            if (!admitScroll()) return
            raw.scrollToItem(index, scrollOffset)
            return
        }
        currentCoroutineContext().ensureActive()
        val count = raw.layoutInfo.totalItemsCount
        if (index !in 0 until count) return
        if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
            if (!admitScroll()) return
            raw.scrollToItem(reversedThreadListIndex(index, count))
            awaitVisible(index)
        }
        currentCoroutineContext().ensureActive()
        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        val delta = forwardScrollToItemDelta(itemOffset = item.offset, scrollOffset = scrollOffset)
        if (delta != 0 && ((delta > 0 && canScrollForward) || (delta < 0 && canScrollBackward))) {
            scrollBy(delta.toFloat())
        }
    }

    /** Single-pass pre-draw jump on the virtual backend (no multi-frame retry loop). */
    suspend fun jumpToVirtualIndex(
        index: Int,
        align: VirtualTimelineAlign,
        reserveBottomPx: Int = 0,
        suppressAutoLoad: Boolean = true,
    ): Boolean {
        val virtual = virtualState ?: return false
        if (index !in 0 until virtual.heightIndex.itemCount) return false
        if (!admitScroll()) return false
        virtual.jumpToIndex(
            index = index,
            align = align,
            reserveBottomPx = reserveBottomPx,
            suppressAutoLoad = suppressAutoLoad,
        )
        awaitVirtualJumpSettled(index)
        return true
    }

    /** Single-pass bottom-up tail convergence on the virtual backend. */
    suspend fun scrollToVirtualBottom(suppressAutoLoad: Boolean = false): Boolean {
        val virtual = virtualState ?: return false
        if (!admitScroll()) return false
        virtual.scrollToBottom(suppressAutoLoad = suppressAutoLoad)
        val lastIndex = (virtual.heightIndex.itemCount - 1).coerceAtLeast(0)
        awaitVirtualJumpSettled(lastIndex)
        return true
    }

    /** Non-suspending forward scroll request (replaces `raw.requestScrollToItem` on virtual backend). */
    fun requestForwardScrollToItem(index: Int, scrollOffset: Int = 0) {
        if (isRetired) return
        virtualState?.let { virtual ->
            virtual.scrollToIndexWithOffset(index, scrollOffset)
            return
        }
        raw.requestScrollToItem(index, scrollOffset)
    }

    fun setVirtualFollowBottom(enabled: Boolean) {
        virtualState?.setFollowBottom(enabled)
    }

    fun pinVirtualReadingAnchorKey(key: Any?) {
        virtualState?.pinReadingAnchorKey(key)
    }

    /**
     * `scrollToItem` on [raw] in raw terms (physical index, offset from the bottom when reversed).
     */
    suspend fun scrollRawToItem(rawIndex: Int, scrollOffset: Int) {
        virtualState?.let { virtual ->
            if (!admitScroll()) return
            virtual.scrollToIndexWithOffset(rawIndex, scrollOffset)
            awaitVirtualJumpSettled(rawIndex)
            return
        }
        if (rawIndex !in 0 until raw.layoutInfo.totalItemsCount) return
        if (!admitScroll()) return
        raw.scrollToItem(rawIndex, scrollOffset)
    }

    suspend fun animateScrollToItem(index: Int, scrollOffset: Int = 0) {
        virtualState?.let { virtual ->
            if (!admitScroll()) return
            virtual.animateScrollToIndex(index, scrollOffset)
            awaitVirtualJumpSettled(index)
            return
        }
        if (!reverse) {
            if (!admitScroll()) return
            raw.animateScrollToItem(index, scrollOffset)
            return
        }
        val count = raw.layoutInfo.totalItemsCount
        if (index !in 0 until count) return
        if (!admitScroll()) return
        raw.animateScrollToItem(reversedThreadListIndex(index, count))
        scrollToItem(index, scrollOffset)
    }

    private suspend fun awaitVirtualJumpSettled(targetIndex: Int) {
        val virtual = virtualState ?: return
        repeat(ThreadListViewportSettleFrames) {
            val info = virtual.layoutInfo
            if (
                !virtual.isJumpSettling &&
                info.totalItemsCount > targetIndex &&
                info.visibleItemsInfo.any { it.index == targetIndex }
            ) {
                return
            }
            delay(ThreadListViewportSettleFrameMs)
        }
    }

    private suspend fun awaitVisible(index: Int) {
        repeat(ThreadListViewportSettleFrames) {
            if (layoutInfo.visibleItemsInfo.any { it.index == index }) return
            delay(ThreadListViewportSettleFrameMs)
        }
    }
}

@Composable
internal fun ThreadListViewportRetireOnDispose(viewport: ThreadListViewport) {
    DisposableEffect(viewport) { onDispose { viewport.retire() } }
}

internal fun ThreadListViewport.scrollOwnerLive(context: CoroutineContext, pageLeaving: Boolean): Boolean =
    context.isActive && !isRetired && !pageLeaving

internal class ThreadListScrollOwnerScope(
    val viewport: ThreadListViewport,
    private val context: CoroutineContext,
    private val pageLeaving: State<Boolean>,
) {
    fun ownerLive(): Boolean = viewport.scrollOwnerLive(context, pageLeaving = pageLeaving.value)
}

@Composable
internal fun ThreadListScrollOwnerEffect(
    viewport: ThreadListViewport,
    key1: Any?,
    key2: Any?,
    block: suspend ThreadListScrollOwnerScope.() -> Unit,
) {
    val pageLeaving = LocalRaftStackPageLeaving.current
    val pageLeavingState = rememberUpdatedState(pageLeaving)
    LaunchedEffect(key1, key2, pageLeaving) {
        if (pageLeaving) return@LaunchedEffect
        ThreadListScrollOwnerScope(viewport, coroutineContext, pageLeavingState).block()
    }
    ThreadListViewportRetireOnDispose(viewport)
}

private const val ThreadListViewportSettleFrames = 6
private const val ThreadListViewportSettleFrameMs = 16L

internal fun forwardScrollToItemDelta(itemOffset: Int, scrollOffset: Int): Int = itemOffset + scrollOffset

internal fun reversedThreadListIndex(index: Int, count: Int): Int = count - 1 - index

internal fun reversedThreadListItemOffset(
    rawOffset: Int,
    size: Int,
    layoutSize: Int,
    rawBeforeContentPadding: Int,
    rawAfterContentPadding: Int,
): Int = layoutSize - rawBeforeContentPadding - rawAfterContentPadding - rawOffset - size

internal fun topVisibleItem(info: LazyListLayoutInfo): LazyListItemInfo? =
    info.visibleItemsInfo
        .filter { it.offset + it.size > 0 }
        .minByOrNull { it.index }

internal class ReversedThreadLayoutInfo(private val raw: LazyListLayoutInfo) : LazyListLayoutInfo {
    private val layoutSize: Int = raw.viewportEndOffset - raw.viewportStartOffset

    override val visibleItemsInfo: List<LazyListItemInfo> =
        raw.visibleItemsInfo
            .map { item ->
                ForwardThreadItemInfo(
                    index = reversedThreadListIndex(item.index, raw.totalItemsCount),
                    key = item.key,
                    offset =
                        reversedThreadListItemOffset(
                            rawOffset = item.offset,
                            size = item.size,
                            layoutSize = layoutSize,
                            rawBeforeContentPadding = raw.beforeContentPadding,
                            rawAfterContentPadding = raw.afterContentPadding,
                        ),
                    size = item.size,
                    contentType = item.contentType,
                )
            }
            .sortedBy { it.index }
    override val viewportStartOffset: Int = -raw.afterContentPadding
    override val viewportEndOffset: Int = layoutSize - raw.afterContentPadding
    override val totalItemsCount: Int = raw.totalItemsCount
    override val viewportSize: IntSize = raw.viewportSize
    override val orientation: Orientation = raw.orientation
    override val reverseLayout: Boolean = false
    override val beforeContentPadding: Int = raw.afterContentPadding
    override val afterContentPadding: Int = raw.beforeContentPadding
    override val mainAxisItemSpacing: Int = raw.mainAxisItemSpacing
}

private data class ForwardThreadItemInfo(
    override val index: Int,
    override val key: Any,
    override val offset: Int,
    override val size: Int,
    override val contentType: Any?,
) : LazyListItemInfo
