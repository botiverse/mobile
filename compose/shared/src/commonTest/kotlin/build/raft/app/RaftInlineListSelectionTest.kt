package build.raft.app

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

    @Test fun anotherRowOpeningAnotherConversationTakesTheMark() {
        val first = RaftInlineOpenedRow("r1", "d1")
        val next = raftInlineListBindOpenedRow(first, rowKey = "r2", detailKeyBefore = "d1", detailKeyAfter = "d2")
        assertEquals("r2", raftInlineListSelectedRowKey(next, openDetailKey = "d2"))
    }

    @Test fun aTapThatOpensNothingBesideTheListKeepsTheMark() {
        val first = RaftInlineOpenedRow("r1", "d1")
        // A member result pushes its own page; an unresolved row only toasts: the pane is unchanged.
        assertEquals(first, raftInlineListBindOpenedRow(first, rowKey = "member", detailKeyBefore = "d1", detailKeyAfter = "d1"))
        // Nothing was open and nothing landed: still nothing selected.
        assertNull(raftInlineListBindOpenedRow(null, rowKey = "member", detailKeyBefore = null, detailKeyAfter = null))
        // A detail opened from Home is not claimed by a tap that did not open it.
        assertNull(raftInlineListBindOpenedRow(null, rowKey = "member", detailKeyBefore = "home", detailKeyAfter = "home"))
    }

    @Test fun theSelectionHolderBindsAfterTheSynchronousOpen() {
        var live: String? = null
        val selection = RaftInlineListSelection { live }
        selection.open("r1") { live = "d1" }
        selection.openDetailKey = "d1"
        assertEquals("r1", selection.selectedRowKey)
        selection.openDetailKey = null
        assertNull(selection.selectedRowKey)
    }

    @Test fun withoutAHostTheTapStillOpens() {
        var opened = false
        val none: RaftInlineListSelection? = null
        none.opening("r1") { opened = true }
        assertEquals(true, opened)
    }
}
