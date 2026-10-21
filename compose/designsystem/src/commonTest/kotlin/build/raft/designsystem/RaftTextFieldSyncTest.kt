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
    fun aCompositionOnlyDifferenceFromTheOwnerIsLeftToTheIme() {
        val h = Harness(committed(""), compareSelection = true)
        h.editor = composing("ni")
        h.reconcile(committed(""))
        // The composer writes the Send snapshot back with the composition finished.
        assertEquals(Outcome.Idle, h.reconcile(committed("ni")))
        assertEquals(emptyList(), h.writes)
        assertEquals(TextRange(0, 2), h.editor.composition)
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
    fun aRejectionDuringCompositionWaitsForTheCommit() {
        val h = Harness(committed("abc"), compareSelection = false)
        h.editor = TextFieldValue("abck", TextRange(4), composition = TextRange(3, 4))
        h.reconcile(committed("abc"))
        assertEquals(Outcome.Deferred, h.reconcile(committed("abc")))
        assertEquals(emptyList(), h.writes)

        h.editor = committed("abc可")
        assertEquals(Outcome.Reported, h.reconcile(committed("abc")))
        assertEquals(Outcome.Recheck, h.reconcile(committed("abc")))
        assertEquals(Outcome.Pushed, h.reconcile(committed("abc")))
        assertEquals("abc", h.editor.text)
    }

    @Test
    fun ownEditsAreNotEchoedAndPushesAreNotReported() {
        val h = Harness(committed(""), compareSelection = true)
        // Draft restore with the caret after "Hi".
        val restored = TextFieldValue("Hi there", TextRange(2))
        assertEquals(Outcome.Pushed, h.reconcile(restored))
        assertEquals(restored, h.editor)
        assertEquals(Outcome.Idle, h.reconcile(restored))
        assertEquals(emptyList(), h.reports)
    }

    @Test
    fun aMentionInsertMovesTheCaretWithTheText() {
        val h = Harness(committed("hi @al"), compareSelection = true)
        val inserted = TextFieldValue("hi @alice ", TextRange(10))
        assertEquals(Outcome.Pushed, h.reconcile(inserted))
        assertEquals(inserted, h.editor)
    }

    @Test
    fun aStringOwnerIsNotToldAboutSelectionMoves() {
        val h = Harness(committed("hello"), compareSelection = false)
        h.editor = TextFieldValue("hello", TextRange(1, 3))
        assertEquals(Outcome.Idle, h.reconcile(committed("hello")))
        assertEquals(emptyList(), h.reports)
        // An outside text change keeps the user's selection (coerced by the editor).
        assertEquals(Outcome.Pushed, h.reconcile(committed("help!")))
        assertEquals(TextFieldValue("help!", TextRange(1, 3)), h.editor)
    }
}
