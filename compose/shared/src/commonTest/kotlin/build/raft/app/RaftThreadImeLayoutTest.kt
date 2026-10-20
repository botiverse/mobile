package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftThreadImeLayoutTest {
    private val parked =
        ThreadTailLayoutAnchorState(
            reverseLayout = false,
            followingLatest = true,
            scrollInProgress = false,
            previousTailAtRenderedBottom = true,
            previousViewportPx = 1225,
            nextViewportPx = 1225,
            previousItemCount = 10,
            nextItemCount = 10,
            previousFirstMessageId = "m1",
            nextFirstMessageId = "m1",
            previousLastMessageId = "m8",
            nextLastMessageId = "m8",
            newerPagingActive = false,
        )

    @Test
    fun keyboardInsetMatchesTheCompatCallbackPerPlatform() {
        // Android: ime - navigationBars (the page adds its bottom safe area back).
        assertEquals(757, raftThreadKeyboardInsetPx(imeBottomPx = 820, navigationBottomPx = 63, excludesNavigationBars = true))
        // The IME starts below the navigation bar: no negative inset while it is still hidden there.
        assertEquals(0, raftThreadKeyboardInsetPx(imeBottomPx = 35, navigationBottomPx = 63, excludesNavigationBars = true))
        // iOS: the full keyboard frame.
        assertEquals(820, raftThreadKeyboardInsetPx(imeBottomPx = 820, navigationBottomPx = 63, excludesNavigationBars = false))
        assertEquals(0, raftThreadKeyboardInsetPx(imeBottomPx = 0, navigationBottomPx = 0, excludesNavigationBars = false))
    }

    @Test
    fun keyboardShrinkWhileParkedOnTheTailPinsTheTailInTheSamePass() {
        assertEquals(
            ThreadTailLayoutAnchor.PinTail,
            resolveThreadTailLayoutAnchor(parked.copy(nextViewportPx = 1109)),
        )
    }

    @Test
    fun genuineTailAppendWhileParkedPinsTheTailInTheSamePass() {
        assertEquals(
            ThreadTailLayoutAnchor.PinTail,
            resolveThreadTailLayoutAnchor(parked.copy(nextItemCount = 11, nextLastMessageId = "m9")),
        )
        // An append landing in the same frame as an IME step is still one pin, not a fight.
        assertEquals(
            ThreadTailLayoutAnchor.PinTail,
            resolveThreadTailLayoutAnchor(
                parked.copy(nextViewportPx = 900, nextItemCount = 11, nextLastMessageId = "m9"),
            ),
        )
    }

    @Test
    fun reversedLayoutNeverNeedsTheCorrection() {
        assertEquals(
            ThreadTailLayoutAnchor.None,
            resolveThreadTailLayoutAnchor(
                parked.copy(reverseLayout = true, nextViewportPx = 900, nextItemCount = 11, nextLastMessageId = "m9"),
            ),
        )
    }

    @Test
    fun userMovementAndNonFollowingIntentsKeepOwnership() {
        val shrink = parked.copy(nextViewportPx = 900)
        assertEquals(ThreadTailLayoutAnchor.None, resolveThreadTailLayoutAnchor(shrink.copy(scrollInProgress = true)))
        assertEquals(ThreadTailLayoutAnchor.None, resolveThreadTailLayoutAnchor(shrink.copy(followingLatest = false)))
        // Leaving the tail retires the parked ownership even when the intent has not caught up yet.
        assertEquals(
            ThreadTailLayoutAnchor.None,
            resolveThreadTailLayoutAnchor(shrink.copy(previousTailAtRenderedBottom = false)),
        )
    }

    @Test
    fun paginationPrependAndReplacementDoNotScroll() {
        val append = parked.copy(nextItemCount = 12, nextLastMessageId = "m10")
        assertEquals(ThreadTailLayoutAnchor.None, resolveThreadTailLayoutAnchor(append.copy(newerPagingActive = true)))
        // Older page: the head changed.
        assertEquals(
            ThreadTailLayoutAnchor.None,
            resolveThreadTailLayoutAnchor(parked.copy(nextItemCount = 40, nextFirstMessageId = "m0")),
        )
        // Same count, different rows (list replacement) together with a resize.
        assertEquals(
            ThreadTailLayoutAnchor.None,
            resolveThreadTailLayoutAnchor(parked.copy(nextViewportPx = 900, nextLastMessageId = "x")),
        )
        // Busy/read-state only mutation: nothing structural changed.
        assertEquals(ThreadTailLayoutAnchor.None, resolveThreadTailLayoutAnchor(parked))
    }

    @Test
    fun growingViewportAndUnknownGeometryAreLeftToTheList() {
        // Keyboard closing: a list at its scroll end already re-fills from the end.
        assertEquals(ThreadTailLayoutAnchor.None, resolveThreadTailLayoutAnchor(parked.copy(nextViewportPx = 1300)))
        assertEquals(ThreadTailLayoutAnchor.None, resolveThreadTailLayoutAnchor(parked.copy(previousViewportPx = -1)))
        assertEquals(
            ThreadTailLayoutAnchor.None,
            resolveThreadTailLayoutAnchor(parked.copy(previousItemCount = 0, nextItemCount = 3, nextLastMessageId = "m9")),
        )
    }

    @Test
    fun tailFlushnessUsesTheBackToBottomTolerance() {
        assertTrue(threadTailEndsAtViewportEnd(tailOffsetPx = 1183, tailSizePx = 42, viewportEndPx = 1225))
        assertTrue(threadTailEndsAtViewportEnd(tailOffsetPx = 1190, tailSizePx = 42, viewportEndPx = 1225))
        assertFalse(threadTailEndsAtViewportEnd(tailOffsetPx = 1250, tailSizePx = 42, viewportEndPx = 1225))
    }
}
