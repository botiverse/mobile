package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task #73 (artin): the unread-thread pill lands a thread parent's replies summary block (the row's
 * bottom part) in view above a bottom reserve, instead of aligning the parent's top to the viewport
 * top. Offsets are forward `scrollToItem` offsets: the row ends up at `top = -offset`.
 */
class ThreadRepliesFocusPlacementTest {
    private val viewportEnd = 2_000
    private val minReserve = 300

    /** The row's top in the viewport after `scrollToItem(index, offset)`. */
    private fun top(itemSize: Int): Int =
        -threadRepliesFocusedItemScrollOffset(itemSize = itemSize, viewportEnd = viewportEnd, minBottomReservePx = minReserve)

    // 25% of 2000 = 500 beats the 300px minimum.
    private val reserve = 500

    @Test
    fun aShortParentIsCentredWhole() {
        val size = 600
        assertEquals((viewportEnd - size) / 2, top(size))
        assertTrue(top(size) >= 0)
        assertTrue(top(size) + size <= viewportEnd - reserve)
    }

    @Test
    fun aParentTooTallToCentreKeepsItsBottomAboveTheReserve() {
        // Centred, a 1200px row would end at 1600, inside the 500px reserve: pinned at 1500 instead.
        val size = 1_200
        assertEquals(viewportEnd - reserve, top(size) + size)
        assertTrue(top(size) >= 0, "the whole row still fits")
    }

    @Test
    fun aParentTallerThanTheViewportShowsItsBottomNotItsTop() {
        // The old placement put this row's top at 0, so its replies block (the last ~400px) sat at
        // 4600..5000, far below the viewport. Now the bottom lands at 1500: the block is at 1100..1500.
        val size = 5_000
        val rowTop = top(size)
        val rowBottom = rowTop + size
        assertEquals(viewportEnd - reserve, rowBottom)
        assertTrue(rowTop < 0, "the parent's top scrolls off")
        val repliesBlockTop = rowBottom - 400
        assertTrue(repliesBlockTop >= 0 && rowBottom <= viewportEnd - reserve, "replies block fully visible")
    }

    @Test
    fun theReserveIsAtLeastTheMinimumAndAtMostHalfTheViewport() {
        // A short viewport: 25% of 800 = 200 < 300, so the 300px minimum (room for the pills) applies.
        val bottom =
            -threadRepliesFocusedItemScrollOffset(itemSize = 3_000, viewportEnd = 800, minBottomReservePx = 300) + 3_000
        assertEquals(500, bottom)
        // A minimum larger than half the viewport is capped there, so the row is never pushed off the top.
        val cappedBottom =
            -threadRepliesFocusedItemScrollOffset(itemSize = 3_000, viewportEnd = 400, minBottomReservePx = 300) + 3_000
        assertEquals(200, cappedBottom)
    }

    @Test
    fun unmeasuredGeometryDoesNotScroll() {
        assertEquals(0, threadRepliesFocusedItemScrollOffset(itemSize = 0, viewportEnd = viewportEnd, minBottomReservePx = minReserve))
        assertEquals(0, threadRepliesFocusedItemScrollOffset(itemSize = 500, viewportEnd = 0, minBottomReservePx = minReserve))
    }

    @Test
    fun onlyThePillsOwnFocusRequestUsesTheRepliesPlacement() {
        assertEquals(
            LazyViewportAnchorPlacement.FocusedThreadReplies,
            threadFocusedAnchorBasePlacement(requestNonce = 7, unreadThreadJumpFocusNonce = 7),
        )
        // A later focus (message link, search hit, Activity open) has a newer nonce.
        assertEquals(
            LazyViewportAnchorPlacement.Focused,
            threadFocusedAnchorBasePlacement(requestNonce = 8, unreadThreadJumpFocusNonce = 7),
        )
        // No pill jump yet: a route focus with nonce 0 is not mistaken for one.
        assertEquals(
            LazyViewportAnchorPlacement.Focused,
            threadFocusedAnchorBasePlacement(requestNonce = 0, unreadThreadJumpFocusNonce = 0),
        )
    }
}
