package build.raft.app

import build.raft.app.data.parseSharedInboxSnapshot
import build.raft.app.data.parseSharedSavedItems
import build.raft.app.data.parseSharedSearchItems
import build.raft.app.data.parseSharedServerTasksPayload
import build.raft.app.model.InboxItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The response bodies the visual cases share with the web (VisualFixtureWire) are read whole by the
 * parsers the app reads the real responses with: a row dropped here is a row the capture would miss.
 */
class VisualFixtureWireTest {
    private val diagnostics = mutableListOf<String>()

    @Test
    fun activityRows() {
        val inbox = assertNotNull(parseSharedInboxSnapshot(VisualFixtureWire.ACTIVITY_RESULTS, diagnostics::add))
        assertEquals(
            listOf(InboxItemKind.Thread, InboxItemKind.Channel, InboxItemKind.DirectMessage),
            inbox.items.map { it.kind },
        )
        assertEquals(inbox.rawItemCount, inbox.items.size)
        assertEquals(inbox.totalCount, inbox.items.size)
        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun savedRows() {
        val saved = assertNotNull(parseSharedSavedItems(VisualFixtureWire.SAVED_MESSAGES, diagnostics::add))
        assertEquals(saved.rawResultCount, saved.items.size)
        assertEquals(5, saved.totalCount)
        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun searchRows() {
        val search = assertNotNull(parseSharedSearchItems(VisualFixtureWire.SEARCH_RESULTS, diagnostics::add))
        assertEquals(2, search.items.size)
        assertEquals(search.rawResultCount, search.items.size)
        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun taskRows() {
        val tasks = assertNotNull(parseSharedServerTasksPayload(VisualFixtureWire.TASKS, diagnostics::add))
        assertEquals(listOf(214, 212, 210, 208), tasks.tasks.map { it.taskNumber })
        assertEquals(emptyList(), diagnostics)
    }
}
