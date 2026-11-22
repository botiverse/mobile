package build.raft.designsystem

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.designsystem.components.RaftTextFieldSync
import build.raft.designsystem.components.RaftTextFieldSync.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The controlled-value bridge behind every Raft text field (task #69). The editor state here is a
 * plain variable standing in for the TextFieldState, so IME compositions can be set directly.
 */
class RaftTextFieldSyncTest {
    private class Harness(initial: TextFieldValue, compareSelection: Boolean) {
        var editor: TextFieldValue = initial
        val writes = mutableListOf<TextFieldValue>()
        val reports = mutableListOf<TextFieldValue>()
        val sync =
            RaftTextFieldSync(
                initial = initial,
                compareSelection = compareSelection,
                read = { editor },
                // A programmatic edit ends the IME composition, as TextFieldState.edit does.
                write = { next ->
                    writes += next
                    editor = next.copy(composition = null)
                },
            )

        fun reconcile(owner: TextFieldValue): Outcome = sync.reconcile(owner, onUserEdit = { reports += it })
    }

    private fun composing(text: String, start: Int = 0) =
        TextFieldValue(text, selection = TextRange(text.length), composition = TextRange(start, text.length))

    private fun committed(text: String) = TextFieldValue(text, selection = TextRange(text.length))

    @Test
    fun pinyinCompositionIsReportedAndNeverWrittenBack() {
        val h = Harness(committed(""), compareSelection = true)
        var owner = committed("")
        // "k", "kr", "k r yi" while the candidate bar is up; the owner accepts each report.
        listOf("k", "kr", "k r yi").forEach { marked ->
            h.editor = composing(marked)
            assertEquals(Outcome.Reported, h.reconcile(owner))
            owner = h.reports.last()
            assertEquals(Outcome.Idle, h.reconcile(owner))
        }
        // The user picks the candidate: the IME replaces the marked text and ends the composition.
        h.editor = committed("可以")
        assertEquals(Outcome.Reported, h.reconcile(owner))
        owner = h.reports.last()
        assertEquals(Outcome.Idle, h.reconcile(owner))

        assertEquals(listOf("k", "kr", "k r yi", "可以"), h.reports.map { it.text })
        assertEquals(TextRange(0, 6), h.reports[2].composition)
        assertEquals(emptyList(), h.writes)
    }

    @Test
    fun anOwnerThatHasNotAnsweredYetDoesNotResetTheComposition() {
        val h = Harness(committed(""), compareSelection = false)
        h.editor = composing("k")
        assertEquals(Outcome.Reported, h.reconcile(committed("")))
        // The owner (a store updated through a flow) still shows "" on the next frames.
        repeat(3) { assertEquals(Outcome.Deferred, h.reconcile(committed(""))) }
        // It catches up.
        assertEquals(Outcome.Idle, h.reconcile(committed("k")))
        assertEquals(emptyList(), h.writes)
    }

    @Test
    fun aNewOwnerValueIsPushedEvenWhileComposing() {
        val h = Harness(committed("hello"), compareSelection = true)
        h.editor = TextFieldValue("hello wor", TextRange(9), composition = TextRange(6, 9))
        h.reconcile(committed("hello"))
        val reported = h.reports.single()
        assertEquals(Outcome.Idle, h.reconcile(reported))

        // Send clears the composer while Gboard still composes "wor".
        assertEquals(Outcome.Pushed, h.reconcile(committed("")))
        assertEquals(committed(""), h.editor)
        assertEquals(1, h.reports.size)
    }

    @Test
    fun aRejectedEditIsUndoneOnceTheOwnerHadTimeToAnswer() {
        val h = Harness(committed("abc"), compareSelection = false)
        // A three-character cap: the owner keeps "abc".
        h.editor = committed("abcd")
        assertEquals(Outcome.Reported, h.reconcile(committed("abc")))
        assertEquals(Outcome.Recheck, h.reconcile(committed("abc")))
        assertEquals(Outcome.Pushed, h.reconcile(committed("abc")))
        assertEquals("abc", h.editor.text)
        assertEquals(listOf("abcd"), h.reports.map { it.text })
    }

    @Test
    fun handleDragSelectionChangesReachTheOwnerAndAreNeverReverted() {
        // A double tap selects "beta", then the end handle extends it, the start handle extends it
        // further, and the end handle shrinks it back; each frame the owner answers with the value.
        val text = "alpha beta gamma"
        val h = Harness(TextFieldValue(text, TextRange(text.length)), compareSelection = true)
        var owner = h.editor
        listOf(TextRange(6, 10), TextRange(6, 13), TextRange(6, 16), TextRange(0, 16), TextRange(0, 8)).forEach { range ->
            h.editor = TextFieldValue(text, range)
            assertEquals(Outcome.Reported, h.reconcile(owner), "selection $range")
            owner = h.reports.last()
            assertEquals(Outcome.Idle, h.reconcile(owner), "selection $range")
        }
        assertEquals(listOf(TextRange(6, 10), TextRange(6, 13), TextRange(6, 16), TextRange(0, 16), TextRange(0, 8)), h.reports.map { it.selection })
        assertEquals(emptyList(), h.writes)
        assertEquals(TextRange(0, 8), h.editor.selection)
    }

    @Test
    fun aMentionInsertMovesTheCaretWithTheText() {
        val h = Harness(committed("hi @al"), compareSelection = true)
        val inserted = TextFieldValue("hi @alice ", TextRange(10))
        assertEquals(Outcome.Pushed, h.reconcile(inserted))
        assertEquals(inserted, h.editor)
    }
}
