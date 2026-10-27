package build.raft.app

import androidx.compose.ui.unit.dp
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
            newerPageCycleActive = false,
            newerHistoryAvailable = false,
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
        assertEquals(ThreadTailLayoutAnchor.None, resolveThreadTailLayoutAnchor(append.copy(newerHistoryAvailable = true)))
        assertEquals(ThreadTailLayoutAnchor.None, resolveThreadTailLayoutAnchor(append.copy(newerPageCycleActive = true)))
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

    @Test
    fun newerPageCycleFencesEveryChangeIncludingKeyboardShrink() {
        // The newer-page restoration owns the viewport until it settles (it restores a captured
        // exact offset); the anchor must not become a second programmatic actor for a shrink.
        val shrink = parked.copy(nextViewportPx = 900)
        assertEquals(ThreadTailLayoutAnchor.None, resolveThreadTailLayoutAnchor(shrink.copy(newerPageCycleActive = true)))
        // Newer history alone (no cycle in flight) gates appends only; a keyboard shrink still pins.
        assertEquals(ThreadTailLayoutAnchor.PinTail, resolveThreadTailLayoutAnchor(shrink.copy(newerHistoryAvailable = true)))
    }

    @Test
    fun suppressWhileImeOpenPolicyDisablesTheAnchor() {
        val suppress = ThreadImeProgrammaticViewportPolicy.SuppressWhileImeOpen
        assertEquals(
            ThreadTailLayoutAnchor.None,
            resolveThreadTailLayoutAnchor(parked.copy(nextViewportPx = 900, imeProgrammaticViewportPolicy = suppress)),
        )
        assertEquals(
            ThreadTailLayoutAnchor.None,
            resolveThreadTailLayoutAnchor(
                parked.copy(nextItemCount = 11, nextLastMessageId = "m9", imeProgrammaticViewportPolicy = suppress),
            ),
        )
    }

    @Test
    fun contentThatFitsTheViewportCountsAsParked() {
        // Short thread: the tail ends well above the viewport end and nothing can scroll.
        assertTrue(
            threadTailParkedInPreviousLayout(
                tailVisible = true,
                tailOffsetPx = 400,
                tailSizePx = 42,
                viewportEndPx = 1225,
                canScrollForward = false,
                canScrollBackward = false,
            ),
        )
        // Scrolled away from a long tail that happens to be visible but not flush.
        assertFalse(
            threadTailParkedInPreviousLayout(
                tailVisible = true,
                tailOffsetPx = 1300,
                tailSizePx = 42,
                viewportEndPx = 1225,
                canScrollForward = true,
                canScrollBackward = true,
            ),
        )
        // Flush tail of a scrollable list.
        assertTrue(
            threadTailParkedInPreviousLayout(
                tailVisible = true,
                tailOffsetPx = 1183,
                tailSizePx = 42,
                viewportEndPx = 1225,
                canScrollForward = false,
                canScrollBackward = true,
            ),
        )
        assertFalse(
            threadTailParkedInPreviousLayout(
                tailVisible = false,
                tailOffsetPx = 0,
                tailSizePx = 0,
                viewportEndPx = 1225,
                canScrollForward = false,
                canScrollBackward = false,
            ),
        )
        // The keyboard then shrinks the viewport below the content height: the tail is pinned.
        assertEquals(
            ThreadTailLayoutAnchor.PinTail,
            resolveThreadTailLayoutAnchor(parked.copy(previousViewportPx = 1225, nextViewportPx = 380)),
        )
    }

    @Test
    fun iosComposerPaddingNeverDipsBelowTheRestingSafeArea() {
        val safeArea = 34.dp
        val resting = threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = safeArea)
        // Keyboard frame includes the safe area: every animation step stays at or above rest and
        // grows monotonically with the keyboard.
        var previous = resting
        for (ime in listOf(1f, 5f, 20f, 34f, 35f, 120f, 336f)) {
            val padding = threadComposerBottomPadding(keyboardInsetDp = ime, bottomSafeArea = safeArea)
            assertTrue(padding >= resting, "ime=$ime padding=$padding resting=$resting")
            assertTrue(padding >= previous, "ime=$ime padding=$padding previous=$previous")
            previous = padding
        }
        assertEquals(resting, threadComposerBottomPadding(keyboardInsetDp = 5f, bottomSafeArea = safeArea))
        assertEquals(
            threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 0.dp) + 336.dp,
            threadComposerBottomPadding(keyboardInsetDp = 336f, bottomSafeArea = safeArea),
        )
        // Android (keyboard excludes the safe area) keeps adding it back.
        assertEquals(
            resting + 5.dp,
            threadComposerBottomPadding(
                keyboardInsetDp = 5f,
                bottomSafeArea = safeArea,
                keyboardInsetExcludesBottomSafeArea = true,
            ),
        )
    }

    @Test
    fun desktopComposerKeepsTheWebBottomGapWithNoSafeArea() {
        assertEquals(16.dp, threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 0.dp, minimumBottomPadding = 16.dp))
        assertEquals(
            threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 34.dp, minimumBottomPadding = 0.dp),
            threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 34.dp, minimumBottomPadding = 16.dp),
        )
    }
}
