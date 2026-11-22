package build.raft.app

import build.raft.app.data.*
import kotlin.test.*

/** task #37 / artin task #20: user-resizable, per-kind list column of the wide list + detail split. */
class AdaptiveListPaneWidthTest {
    private val sidebar = RaftListPaneKind.Sidebar
    private val list = RaftListPaneKind.List

    @Test fun nothingSavedOrNothingValidStartsAtTheKindDefault() {
        for (kind in RaftListPaneKind.entries) {
            assertEquals(kind.defaultDp, raftListPanePreferredWidthDp(kind, null))
            assertEquals(kind.defaultDp, raftListPanePreferredWidthDp(kind, "wide"))
            assertEquals(kind.defaultDp, raftListPanePreferredWidthDp(kind, "NaN"))
            assertEquals(kind.defaultDp, raftListPanePreferredWidthDp(kind, "Infinity"))
            assertEquals(kind.defaultDp, raftListPanePreferredWidthDp(kind, " "))
        }
    }

    @Test fun aSavedWidthIsClampedIntoTheKindRangeNotReset() {
        assertEquals(300f, raftListPanePreferredWidthDp(sidebar, "300"))
        assertEquals(200f, raftListPanePreferredWidthDp(sidebar, "100"))
        assertEquals(400f, raftListPanePreferredWidthDp(sidebar, "9000"))
        assertEquals(420f, raftListPanePreferredWidthDp(list, "420"))
        assertEquals(240f, raftListPanePreferredWidthDp(list, "100"))
        assertEquals(640f, raftListPanePreferredWidthDp(list, "9000"))
        // Round trip through what a finished drag saves.
        assertEquals(433f, raftListPanePreferredWidthDp(list, raftListPaneStoredWidth(list, 433.7f)))
        assertEquals("400", raftListPaneStoredWidth(sidebar, 433.7f))
    }

    @Test fun theOldSingleWidthCarriesIntoEveryKindClamped() {
        // Pre-#20 range was 280..520: a dragged 300 stays 300 in both kinds, 520 is 400 / 520.
        assertEquals(300f, raftListPanePreferredWidthDp(sidebar, null, "300"))
        assertEquals(300f, raftListPanePreferredWidthDp(list, null, "300"))
        assertEquals(400f, raftListPanePreferredWidthDp(sidebar, null, "520"))
        assertEquals(520f, raftListPanePreferredWidthDp(list, null, "520"))
        // A kind's own value wins over the old one; an invalid own value falls back to it.
        assertEquals(250f, raftListPanePreferredWidthDp(sidebar, "250", "300"))
        assertEquals(300f, raftListPanePreferredWidthDp(sidebar, "junk", "300"))
        assertEquals(sidebar.defaultDp, raftListPanePreferredWidthDp(sidebar, null, "junk"))
        // What the store writes under each kind's key when moving the old value over.
        assertEquals("400", raftListPaneMigratedWidth(sidebar, null, "520"))
        assertEquals("520", raftListPaneMigratedWidth(list, null, "520"))
        assertEquals("200", raftListPaneMigratedWidth(sidebar, null, "150"))
        assertNull(raftListPaneMigratedWidth(sidebar, "250", "300"))
        assertNull(raftListPaneMigratedWidth(list, null, null))
        assertNull(raftListPaneMigratedWidth(list, null, "NaN"))
    }

    @Test fun renderedWidthStaysWithinTheKindAndLeavesTheMainItsMinimum() {
        assertEquals(400f, raftListPaneWidthDp(sidebar, 900f, 2000f))
        assertEquals(200f, raftListPaneWidthDp(sidebar, 10f, 2000f))
        assertEquals(640f, raftListPaneWidthDp(list, 900f, 2000f))
        assertEquals(240f, raftListPaneWidthDp(list, 10f, 2000f))
        // A 600dp list in an 800dp row: the main keeps 360dp, the list gets 439dp.
        val clamped = raftListPaneWidthDp(list, 600f, 800f)
        assertEquals(800f - RAFT_LIST_PANE_DIVIDER_DP - RAFT_LIST_PANE_MAIN_MIN_DP, clamped)
        // A row too small for both minimums keeps the list minimum rather than collapsing it.
        assertEquals(240f, raftListPaneWidthDp(list, 380f, 500f))
        assertEquals(200f, raftListPaneWidthDp(sidebar, 240f, 500f))
    }

    @Test fun windowClampDoesNotChangeThePreference() {
        val preferred = 600f
        // Shrunk window, then grown back: the user's width returns.
        assertTrue(raftListPaneWidthDp(list, preferred, 800f) < preferred)
        assertEquals(preferred, raftListPaneWidthDp(list, preferred, 1400f))
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

    @Test fun aDragMoreThanFortyUnderTheMinimumCollapses() {
        // web ColumnResizeHandle SNAP_PAST_END: `reach < min - 40`, before any clamp.
        assertEquals(40f, RAFT_LIST_PANE_SNAP_PAST_END_DP)
        // Sidebar 200 minimum: from 240, a drag to 160 stops at the minimum; past 160 it collapses.
        assertFalse(raftListPaneDragCollapses(sidebar, 240f, -80f))
        assertTrue(raftListPaneDragCollapses(sidebar, 240f, -81f))
        // List 240 minimum: the line is at 200.
        assertFalse(raftListPaneDragCollapses(list, 380f, -180f))
        assertTrue(raftListPaneDragCollapses(list, 380f, -181f))
        // Back across the line while still held: no longer collapsed.
        assertFalse(raftListPaneDragCollapses(sidebar, 240f, -20f))
    }

    @Test fun aCommittedWidthIsSavedPerDeviceAndAPreviewIsNot() {
        val values = InMemoryRaftKeyValueStore()
        val widths = RaftListPaneWidths(KeyValueChatLayoutPreferenceStore(values))
        // A drag in flight shows its width and saves nothing.
        widths.preview(sidebar, 300f)
        assertEquals(300f, widths.preferredDp(sidebar))
        assertNull(values.getString(sidebar.storageKey))
        // A key, a double click or a drag's end saves it, within the kind's range.
        widths.commit(sidebar, 512.4f)
        assertEquals("400", values.getString(sidebar.storageKey))
        widths.commit(sidebar, 287.6f)
        assertEquals("287", values.getString(sidebar.storageKey))
        // The next launch reads it back; the other kind keeps its own default.
        val relaunched = RaftListPaneWidths(KeyValueChatLayoutPreferenceStore(values))
        assertEquals(287f, relaunched.preferredDp(sidebar))
        assertEquals(list.defaultDp, relaunched.preferredDp(list))
    }
}
