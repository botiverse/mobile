package build.raft.app

import build.raft.app.thread.LazyViewportAnchorPlacement
import build.raft.app.thread.threadFocusedAnchorBasePlacement
import build.raft.app.thread.threadRepliesFocusedItemScrollOffset
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
