package build.raft.app

import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.data.MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.IntSize
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay

/** Whether this thread route lays its message list out bottom-up; see [ThreadListViewport]. */
internal val LocalThreadReverseLayout = staticCompositionLocalOf { false }

/**
 * Same identity rules as the markdown light path: decided on the route's reply-cache provenance.
 * The server flag reads ON unless the server explicitly turns it off (client default-on key, artin
 * 2026-10-12). Local Feature Overrides ForceOn/ForceOff still win either way.
 */
internal fun resolveThreadReverseLayoutEnabled(
    registry: DiagnosticFeatureFlagOverrideRegistry,
    serverEffective: Boolean,
    environmentId: String,
    accountId: String,
    serverId: String,
): Boolean =
    registry.decide(
        key = MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY,
        serverEffective = serverEffective,
        scope =
            DiagnosticFeatureFlagScope(
                environmentId = environmentId.trim().trimEnd('/'),
                accountId = accountId,
                serverId = serverId,
            ),
    ).finalEffective

/** Task #193: the direction a thread opens with before anything else has decided it. */
internal const val THREAD_REVERSE_LAYOUT_UNDECIDED_DEFAULT: Boolean = true

/**
 * Task #193 (option C): the last gate-resolved reverse-layout value per server, for the process
 * lifetime (not persisted; after a restart the first open of a server falls back to
 * [THREAD_REVERSE_LAYOUT_UNDECIDED_DEFAULT] until its gate resolves once). A thread open reads it
 * for its first frame and never changes direction afterwards; the gate result recorded here only
 * applies from the next open.
 */
internal object ThreadReverseLayoutLastDecision {
    private val lock = SynchronizedObject()
    private val byServer = mutableMapOf<String, Boolean>()

    fun get(serverId: String?): Boolean? {
        if (serverId.isNullOrBlank()) return null
        return synchronized(lock) { byServer[serverId] }
    }

    fun record(serverId: String, reverse: Boolean) {
        if (serverId.isBlank()) return
        synchronized(lock) { byServer[serverId] = reverse }
    }

    internal fun clearForTest() {
        synchronized(lock) { byServer.clear() }
    }
}

/**
 * Task #193 (option C): the direction locked for one thread open. Order: the user's explicit
 * Settings choice, the gate when it is already resolved at first composition, this server's last
 * resolved value, then reverse.
 */
internal fun threadReverseLayoutFirstFrame(
    userChoice: Boolean?,
    gateResolved: Boolean?,
    lastResolvedForServer: Boolean?,
): Boolean = userChoice ?: gateResolved ?: lastResolvedForServer ?: THREAD_REVERSE_LAYOUT_UNDECIDED_DEFAULT

/**
 * The layout a thread opens with: the user's Settings choice when there is one, otherwise the
 * default above (artin 2026-10-11: the switch lives in Settings, the flag only sets its default).
 */
internal fun threadReverseLayoutEffective(userChoice: Boolean?, followedDefault: () -> Boolean): Boolean =
    userChoice ?: followedDefault()

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
    suspend fun scrollBy(value: Float): Float =
        if (reverse) -raw.scrollBy(-value) else raw.scrollBy(value)

    /**
     * Forward semantics, as `LazyListState.scrollToItem`: afterwards row [index] has `offset == -scrollOffset`,
     * measured from the content start after the top padding. Reversed, that needs the row's measured size,
     * so a row that is not laid out is first brought on screen.
     */
    suspend fun scrollToItem(index: Int, scrollOffset: Int = 0) {
        if (!reverse) {
            raw.scrollToItem(index, scrollOffset)
            return
        }
        val count = raw.layoutInfo.totalItemsCount
        if (index !in 0 until count) return
        if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
            raw.scrollToItem(reversedThreadListIndex(index, count))
            awaitVisible(index)
        }
        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        val delta = forwardScrollToItemDelta(itemOffset = item.offset, scrollOffset = scrollOffset)
        if (delta != 0) scrollBy(delta.toFloat())
    }

    suspend fun animateScrollToItem(index: Int, scrollOffset: Int = 0) {
        if (!reverse) {
            raw.animateScrollToItem(index, scrollOffset)
            return
        }
        val count = raw.layoutInfo.totalItemsCount
        if (index !in 0 until count) return
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
