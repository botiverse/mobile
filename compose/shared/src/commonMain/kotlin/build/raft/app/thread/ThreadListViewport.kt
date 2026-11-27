package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.IntSize
import build.raft.app.get
import build.raft.app.nav.LocalRaftStackPageLeaving
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** Whether this thread route lays its message list out bottom-up (always, on a thread page); see [ThreadListViewport]. */
internal val LocalThreadReverseLayout = staticCompositionLocalOf { false }

/**
 * The thread message list seen in forward (top-to-bottom, oldest-first) terms, whichever way the
 * underlying [LazyListState] lays out.
 *
 * With [reverse] the list is a `LazyColumn(reverseLayout = true)`: the items are emitted newest-first,
 * so the newest row is physical index 0 and sits at the bottom from the very first frame — no
 * "scroll to the bottom" pass, and nothing to hide behind a loading state. Every thread-page
 * reader and scroll command keeps its forward meaning through this adapter: logical index `i` is
 * physical `count - 1 - i`, offsets are measured from the viewport top, "forward" means toward the
 * newest row. Without [reverse] every member is the raw state unchanged.
 */
internal class ThreadListViewport(
    val raw: LazyListState,
    val reverse: Boolean,
) {
    /**
     * Owner lifetime (scroll ownership contract, "Coordinator and native-view lifetime"; Hands
     * 712d8386): set for good when the list this adapter drives leaves composition
     * ([ThreadListViewportRetireOnDispose]). A retired adapter issues no scroll: a scroll on a
     * [LazyListState] whose LazyColumn is gone cancels prefetch work on detached nodes and
     * crashes (`LayoutNode should be attached to an owner`).
     */
    var isRetired: Boolean = false
        private set

    /** Scrolls this adapter has passed to [raw]; the owner-lifetime tests read it. */
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
    private val ownerAttemptByTag: MutableMap<String, Int> = mutableMapOf()

    /** Scrolls issued by a named owner (this adapter's, and raw writes noted with [noteRawViewportWrite]). */
    var ownedScrollCount: Int = 0
        private set
    var lastScrollOwnerTag: String = ThreadOpenShiftCause.Unknown
        private set
    var lastScrollOwnerAttempt: Int = 0
        private set

    internal val activeScrollOwnerTag: String
        get() = activeOwnerTag
    internal val activeScrollOwnerAttempt: Int
        get() = activeOwnerAttempt

    /**
     * Explicit non-drag user navigation count (e.g. Back to bottom pill, header scroll-to-top,
     * outline jump, local send, row expand/collapse). Observe-only: [ThreadOpenShiftRecorderEffect]
     * treats an increment as [ThreadOpenEnd.UserTookOver] so deliberate user jumps are never
     * misattributed as open-path jitter (#proj-mobile:#258).
     */
    var userNavigationCount: Int = 0
        private set

    fun noteUserNavigation() {
        userNavigationCount += 1
    }

    private fun resolveOwnerAttempt(tag: String, explicitAttempt: Int = 0): Int {
        val previous = ownerAttemptByTag[tag] ?: 0
        if (explicitAttempt > 0) {
            if (explicitAttempt > previous) ownerAttemptByTag[tag] = explicitAttempt
            return explicitAttempt
        }
        val next = previous + 1
        ownerAttemptByTag[tag] = next
        return next
    }

    /** A writer that dispatches a controller event names itself; the command executor picks it up. */
    fun noteDispatchOwner(tag: String, attempt: Int = 0) {
        pendingDispatchOwner = tag
        pendingDispatchAttempt = attempt
    }

    /** The command executor: the dispatching writer's name, else [defaultTag] for the command's target. */
    fun noteCommandOwner(defaultTag: String) {
        val pending = pendingDispatchOwner
        val tag = pending ?: defaultTag
        val explicitAttempt = if (pending != null) pendingDispatchAttempt else 0
        activeOwnerTag = tag
        activeOwnerAttempt = resolveOwnerAttempt(tag, explicitAttempt)
        pendingDispatchOwner = null
        pendingDispatchAttempt = 0
    }

    /** A writer that scrolls through this adapter directly. */
    fun noteViewportOwner(tag: String, attempt: Int = 0) {
        activeOwnerTag = tag
        activeOwnerAttempt = resolveOwnerAttempt(tag, attempt)
        pendingDispatchOwner = null
        pendingDispatchAttempt = 0
    }

    /** A write that bypasses this adapter (`raw.requestScrollToItem`). */
    fun noteRawViewportWrite(tag: String, attempt: Int = 0) {
        lastScrollOwnerTag = tag
        lastScrollOwnerAttempt = resolveOwnerAttempt(tag, attempt)
        ownedScrollCount += 1
    }

    /**
     * Checked right before each mutation of [raw], with no suspension in between: never from a
     * cancelling job (throws its [kotlinx.coroutines.CancellationException]), never on a retired list.
     */
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
        get() = raw.layoutInfo.let { info -> if (reverse) ReversedThreadLayoutInfo(info) else info }

    val isScrollInProgress: Boolean
        get() = raw.isScrollInProgress

    /** Toward the end of the forward list, i.e. toward the newest row. */
    val canScrollForward: Boolean
        get() = if (reverse) raw.canScrollBackward else raw.canScrollForward

    val canScrollBackward: Boolean
        get() = if (reverse) raw.canScrollForward else raw.canScrollBackward

    /** The top-most visible row, as in `LazyListState.firstVisibleItemIndex`. */
    val firstVisibleItemIndex: Int
        get() = if (reverse) topVisibleItem(layoutInfo)?.index ?: 0 else raw.firstVisibleItemIndex

    /** How far the top-most visible row is scrolled past the viewport top, as in `LazyListState`. */
    val firstVisibleItemScrollOffset: Int
        get() =
            if (reverse) {
                val info = layoutInfo
                topVisibleItem(info)?.let { (-it.offset).coerceAtLeast(0) } ?: 0
            } else {
                raw.firstVisibleItemScrollOffset
            }

    /** Forward semantics: positive moves the content up, revealing newer rows. */
    suspend fun scrollBy(value: Float): Float {
        if (!admitScroll()) return 0f
        return if (reverse) -raw.scrollBy(-value) else raw.scrollBy(value)
    }

    /** [scrollBy], animated (default spring); a drag cancels it through the scroll mutex. */
    suspend fun animateScrollBy(value: Float): Float {
        if (!admitScroll()) return 0f
        return if (reverse) -raw.animateScrollBy(-value) else raw.animateScrollBy(value)
    }

    /**
     * Forward semantics, as `LazyListState.scrollToItem`: afterwards row [index] has `offset == -scrollOffset`,
     * measured from the content start after the top padding. Reversed, that needs the row's measured size,
     * so a row that is not laid out is first brought on screen.
     */
    suspend fun scrollToItem(index: Int, scrollOffset: Int = 0) {
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
        // scrollBy admits again: awaitVisible suspended since the first scroll.
        // Skip a delta into an already-clamped edge so a focused row near the tail doesn't emit a
        // zero-movement scrollBy or bump ownedScrollCount.
        if (delta != 0 && ((delta > 0 && canScrollForward) || (delta < 0 && canScrollBackward))) {
            scrollBy(delta.toFloat())
        }
    }

    /**
     * `scrollToItem` on [raw] in raw terms (physical index, offset from the bottom when reversed).
     * It forces a remeasure, so started undispatched outside a layout pass it completes before the
     * frame is drawn ([ThreadReadingAnchorKeeper]).
     */
    suspend fun scrollRawToItem(rawIndex: Int, scrollOffset: Int) {
        if (rawIndex !in 0 until raw.layoutInfo.totalItemsCount) return
        if (!admitScroll()) return
        raw.scrollToItem(rawIndex, scrollOffset)
    }

    suspend fun animateScrollToItem(index: Int, scrollOffset: Int = 0) {
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

    private suspend fun awaitVisible(index: Int) {
        repeat(ThreadListViewportSettleFrames) {
            if (layoutInfo.visibleItemsInfo.any { it.index == index }) return
            delay(ThreadListViewportSettleFrameMs)
        }
    }
}

/**
 * Retires [viewport] ([ThreadListViewport.retire]) when this leaves composition. Call it in the
 * composable that owns the list, after the effects that scroll [viewport]: effects are disposed in
 * reverse order, so the list is retired before those effects' jobs are cancelled (the contract's
 * "invalidate before scope cancellation").
 */
@Composable
internal fun ThreadListViewportRetireOnDispose(viewport: ThreadListViewport) {
    DisposableEffect(viewport) { onDispose { viewport.retire() } }
}

/**
 * Whether the owner of a programmatic thread-list scroll is still live after a suspension: the job
 * issuing it ([context]) is active, the list is still composed, and its page is not leaving the
 * page stack ([pageLeaving], `LocalRaftStackPageLeaving`). Checked after every suspension point,
 * before the next `scrollToItem` / `scrollBy`, on top of the command's own currency checks.
 */
internal fun ThreadListViewport.scrollOwnerLive(context: CoroutineContext, pageLeaving: Boolean): Boolean =
    context.isActive && !isRetired && !pageLeaving

/** What a [ThreadListScrollOwnerEffect] block checks after each suspension: [ownerLive]. */
internal class ThreadListScrollOwnerScope(
    val viewport: ThreadListViewport,
    private val context: CoroutineContext,
    private val pageLeaving: State<Boolean>,
) {
    /** [scrollOwnerLive] for this effect's job, its list and its page, read now. */
    fun ownerLive(): Boolean = viewport.scrollOwnerLive(context, pageLeaving = pageLeaving.value)
}

/**
 * A `LaunchedEffect(key1, key2)` that scrolls [viewport], with the owner lifetime wired in (Hands
 * 712d8386): it is also keyed on the page leaving the stack (`LocalRaftStackPageLeaving`) and does
 * not run [block] on a leaving page, and it retires [viewport] when it leaves composition, before
 * its job is cancelled ([ThreadListViewportRetireOnDispose] after the effect). [block] still has to
 * check [ThreadListScrollOwnerScope.ownerLive] after every suspension, before its next scroll.
 */
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

/** Forward `scrollBy` that brings a row at [itemOffset] to `offset == -scrollOffset`. */
internal fun forwardScrollToItemDelta(itemOffset: Int, scrollOffset: Int): Int = itemOffset + scrollOffset

/** Physical index of forward index [index] (and back: the mapping is its own inverse). */
internal fun reversedThreadListIndex(index: Int, count: Int): Int = count - 1 - index

/**
 * Forward offset (from the viewport top, after the top content padding) of a reversed-layout row
 * whose raw offset is [rawOffset] (from the bottom, after the bottom padding) and size [size].
 */
internal fun reversedThreadListItemOffset(
    rawOffset: Int,
    size: Int,
    layoutSize: Int,
    rawBeforeContentPadding: Int,
    rawAfterContentPadding: Int,
): Int = layoutSize - rawBeforeContentPadding - rawAfterContentPadding - rawOffset - size

/**
 * The forward first visible item: like Compose, rows lying wholly inside the top content padding are
 * skipped (offsets are measured from the content start after that padding).
 */
internal fun topVisibleItem(info: LazyListLayoutInfo): LazyListItemInfo? =
    info.visibleItemsInfo
        .filter { it.offset + it.size > 0 }
        .minByOrNull { it.index }

/**
 * A reversed layout presented forward: indices flipped, offsets measured from the top, paddings
 * swapped (the raw "before" padding of a reversed list is the bottom one), items in ascending order.
 */
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
