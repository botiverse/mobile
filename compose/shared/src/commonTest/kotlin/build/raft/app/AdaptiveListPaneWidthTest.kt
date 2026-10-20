package build.raft.app

import build.raft.app.data.*
import kotlin.test.*

/** task #37: user-resizable list column of the wide list + detail split. */
class AdaptiveListPaneWidthTest {
    @Test fun savedWidthIsUsedOnlyWhenValid() {
        assertEquals(RAFT_LIST_PANE_DEFAULT_DP, raftListPanePreferredWidthDp(null))
        assertEquals(RAFT_LIST_PANE_DEFAULT_DP, raftListPanePreferredWidthDp("wide"))
        assertEquals(RAFT_LIST_PANE_DEFAULT_DP, raftListPanePreferredWidthDp("NaN"))
        assertEquals(RAFT_LIST_PANE_DEFAULT_DP, raftListPanePreferredWidthDp("100"))
        assertEquals(RAFT_LIST_PANE_DEFAULT_DP, raftListPanePreferredWidthDp("9000"))
        assertEquals(420f, raftListPanePreferredWidthDp("420"))
        // Round trip through what a finished drag saves.
        assertEquals(433f, raftListPanePreferredWidthDp(raftListPaneStoredWidth(433.7f)))
    }

    @Test fun defaultKeepsTodaysListWidthOnEveryDualPaneWindow() {
        // Narrowest dual-pane row (840dp window minus the rail) still fits the default 360dp list.
        assertEquals(360f, raftListPaneWidthDp(RAFT_LIST_PANE_DEFAULT_DP, 768f))
        assertEquals(360f, raftListPaneWidthDp(RAFT_LIST_PANE_DEFAULT_DP, 1600f))
        assertEquals(360f, raftListPaneWidthDp(RAFT_LIST_PANE_DEFAULT_DP, null))
    }

    @Test fun renderedWidthStaysWithinLimitsAndLeavesTheDetailItsMinimum() {
        assertEquals(RAFT_LIST_PANE_MAX_DP, raftListPaneWidthDp(900f, 2000f))
        assertEquals(RAFT_LIST_PANE_MIN_DP, raftListPaneWidthDp(10f, 2000f))
        // A 500dp preference in a 800dp row: detail keeps 400dp, list gets 399dp.
        val clamped = raftListPaneWidthDp(500f, 800f)
        assertEquals(800f - RAFT_LIST_PANE_DIVIDER_DP - RAFT_LIST_PANE_DETAIL_MIN_DP, clamped)
        assertTrue(800f - RAFT_LIST_PANE_DIVIDER_DP - clamped >= RAFT_LIST_PANE_DETAIL_MIN_DP)
        // A row too small for both minimums keeps the list minimum rather than collapsing it.
        assertEquals(RAFT_LIST_PANE_MIN_DP, raftListPaneWidthDp(360f, 500f))
    }

    @Test fun windowClampDoesNotChangeThePreference() {
        val preferred = 500f
        // Shrunk window, then grown back: the user's width returns.
        assertTrue(raftListPaneWidthDp(preferred, 800f) < preferred)
        assertEquals(preferred, raftListPaneWidthDp(preferred, 1400f))
    }

    @Test fun dragStartsFromTheRenderedWidthAndIsClamped() {
        assertEquals(400f, raftListPaneDraggedWidthDp(360f, 40f, 1400f))
        assertEquals(RAFT_LIST_PANE_MIN_DP, raftListPaneDraggedWidthDp(360f, -500f, 1400f))
        assertEquals(RAFT_LIST_PANE_MAX_DP, raftListPaneDraggedWidthDp(360f, 500f, 1400f))
        // In a clamped window, a drag from the rendered 399dp cannot push the detail under 400dp.
        assertEquals(399f, raftListPaneDraggedWidthDp(399f, 50f, 800f))
    }

    @Test fun wideningTheListCanTakeTheRoomTheSideColumnNeeds() {
        // F4 needs 841dp of detail; a 1440dp row with a 520dp list leaves 919dp, a row of 1300dp
        // leaves 779dp, so the side thread folds once the drag ends (SettingsHomePage).
        val needed = RAFT_SIDE_THREAD_MAIN_MIN_DP + RAFT_SIDE_THREAD_DIVIDER_DP + RAFT_SIDE_THREAD_COLUMN_DP
        fun detail(row: Float) = row - RAFT_LIST_PANE_DIVIDER_DP - raftListPaneWidthDp(RAFT_LIST_PANE_MAX_DP, row)
        assertTrue(detail(1440f) >= needed)
        assertTrue(detail(1300f) < needed)
    }

    @Test fun aDragEndsExactlyOnceWhicheverEndArrivesFirst() {
        val reasons = mutableListOf<String>()
        val session = RaftListPaneDragSession(onEnd = { reasons += it })
        // Nothing to end before a drag starts.
        assertFalse(session.end("lift"))
        assertNull(session.moveTo(10f, 2f))
        session.start(360f)
        assertEquals(360f to 20f, session.moveTo(40f, 2f))
        // Host goes inactive (tab switch), then the gesture's own cancel and lift arrive late.
        assertTrue(session.end("inactive"))
        assertFalse(session.end("cancel"))
        assertFalse(session.end("lift"))
        assertEquals(listOf("inactive"), reasons)
        // Movement after the end (finger still down) changes nothing.
        assertNull(session.moveTo(80f, 2f))
        assertFalse(session.active)
    }

    @Test fun aNewDragStartsCleanAndAnUnfinishedOneEndsFirst() {
        val reasons = mutableListOf<String>()
        val session = RaftListPaneDragSession(onEnd = { reasons += it })
        session.start(360f)
        session.moveTo(100f, 2f)
        // A second start without an end (e.g. a missed lift) ends the first one exactly once.
        session.start(400f)
        assertEquals(listOf("restart"), reasons)
        assertEquals(400f to 5f, session.moveTo(10f, 2f))
        assertTrue(session.end("lift"))
        assertEquals(listOf("restart", "lift"), reasons)
    }

    @Test fun theDeltaIsTheTotalFromTheDragOriginNotASumOfSteps() {
        val session = RaftListPaneDragSession(onEnd = { _ -> })
        session.start(360f)
        // The finger is 300px past the origin, then back to 200px: the delta follows the finger,
        // whatever the handle did in between (it moves and widens mid-drag).
        assertEquals(360f to 150f, session.moveTo(300f, 2f))
        assertEquals(360f to 100f, session.moveTo(200f, 2f))
        // Past the list limit the finger keeps going; the drag stays alive and keeps reporting.
        assertEquals(360f to 400f, session.moveTo(800f, 2f))
        assertTrue(session.active)
        // A non-positive density is ignored rather than dividing by zero.
        assertNull(session.moveTo(20f, 0f))
    }
}
