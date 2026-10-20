package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftTextFieldMultiTapSelectionTest {
    private fun counter() = RaftTextFieldTapCounter(doubleTapTimeoutMillis = 300L, slopPx = 8f)

    @Test
    fun consecutiveDownsWithinTimeoutAndSlopCountUp() {
        val counter = counter()
        assertEquals(1, counter.onDown(1_000L, Offset(10f, 10f)))
        assertEquals(2, counter.onDown(1_200L, Offset(14f, 12f)))
        assertEquals(3, counter.onDown(1_450L, Offset(12f, 9f)))
        assertEquals(4, counter.onDown(1_600L, Offset(12f, 9f)))
    }

    @Test
    fun aLateDownStartsANewSequence() {
        val counter = counter()
        counter.onDown(1_000L, Offset.Zero)
        assertEquals(1, counter.onDown(1_300L, Offset.Zero))
        assertEquals(2, counter.onDown(1_500L, Offset.Zero))
    }

    @Test
    fun aDistantDownStartsANewSequence() {
        val counter = counter()
        counter.onDown(1_000L, Offset(10f, 10f))
        assertEquals(1, counter.onDown(1_100L, Offset(10f, 30f)))
        // The sequence continues from the latest down, like upstream ClicksCounter.
        assertEquals(2, counter.onDown(1_200L, Offset(12f, 30f)))
    }

    @Test
    fun resetEndsTheSequence() {
        val counter = counter()
        counter.onDown(1_000L, Offset.Zero)
        counter.reset()
        assertEquals(1, counter.onDown(1_100L, Offset.Zero))
    }

    private val words: (String) -> (Int) -> TextRange = { text ->
        { offset ->
            var start = offset
            while (start > 0 && text[start - 1].isLetterOrDigit()) start--
            var end = offset
            while (end < text.length && text[end].isLetterOrDigit()) end++
            TextRange(start, end)
        }
    }

    @Test
    fun singleTapSelectsNothing() {
        val text = "hello world"
        assertNull(raftTextFieldMultiTapSelectionRange(text, 2, 1, words(text)))
    }

    @Test
    fun doubleTapSelectsTheWordAtTheCursor() {
        val text = "hello world"
        assertEquals(TextRange(6, 11), raftTextFieldMultiTapSelectionRange(text, 8, 2, words(text)))
        assertEquals(TextRange(0, 5), raftTextFieldMultiTapSelectionRange(text, 5, 2, words(text)))
    }

    @Test
    fun doubleTapOnNothingSelectableKeepsTheCursor() {
        val text = "a  b"
        assertNull(raftTextFieldMultiTapSelectionRange(text, 2, 2, words(text)))
        assertNull(raftTextFieldMultiTapSelectionRange("", 0, 2, words("")))
    }

    @Test
    fun tripleTapSelectsTheWholeSingleParagraph() {
        val text = "hello world, this is one line"
        assertEquals(
            TextRange(0, text.length),
            raftTextFieldMultiTapSelectionRange(text, 8, 3, words(text)),
        )
        assertEquals(
            TextRange(0, text.length),
            raftTextFieldMultiTapSelectionRange(text, text.length, 4, words(text)),
        )
    }

    @Test
    fun tripleTapSelectsTheParagraphBetweenLineFeeds() {
        val text = "first\nsecond line\nthird"
        assertEquals(TextRange(6, 17), raftTextFieldMultiTapSelectionRange(text, 9, 3, words(text)))
        assertEquals(TextRange(0, 5), raftTextFieldMultiTapSelectionRange(text, 0, 3, words(text)))
        assertEquals(TextRange(18, 23), raftTextFieldMultiTapSelectionRange(text, 23, 3, words(text)))
        // A cursor right before a line feed belongs to the paragraph it ends.
        assertEquals(TextRange(0, 5), raftTextFieldParagraphRange(text, 5))
    }

    @Test
    fun tripleTapOnAnEmptyLineSelectsNothing() {
        val text = "a\n\nb"
        assertNull(raftTextFieldMultiTapSelectionRange(text, 2, 3, words(text)))
    }

    @Test
    fun toolbarPasteAndCutReplaceTheSelectionAndPlaceTheCursorAfterIt() {
        val selectedFoo = TextFieldValue("hello foo bar", TextRange(6, 9))
        assertEquals(
            TextFieldValue("hello baz bar", TextRange(9)),
            raftReplaceSelection(selectedFoo, "baz"),
        )
        assertEquals(TextFieldValue("hello  bar", TextRange(6)), raftReplaceSelection(selectedFoo, ""))
        // A reversed selection is replaced the same way.
        assertEquals(
            TextFieldValue("hello baz bar", TextRange(9)),
            raftReplaceSelection(selectedFoo.copy(selection = TextRange(9, 6)), "baz"),
        )
    }
}
