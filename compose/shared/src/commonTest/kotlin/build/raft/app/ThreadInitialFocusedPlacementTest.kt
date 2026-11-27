package build.raft.app

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import build.raft.app.thread.LazyViewportAnchorPlacement
import build.raft.app.thread.ThreadInitialFocusedPlacementDecision
import build.raft.app.thread.key
import build.raft.app.thread.resolveThreadInitialFocusedPlacement
import build.raft.app.thread.reversedThreadListItemOffset
import build.raft.app.thread.shouldArmThreadInitialFocusedPlacement
import build.raft.app.thread.threadInitialFirstVisibleItemIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        // Out-of-range focused index, empty list, and single-item list.
        assertEquals(
            0,
            threadInitialFirstVisibleItemIndex(
                reverseLayout = true,
                latestItemIndex = 51,
                lazyItemCount = 52,
                focusedItemIndex = 52,
            ),
        )
        assertEquals(
            0,
            threadInitialFirstVisibleItemIndex(
                reverseLayout = true,
                latestItemIndex = -1,
                lazyItemCount = 0,
                focusedItemIndex = 0,
            ),
        )
        assertEquals(
            0,
            threadInitialFirstVisibleItemIndex(
                reverseLayout = true,
                latestItemIndex = 0,
                lazyItemCount = 1,
                focusedItemIndex = 0,
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
    fun shouldArmInitialFocusedPlacementStaysArmedForReplyThreadAwaitingCachedReplies() {
        // Reply thread painting only originMessage while waiting for cached replies -> stay armed.
        assertTrue(
            shouldArmThreadInitialFocusedPlacement(
                reverseLayout = true,
                adaptiveAnchorRestoreInFlight = false,
                initialFocusedItemIndex = -1,
                messagesEmpty = true,
                focusedMessageId = "reply-5",
            )
        )
        // Cached focused target already in slice -> armed.
        assertTrue(
            shouldArmThreadInitialFocusedPlacement(
                reverseLayout = true,
                adaptiveAnchorRestoreInFlight = false,
                initialFocusedItemIndex = 4,
                messagesEmpty = false,
                focusedMessageId = "reply-5",
            )
        )
        // Non-empty messages without focused target in slice -> not armed.
        assertFalse(
            shouldArmThreadInitialFocusedPlacement(
                reverseLayout = true,
                adaptiveAnchorRestoreInFlight = false,
                initialFocusedItemIndex = -1,
                messagesEmpty = false,
                focusedMessageId = "reply-5",
            )
        )
        // No focusedMessageId -> not armed.
        assertFalse(
            shouldArmThreadInitialFocusedPlacement(
                reverseLayout = true,
                adaptiveAnchorRestoreInFlight = false,
                initialFocusedItemIndex = -1,
                messagesEmpty = true,
                focusedMessageId = " ",
            )
        )
        // Adaptive restore in flight or forward layout -> not armed.
        assertFalse(
            shouldArmThreadInitialFocusedPlacement(
                reverseLayout = true,
                adaptiveAnchorRestoreInFlight = true,
                initialFocusedItemIndex = 4,
                messagesEmpty = false,
                focusedMessageId = "reply-5",
            )
        )
        assertFalse(
            shouldArmThreadInitialFocusedPlacement(
                reverseLayout = false,
                adaptiveAnchorRestoreInFlight = false,
                initialFocusedItemIndex = -1,
                messagesEmpty = true,
                focusedMessageId = "reply-5",
            )
        )
    }
}
