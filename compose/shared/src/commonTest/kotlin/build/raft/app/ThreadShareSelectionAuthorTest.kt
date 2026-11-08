package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The share image's author headers come from the selection, not the timeline's grouping
 * (web messageGrouping.ts `shouldShowGroupedMessageHeader`).
 */
class ThreadShareSelectionAuthorTest {
    @Test
    fun aSelectedContinuationWhosePredecessorIsNotSelectedShowsItsAuthor() {
        assertFalse(
            threadShareSelectionSuppressesAuthor(
                timelineSuppressesAuthor = true,
                selectionActive = true,
                selected = true,
                previousSelected = false,
            )
        )
    }

    @Test
    fun aSelectedContinuationAfterASelectedRowOfItsRunStaysCompact() {
        assertTrue(
            threadShareSelectionSuppressesAuthor(
                timelineSuppressesAuthor = true,
                selectionActive = true,
                selected = true,
                previousSelected = true,
            )
        )
    }

    @Test
    fun theTimelineGroupingStandsOutsideTheSelection() {
        // Not selecting: the timeline's rows are untouched.
        assertTrue(
            threadShareSelectionSuppressesAuthor(
                timelineSuppressesAuthor = true,
                selectionActive = false,
                selected = true,
                previousSelected = false,
            )
        )
        // Selecting, but this row is not picked: it is not in the image, so it keeps its look.
        assertTrue(
            threadShareSelectionSuppressesAuthor(
                timelineSuppressesAuthor = true,
                selectionActive = true,
                selected = false,
                previousSelected = false,
            )
        )
        // A row that opens a run always shows its author.
        assertFalse(
            threadShareSelectionSuppressesAuthor(
                timelineSuppressesAuthor = false,
                selectionActive = true,
                selected = true,
                previousSelected = true,
            )
        )
    }

    @Test
    fun theImageRegroupsTheSelectedRowsOnly() {
        // One run in the timeline: Artin m1..m4 within minutes; only m1 shows its author there.
        val messages =
            listOf("01:00", "01:01", "01:02", "01:03").mapIndexed { index, time ->
                RaftThreadMessageItem(
                    id = "m${index + 1}",
                    authorId = "u-artin",
                    authorName = "Artin",
                    timestampLabel = "2026-06-27T$time:00Z",
                    body = "Message ${index + 1}",
                )
            }

        assertEquals(listOf("m1"), authoredRows(messages, selected = emptySet(), selectionActive = false))
        // artin's case: one continuation row picked alone; the image's only message has its author.
        assertEquals(listOf("m3"), authoredRows(messages, selected = setOf("m3"), selectionActive = true).filter { it == "m3" })
        // Adjacent picks group as the timeline does: m3 shows the author, m4 continues it.
        assertEquals(
            listOf("m3"),
            authoredRows(messages, selected = setOf("m3", "m4"), selectionActive = true).filter { it in setOf("m3", "m4") },
        )
        // Picks with an unselected message between them never group: both show the author.
        assertEquals(
            listOf("m2", "m4"),
            authoredRows(messages, selected = setOf("m2", "m4"), selectionActive = true).filter { it in setOf("m2", "m4") },
        )
        // A pick that opens the run keeps its author and its selected follower stays compact.
        assertEquals(
            listOf("m1"),
            authoredRows(messages, selected = setOf("m1", "m2"), selectionActive = true).filter { it in setOf("m1", "m2") },
        )
    }

    /** The ids of the rows that show their author header, as the thread list decides it. */
    private fun authoredRows(
        messages: List<RaftThreadMessageItem>,
        selected: Set<String>,
        selectionActive: Boolean,
    ): List<String> =
        messages.filterIndexed { index, message ->
            val previous = messages.getOrNull(index - 1)
            !threadShareSelectionSuppressesAuthor(
                timelineSuppressesAuthor =
                    shouldSuppressCompactThreadAuthor(previous = previous, next = message, localOffsetMinutes = 0),
                selectionActive = selectionActive,
                selected = message.id in selected,
                previousSelected = previous != null && previous.id in selected,
            )
        }.map { it.id }
}
