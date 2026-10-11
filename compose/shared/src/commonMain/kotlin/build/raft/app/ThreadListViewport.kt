package build.raft.app

import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.data.MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.DiagnosticFeatureFlagScope
import com.tencent.kuikly.compose.foundation.gestures.Orientation
import com.tencent.kuikly.compose.foundation.gestures.scrollBy
import com.tencent.kuikly.compose.foundation.lazy.LazyListItemInfo
import com.tencent.kuikly.compose.foundation.lazy.LazyListLayoutInfo
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.ui.unit.IntSize
import kotlinx.coroutines.delay

/** Whether this thread route lays its message list out bottom-up; see [ThreadListViewport]. */
internal val LocalThreadReverseLayout = staticCompositionLocalOf { false }

/**
 * Same identity rules as the markdown light path: decided on the route's reply-cache provenance.
 * Alpha builds have it on by default (artin, #proj-chat:9d4548c9 f1e2f5ec); release builds follow the
 * server flag. Local Feature Overrides ForceOn/ForceOff still win either way.
 */
internal fun resolveThreadReverseLayoutEnabled(
    registry: DiagnosticFeatureFlagOverrideRegistry,
    serverEffective: Boolean,
    buildType: String?,
    environmentId: String,
    accountId: String,
    serverId: String,
): Boolean =
    registry.decide(
        key = MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY,
        serverEffective = threadReverseLayoutDefault(serverEffective, buildType),
        scope =
            DiagnosticFeatureFlagScope(
                environmentId = environmentId.trim().trimEnd('/'),
                accountId = accountId,
                serverId = serverId,
            ),
    ).finalEffective

/** The value the local override falls back to: on in every alpha build, else the server flag. */
internal fun threadReverseLayoutDefault(serverEffective: Boolean, buildType: String?): Boolean =
    serverEffective || buildType?.trim().equals(THREAD_REVERSE_LAYOUT_DEFAULT_ON_BUILD_TYPE, ignoreCase = true)

private const val THREAD_REVERSE_LAYOUT_DEFAULT_ON_BUILD_TYPE = "alpha"

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
