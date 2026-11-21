package build.raft.app

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pure unit tests for cached focused-open initial placement (#proj-mobile:#258):
 * - [threadInitialFirstVisibleItemIndex] starts at the cached focused row instead of latest/bottom.
 * - [resolveThreadInitialFocusedPlacement] aligns the measured row to its focused forward offset
 *   using the exact inverse of [reversedThreadListItemOffset] (including top content padding and
 *   [LazyViewportAnchorPlacement.FocusedThreadReplies]).
 */
class ThreadInitialFocusedPlacementTest {
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
        override val viewportStartOffset: Int = -beforeContentPadding
        override val viewportEndOffset: Int = layoutSize - beforeContentPadding
        override val reverseLayout: Boolean = true
    }

    @Test
    fun initialIndexUsesCachedFocusedRowOnBothReversedAndForwardLists() {
        // Reversed: 52 items (0..51), focused forward index 20 -> physical index 31.
        assertEquals(
            31,
            threadInitialFirstVisibleItemIndex(
                reverseLayout = true,
                latestItemIndex = 51,
                lazyItemCount = 52,
                focusedItemIndex = 20,
            ),
        )
        // Forward: starts directly at forward index 20.
        assertEquals(
            20,
            threadInitialFirstVisibleItemIndex(
                reverseLayout = false,
                latestItemIndex = 51,
                lazyItemCount = 52,
                focusedItemIndex = 20,
            ),
        )
        // No cached focused target (cold open or normal latest open): keep existing defaults.
        assertEquals(
            0,
            threadInitialFirstVisibleItemIndex(
                reverseLayout = true,
                latestItemIndex = 51,
                lazyItemCount = 52,
                focusedItemIndex = -1,
            ),
        )
        assertEquals(
            51,
            threadInitialFirstVisibleItemIndex(
                reverseLayout = false,
                latestItemIndex = 51,
                lazyItemCount = 52,
                focusedItemIndex = -1,
            ),
        )
        // Out-of-range focused index falls back cleanly.
        assertEquals(
            0,
            threadInitialFirstVisibleItemIndex(
                reverseLayout = true,
                latestItemIndex = 51,
                lazyItemCount = 52,
                focusedItemIndex = 52,
            ),
        )
    }

    @Test
    fun resolveFocusedPlacementAlignsRowTopToViewportStartIncludingTopPadding() {
        // 52 items, focused forward index 20 -> physical index 31, height 120px, sitting at rawOffset 0.
        // Top inset (offline tip) = 40px (raw afterContentPadding = 40).
        val rawInfo =
            RawInfo(
                visibleItemsInfo = listOf(RawItem(index = 31, offset = 0, size = 120)),
                totalItemsCount = 52,
                layoutSize = 1000,
                beforeContentPadding = 0,
                afterContentPadding = 40,
            )
        val decision =
            resolveThreadInitialFocusedPlacement(
                layoutInfo = rawInfo,
                focusedForwardIndex = 20,
                placement = LazyViewportAnchorPlacement.Focused,
                minBottomReservePx = 100,
            )
        val scroll = assertIs<ThreadInitialFocusedPlacementDecision.Scroll>(decision)
        assertEquals(31, scroll.request.rawIndex)
        // desiredRawOffset = 1000 - 0 - 40 - 120 - 0 = 840 => scrollOffset = -840.
        assertEquals(-840, scroll.request.scrollOffset)
        // Round-trip through ReversedThreadLayoutInfo's offset math lands at forward top == 0.
        assertEquals(
            0,
            reversedThreadListItemOffset(
                rawOffset = -scroll.request.scrollOffset,
                size = 120,
                layoutSize = 1000,
                rawBeforeContentPadding = 0,
                rawAfterContentPadding = 40,
            ),
        )
    }

    @Test
    fun resolveFocusedPlacementHandlesRowTallerThanViewport() {
        val rawInfo =
            RawInfo(
                visibleItemsInfo = listOf(RawItem(index = 10, offset = 0, size = 2000)),
                totalItemsCount = 20,
                layoutSize = 800,
            )
        val decision =
            resolveThreadInitialFocusedPlacement(
                layoutInfo = rawInfo,
                focusedForwardIndex = 9,
                placement = LazyViewportAnchorPlacement.Focused,
                minBottomReservePx = 100,
            )
        val scroll = assertIs<ThreadInitialFocusedPlacementDecision.Scroll>(decision)
        assertEquals(10, scroll.request.rawIndex)
        // desiredRawOffset = 800 - 2000 = -1200 => scrollOffset = +1200 (top flush at forward 0).
        assertEquals(1200, scroll.request.scrollOffset)
        assertEquals(
            0,
            reversedThreadListItemOffset(
                rawOffset = -scroll.request.scrollOffset,
                size = 2000,
                layoutSize = 800,
                rawBeforeContentPadding = 0,
                rawAfterContentPadding = 0,
            ),
        )
    }

    @Test
    fun resolveFocusedThreadRepliesPlacementMatchesRepliesFormula() {
        val rawInfo =
            RawInfo(
                visibleItemsInfo = listOf(RawItem(index = 31, offset = 0, size = 120)),
                totalItemsCount = 52,
                layoutSize = 1000,
                afterContentPadding = 40,
            )
        val decision =
            resolveThreadInitialFocusedPlacement(
                layoutInfo = rawInfo,
                focusedForwardIndex = 20,
                placement = LazyViewportAnchorPlacement.FocusedThreadReplies,
                minBottomReservePx = 100,
            )
        val scroll = assertIs<ThreadInitialFocusedPlacementDecision.Scroll>(decision)
        val expectedForwardTop =
            -threadRepliesFocusedItemScrollOffset(
                itemSize = 120,
                viewportEnd = 960,
                minBottomReservePx = 100,
            )
        assertEquals(
            expectedForwardTop,
            reversedThreadListItemOffset(
                rawOffset = -scroll.request.scrollOffset,
                size = 120,
                layoutSize = 1000,
                rawBeforeContentPadding = 0,
                rawAfterContentPadding = 40,
            ),
        )
    }

    @Test
    fun resolveReturnsAlreadyAlignedWhenRowAlreadyAtTargetAndPendingWhenNotMeasured() {
        val alignedInfo =
            RawInfo(
                visibleItemsInfo = listOf(RawItem(index = 31, offset = 880, size = 120)),
                totalItemsCount = 52,
                layoutSize = 1000,
            )
        assertEquals(
            ThreadInitialFocusedPlacementDecision.AlreadyAligned,
            resolveThreadInitialFocusedPlacement(
                layoutInfo = alignedInfo,
                focusedForwardIndex = 20,
                placement = LazyViewportAnchorPlacement.Focused,
                minBottomReservePx = 100,
            ),
        )

        val unmeasuredInfo =
            RawInfo(
                visibleItemsInfo = listOf(RawItem(index = 0, offset = 0, size = 40)),
                totalItemsCount = 52,
                layoutSize = 1000,
            )
        assertEquals(
            ThreadInitialFocusedPlacementDecision.Pending,
            resolveThreadInitialFocusedPlacement(
                layoutInfo = unmeasuredInfo,
                focusedForwardIndex = 20,
                placement = LazyViewportAnchorPlacement.Focused,
                minBottomReservePx = 100,
            ),
        )
    }
}
