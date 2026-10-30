package build.raft.app

import build.raft.app.data.*
import kotlin.test.*

/** task #37 / artin task #20: user-resizable, per-kind list column of the wide list + detail split. */
class AdaptiveListPaneWidthTest {
    private val sidebar = RaftListPaneKind.Sidebar
    private val list = RaftListPaneKind.List

    @Test fun eachKindHasTheWebDefaultAndRange() {
        // web lib/panelBounds.ts COLUMN_BOUNDS sidebar / list, MAIN_COLUMN_MIN_WIDTH.
        assertEquals(Triple(240f, 200f, 400f), Triple(sidebar.defaultDp, sidebar.minDp, sidebar.maxDp))
        assertEquals(Triple(380f, 240f, 640f), Triple(list.defaultDp, list.minDp, list.maxDp))
        assertEquals(360f, RAFT_LIST_PANE_MAIN_MIN_DP)
        assertNotEquals(sidebar.storageKey, list.storageKey)
        assertFalse(RaftListPaneKind.entries.any { it.storageKey == RAFT_LIST_PANE_LEGACY_STORAGE_KEY })
    }

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

    @Test fun theStoreMovesTheOldWidthOnceAndDropsTheOldKey() {
        val values = mutableMapOf(RAFT_LIST_PANE_LEGACY_STORAGE_KEY to "300")
        val store =
            KeyValueChatLayoutPreferenceStore(
                object : SharedPreferenceStore {
                    override fun getString(key: String): String? = values[key]
                    override fun putString(key: String, value: String?) {
                        if (value == null) values.remove(key) else values[key] = value
                    }
                }
            )
        assertEquals("300", store.readAdaptiveListPaneWidthSync(list))
        assertEquals("300", store.readAdaptiveListPaneWidthSync(sidebar))
        assertNull(values[RAFT_LIST_PANE_LEGACY_STORAGE_KEY])
        // Later drags are per kind and are not overwritten by a stale old value.
        store.saveAdaptiveListPaneWidth(sidebar, "220")
        assertEquals("220", store.readAdaptiveListPaneWidthSync(sidebar))
        assertEquals("300", store.readAdaptiveListPaneWidthSync(list))
    }

    @Test fun defaultsFitTheNarrowestDualPaneWindow() {
        // Narrowest dual-pane row: the 768dp window (web `md`) minus the 57dp rail. The sidebar
        // keeps its default; the list gives way to the main's minimum, as on the web at `md`.
        val row = DualPaneMinimumWidthDp - 57f
        assertEquals(240f, raftListPaneWidthDp(sidebar, sidebar.defaultDp, row))
        assertEquals(row - RAFT_LIST_PANE_DIVIDER_DP - RAFT_LIST_PANE_MAIN_MIN_DP, raftListPaneWidthDp(list, list.defaultDp, row))
        assertTrue(raftListPaneWidthDp(list, list.defaultDp, row) >= list.minDp)
        assertEquals(380f, raftListPaneWidthDp(list, list.defaultDp, null))
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

    @Test fun dragStartsFromTheRenderedWidthAndIsClamped() {
        assertEquals(420f, raftListPaneDraggedWidthDp(list, 380f, 40f, 1400f))
        assertEquals(240f, raftListPaneDraggedWidthDp(list, 380f, -500f, 1400f))
        assertEquals(640f, raftListPaneDraggedWidthDp(list, 380f, 500f, 1400f))
        assertEquals(400f, raftListPaneDraggedWidthDp(sidebar, 240f, 500f, 1400f))
        // In a clamped window, a drag from the rendered 439dp cannot push the main under 360dp.
        assertEquals(439f, raftListPaneDraggedWidthDp(list, 439f, 50f, 800f))
    }

    @Test fun wideningTheListCanTakeTheRoomTheSideColumnNeeds() {
        // F4 needs 841dp of detail. A 1440dp row with the widest sidebar leaves 1039dp; the widest
        // Activity list leaves 799dp, so the side thread folds once that drag ends (SettingsHomePage).
        val needed = RAFT_SIDE_THREAD_MAIN_MIN_DP + RAFT_SIDE_THREAD_DIVIDER_DP + RAFT_SIDE_THREAD_COLUMN_DP
        fun detail(kind: RaftListPaneKind, row: Float) =
            row - RAFT_LIST_PANE_DIVIDER_DP - raftListPaneWidthDp(kind, kind.maxDp, row)
        assertTrue(detail(sidebar, 1440f) >= needed)
        assertTrue(detail(list, 1440f) < needed)
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
