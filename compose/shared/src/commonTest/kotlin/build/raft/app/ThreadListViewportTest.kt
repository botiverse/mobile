package build.raft.app

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The reversed thread list is read forward through [ReversedThreadLayoutInfo]: a bottom-up raw layout
 * must look exactly like the forward layout the thread page's scroll logic was written against.
 */
class ThreadListViewportTest {
    private data class RawItem(
        override val index: Int,
        override val offset: Int,
        override val size: Int,
        override val key: Any = "k$index",
    ) : LazyListItemInfo

    private class RawInfo(
        override val visibleItemsInfo: List<LazyListItemInfo>,
        override val totalItemsCount: Int,
        layoutSize: Int,
        override val beforeContentPadding: Int = 0,
        override val afterContentPadding: Int = 0,
    ) : LazyListLayoutInfo {
        // Kuikly/Compose: start = -before, end = layout size - before.
        override val viewportStartOffset: Int = -beforeContentPadding
        override val viewportEndOffset: Int = layoutSize - beforeContentPadding
        override val reverseLayout: Boolean = true
    }

    // Physical (bottom-up): 0 = bottom padding row (40), 1..4 = messages newest→oldest (100 each).
    private val rawItems =
        listOf(RawItem(0, 0, 40), RawItem(1, 40, 100), RawItem(2, 140, 100), RawItem(3, 240, 100), RawItem(4, 340, 100))

    @Test
    fun indicesFlipAndOffsetsAreMeasuredFromTheTop() {
        val info = ReversedThreadLayoutInfo(RawInfo(rawItems, totalItemsCount = 5, layoutSize = 1000))
        assertEquals(listOf(0, 1, 2, 3, 4), info.visibleItemsInfo.map { it.index }, "ascending forward order")
        assertEquals(listOf("k4", "k3", "k2", "k1", "k0"), info.visibleItemsInfo.map { it.key })
        // Oldest message at the top of the stack, bottom padding row last, touching the viewport end.
        assertEquals(listOf(560, 660, 760, 860, 960), info.visibleItemsInfo.map { it.offset })
        val padding = info.visibleItemsInfo.last()
        assertEquals(info.viewportEndOffset, padding.offset + padding.size, "the latest row sits at the rendered bottom")
        assertEquals(0, info.viewportStartOffset)
        assertEquals(1000, info.viewportEndOffset)
        assertFalse(info.reverseLayout)
    }

    @Test
    fun theTopContentPaddingOfAReversedListIsItsForwardBeforePadding() {
        // Offline tip inset: PaddingValues(top = 40) is the raw *after* padding of a bottom-up list.
        val info = ReversedThreadLayoutInfo(RawInfo(rawItems, totalItemsCount = 5, layoutSize = 1000, afterContentPadding = 40))
        assertEquals(40, info.beforeContentPadding)
        assertEquals(0, info.afterContentPadding)
        assertEquals(-40, info.viewportStartOffset)
        assertEquals(960, info.viewportEndOffset)
        val padding = info.visibleItemsInfo.last()
        assertEquals(info.viewportEndOffset, padding.offset + padding.size)
        assertEquals(520, info.visibleItemsInfo.first().offset)
    }

    @Test
    fun underTheTopInsetTheFirstVisibleRowAndScrollTargetsFollowComposeForwardSemantics() {
        // BiSheng #2773 review: with a 40px top inset, forward offsets are measured after the inset, so a
        // row lying wholly under it is not the first visible row, and scrollToItem(i, s) aims at offset -s.
        val info = ReversedThreadLayoutInfo(
            RawInfo(
                listOf(RawItem(0, 0, 40), RawItem(1, 40, 100), RawItem(2, 140, 900), RawItem(3, 1040, 30)),
                totalItemsCount = 4,
                layoutSize = 1000,
                afterContentPadding = 40,
            )
        )
        // Forward: row 0 (raw 3) at -110..-80, wholly under the inset; row 1 (raw 2) at -80..820.
        assertEquals(listOf(-110, -80, 820, 920), info.visibleItemsInfo.map { it.offset })
        val top = topVisibleItem(info)!!
        assertEquals(1, top.index, "a row wholly inside the top padding is skipped")
        assertEquals(80, (-top.offset).coerceAtLeast(0), "scrolled 80px past the content start, not 40")
        // Restoring an anchor captured as offset -80 must be a no-op, not a 40px (inset) jump.
        assertEquals(0, forwardScrollToItemDelta(itemOffset = -80, scrollOffset = 80))
    }

    @Test
    fun indexMappingIsItsOwnInverse() {
        for (count in 1..6) {
            for (index in 0 until count) {
                assertEquals(index, reversedThreadListIndex(reversedThreadListIndex(index, count), count))
            }
        }
        assertEquals(4, reversedThreadListIndex(0, 5), "the oldest forward row is the top physical row")
        assertEquals(0, reversedThreadListIndex(4, 5), "the bottom padding row is physical item 0")
    }

    @Test
    fun aRowScrolledPartlyOffTheBottomKeepsItsForwardGeometry() {
        // Raw offset -30: the bottom padding row is 30px below the viewport bottom.
        val info = ReversedThreadLayoutInfo(RawInfo(listOf(RawItem(0, -30, 40), RawItem(1, 10, 100)), totalItemsCount = 5, layoutSize = 1000))
        val latest = info.visibleItemsInfo.last()
        assertEquals(4, latest.index)
        assertEquals(990, latest.offset)
        assertEquals(30, latest.offset + latest.size - info.viewportEndOffset, "30px past the bottom, forward-measured")
    }
}
