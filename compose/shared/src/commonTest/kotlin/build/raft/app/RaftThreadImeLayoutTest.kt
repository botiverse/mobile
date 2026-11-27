package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.thread.RaftThreadComposerPlacement
import build.raft.app.thread.ThreadTailLayoutAnchor
import build.raft.app.thread.ThreadTailLayoutAnchorState
import build.raft.app.thread.raftThreadComposerKeptConstraints
import build.raft.app.thread.raftThreadComposerKeptPlacement
import build.raft.app.thread.raftThreadKeyboardInsetPx
import build.raft.app.thread.resolveThreadTailLayoutAnchor
import build.raft.app.thread.threadComposerBottomPadding
import kotlin.test.Test
import kotlin.test.assertEquals
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
            resting + 5.dp + 14.dp,
            threadComposerBottomPadding(
                keyboardInsetDp = 5f,
                bottomSafeArea = safeArea,
                keyboardInsetExcludesBottomSafeArea = true,
            ),
        )
    }

    @Test
    fun iosRestingComposerPaddingDoesNotStackExtraClearanceOverHomeIndicatorSafeArea() {
        // iPhone Duo landscape (21dp) and iPhone portrait (34dp): the transparent home-indicator
        // safe area already clears the indicator, so resting padding does not stack +14dp on top.
        assertEquals(21.dp, threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 21.dp))
        assertEquals(34.dp, threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 34.dp))
        // Without a bottom safe area (iPad, desktop, zero-safe-area visual captures), base clearance stays 14dp.
        assertEquals(14.dp, threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 0.dp))
        // Desktop minimumBottomPadding (16dp) still wins when no safe area or keyboard is present.
        assertEquals(
            16.dp,
            threadComposerBottomPadding(keyboardInsetDp = 0f, bottomSafeArea = 0.dp, minimumBottomPadding = 16.dp),
        )
        // Android opaque navigation bar still keeps base clearance above safeArea + keyboard.
        assertEquals(
            38.dp,
            threadComposerBottomPadding(
                keyboardInsetDp = 0f,
                bottomSafeArea = 24.dp,
                keyboardInsetExcludesBottomSafeArea = true,
            ),
        )
    }

    @Test
    fun withRoomTheComposerSitsOnTheKeyboardAsBefore() {
        // A phone upright: a 600 slot, a 300 keyboard, a 96 composer.
        val constraints = androidx.compose.ui.unit.Constraints(maxWidth = 393, maxHeight = 600)
        assertEquals(300, raftThreadComposerKeptConstraints(constraints, bottomPx = 300, keptPx = 160).maxHeight)
        assertEquals(RaftThreadComposerPlacement(heightPx = 396, yPx = 0), raftThreadComposerKeptPlacement(600, 96, 300))
        // No keyboard: the composer's own height.
        assertEquals(RaftThreadComposerPlacement(heightPx = 96, yPx = 0), raftThreadComposerKeptPlacement(600, 96, 0))
        assertEquals(600, raftThreadComposerKeptConstraints(constraints, bottomPx = 0, keptPx = 160).maxHeight)
    }

    @Test
    fun aShortSlotKeepsTheComposerWholeAboveTheKeyboard() {
        // #338: 914 x 411 in landscape, the wide shell: a 291 slot under the top bar and the head, a 250 keyboard. Before,
        // the composer got the 41 left and its field was cut in half.
        val constraints = androidx.compose.ui.unit.Constraints(maxWidth = 500, maxHeight = 291)
        assertEquals(160, raftThreadComposerKeptConstraints(constraints, bottomPx = 250, keptPx = 160).maxHeight)
        // Its 96 stand on the keyboard, 55 of them over what is above the slot.
        val placed = raftThreadComposerKeptPlacement(291, 96, 250)
        assertEquals(RaftThreadComposerPlacement(heightPx = 291, yPx = -55), placed)
        assertEquals(291 - 250, placed.yPx + 96, "the composer's bottom is the keyboard's top")
        // A slot shorter than the kept room gives what it has.
        assertEquals(120, raftThreadComposerKeptConstraints(androidx.compose.ui.unit.Constraints(maxHeight = 120), 100, 160).maxHeight)
        // A minimum is never above the maximum.
        val tight = androidx.compose.ui.unit.Constraints(minHeight = 291, maxHeight = 291)
        val kept = raftThreadComposerKeptConstraints(tight, bottomPx = 250, keptPx = 160)
        assertEquals(41, kept.minHeight)
        assertEquals(160, kept.maxHeight)
    }
}
