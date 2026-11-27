package build.raft.app

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import build.raft.app.thread.LazyViewportAnchorPlacement
import build.raft.app.thread.ThreadTimelinePlacementResizeDecision
import build.raft.app.thread.ThreadTimelinePlacementTracker
import build.raft.app.thread.usableViewportHeightPx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for `#wg-mobile-sync:#350` (`botiverse/slock` `#17076`, `timelinePlacement.ts`):
 * pure state-machine verification for initial focus re-placement across viewport height changes
 * before user scroll, width-change suppression, and retirement on user scroll / IME / follow-latest.
 */
class ThreadTimelinePlacementTest {
    private fun fakeLayoutInfo(
        viewportStart: Int,
        viewportEnd: Int,
        beforePadding: Int,
        afterPadding: Int,
        reverseLayout: Boolean = false,
    ): LazyListLayoutInfo =
        object : LazyListLayoutInfo {
            override val visibleItemsInfo: List<LazyListItemInfo> = emptyList()
            override val viewportStartOffset: Int = viewportStart
            override val viewportEndOffset: Int = viewportEnd
            override val totalItemsCount: Int = 0
            override val viewportSize: IntSize = IntSize(400, viewportEnd - viewportStart)
            override val orientation: Orientation = Orientation.Vertical
            override val reverseLayout: Boolean = reverseLayout
            override val beforeContentPadding: Int = beforePadding
            override val afterContentPadding: Int = afterPadding
            override val mainAxisItemSpacing: Int = 0
        }

