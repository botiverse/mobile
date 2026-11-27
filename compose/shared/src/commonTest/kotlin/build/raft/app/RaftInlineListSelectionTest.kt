package build.raft.app

import build.raft.app.shell.RaftInlineListSelection
import build.raft.app.shell.RaftInlineOpenedRow
import build.raft.app.shell.opening
import build.raft.app.shell.raftInlineListBindOpenedRow
import build.raft.app.shell.raftInlineListSelectedRowKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Two-pane inline Search and Saved: the row whose conversation is open beside the list is
 * selected, and only while it stays open (the Home / Activity behaviour).
 */
class RaftInlineListSelectionTest {
    @Test fun theRowThatOpenedTheDetailIsSelectedWhileItStaysOpen() {
        val opened = raftInlineListBindOpenedRow(null, rowKey = "r1", detailKeyBefore = null, detailKeyAfter = "d1")
        assertEquals(RaftInlineOpenedRow("r1", "d1"), opened)
        assertEquals("r1", raftInlineListSelectedRowKey(opened, openDetailKey = "d1"))
    }

    @Test fun theMarkClearsWhenTheDetailClosesOrSomethingElseOpens() {
        val opened = RaftInlineOpenedRow("r1", "d1")
        assertNull(raftInlineListSelectedRowKey(opened, openDetailKey = null))
        // Home, Activity or the other inline list opened another conversation.
        assertNull(raftInlineListSelectedRowKey(opened, openDetailKey = "d2"))
        assertNull(raftInlineListSelectedRowKey(null, openDetailKey = "d1"))
    }

    @Test fun withoutAHostTheTapStillOpens() {
        var opened = false
        val none: RaftInlineListSelection? = null
        none.opening("r1") { opened = true }
        assertEquals(true, opened)
    }
}