    @Test
    fun shrinkAndGrowEmitReplaceWhileRepeatedHeightAndWidthChangeDoNot() {
        val tracker = ThreadTimelinePlacementTracker()
        tracker.place(
            messageId = "m6",
            placement = LazyViewportAnchorPlacement.Focused,
            userScrollGeneration = 0,
            viewportHeightPx = 600,
            viewportWidthPx = 400,
        )

        // Repeated identical height -> None.
        assertEquals(
            ThreadTimelinePlacementResizeDecision.None,
            tracker.onViewportGeometry(600, 400, 0, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )

        // Height shrink (-69px) -> Replace.
        assertEquals(
            ThreadTimelinePlacementResizeDecision.Replace("m6", LazyViewportAnchorPlacement.Focused, 600, 531),
            tracker.onViewportGeometry(531, 400, 0, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )

        // Sequential shrink (-40px) -> Replace against updated 531px baseline.
        assertEquals(
            ThreadTimelinePlacementResizeDecision.Replace("m6", LazyViewportAnchorPlacement.Focused, 531, 491),
            tracker.onViewportGeometry(491, 400, 0, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )

        // Height grow (+40px, e.g. announcement placeholder collapse) -> Replace.
        assertEquals(
            ThreadTimelinePlacementResizeDecision.Replace("m6", LazyViewportAnchorPlacement.Focused, 491, 531),
            tracker.onViewportGeometry(531, 400, 0, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )

        // Width + height change (rotation / split-pane) -> None, but baseline updates to (700, 800).
        assertEquals(
            ThreadTimelinePlacementResizeDecision.None,
            tracker.onViewportGeometry(700, 800, 0, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )

        // Subsequent height-only shrink compares against post-width-change height 700.
        assertEquals(
            ThreadTimelinePlacementResizeDecision.Replace("m6", LazyViewportAnchorPlacement.Focused, 700, 640),
            tracker.onViewportGeometry(640, 800, 0, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )
    }

    @Test
    fun unmeasuredInitialHeightLatchesFirstPositiveHeightBeforeEmittingReplace() {
        val tracker = ThreadTimelinePlacementTracker()
        tracker.place(
            messageId = "m12",
            placement = LazyViewportAnchorPlacement.FocusedThreadReplies,
            userScrollGeneration = 2,
            viewportHeightPx = 0,
            viewportWidthPx = 0,
        )
        assertEquals(
            ThreadTimelinePlacementResizeDecision.None,
            tracker.onViewportGeometry(600, 400, 2, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )
        assertEquals(
            ThreadTimelinePlacementResizeDecision.Replace("m12", LazyViewportAnchorPlacement.FocusedThreadReplies, 600, 520),
            tracker.onViewportGeometry(520, 400, 2, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )
    }

    @Test
    fun userScrollImeFollowLatestAndClearRetirePlacementPermanently() {
        fun armedTracker() =
            ThreadTimelinePlacementTracker().apply {
                place("m6", LazyViewportAnchorPlacement.Focused, userScrollGeneration = 1, viewportHeightPx = 600, viewportWidthPx = 400)
            }

        // User scroll generation increment retires.
        val byGen = armedTracker()
        assertEquals(
            ThreadTimelinePlacementResizeDecision.Cleared,
            byGen.onViewportGeometry(531, 400, currentUserScrollGeneration = 2, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )
        assertNull(byGen.current)
        assertEquals(
            ThreadTimelinePlacementResizeDecision.None,
            byGen.onViewportGeometry(480, 400, currentUserScrollGeneration = 2, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )

        // Active scroll retires.
        val byScroll = armedTracker()
        assertEquals(
            ThreadTimelinePlacementResizeDecision.Cleared,
            byScroll.onViewportGeometry(531, 400, 1, scrollInProgress = true, imeOpen = false, followingLatest = false),
        )
        assertNull(byScroll.current)

        // IME open retires (and later close/grow must never yank back).
        val byIme = armedTracker()
        assertEquals(
            ThreadTimelinePlacementResizeDecision.Cleared,
            byIme.onViewportGeometry(300, 400, 1, scrollInProgress = false, imeOpen = true, followingLatest = false),
        )
        assertEquals(
            ThreadTimelinePlacementResizeDecision.None,
            byIme.onViewportGeometry(600, 400, 1, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )

        // FollowingLatest retires.
        val byFollow = armedTracker()
        assertEquals(
            ThreadTimelinePlacementResizeDecision.Cleared,
            byFollow.onViewportGeometry(531, 400, 1, scrollInProgress = false, imeOpen = false, followingLatest = true),
        )
        assertNull(byFollow.current)

        // Explicit clear retires.
        val byClear = armedTracker()
        byClear.clear()
        assertEquals(
            ThreadTimelinePlacementResizeDecision.None,
            byClear.onViewportGeometry(531, 400, 1, scrollInProgress = false, imeOpen = false, followingLatest = false),
        )
    }

    @Test
    fun placeIfAbsentPreservesFrame1BaselineAndUnsupportedPlacementsClear() {
        val tracker = ThreadTimelinePlacementTracker()
        tracker.place("m6", LazyViewportAnchorPlacement.Focused, 0, 600, 400)
        // Frame 2 effect sees already-shrunk 531px: placeIfAbsent keeps Frame 1's 600px so the shrink still fires.
        tracker.placeIfAbsent("m6", LazyViewportAnchorPlacement.Focused, 0, 531, 400)
        assertEquals(600, tracker.current?.viewportHeightPx)

        // Non-focused placements (ExactOffset / RenderedBottom) clear instead of arming.
        tracker.place("m6", LazyViewportAnchorPlacement.ExactOffset, 0, 600, 400)
        assertNull(tracker.current)
        tracker.place("m6", LazyViewportAnchorPlacement.RenderedBottom, 0, 600, 400)
        assertNull(tracker.current)
    }

    @Test
    fun usableViewportHeightPxMatchesAcrossForwardAndReversedPadding() {
        val forward = fakeLayoutInfo(viewportStart = 0, viewportEnd = 600, beforePadding = 40, afterPadding = 16, reverseLayout = false)
        val reversed = fakeLayoutInfo(viewportStart = 0, viewportEnd = 600, beforePadding = 16, afterPadding = 40, reverseLayout = true)
        assertEquals(544, forward.usableViewportHeightPx())
        assertEquals(544, reversed.usableViewportHeightPx())
    }
}
