package build.raft.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TextFieldValueLengthTest {
    @Test
    fun oversizeActiveCjkCompositionIsNotMutated() {
        val composing =
            TextFieldValue(
                text = "拼音输入中",
                selection = TextRange(5),
                composition = TextRange(0, 5)
            )

        val result = composing.coerceTextLength(3)

        assertSame(composing, result)
        assertEquals(TextRange(5), result.selection)
        assertEquals(TextRange(0, 5), result.composition)
    }

    @Test
    fun committedOversizeClampsMidTextAndReversedSelections() {
        val midText =
            TextFieldValue(
                text = "abcdef",
                selection = TextRange(1, 3)
            )
        val reversed =
            TextFieldValue(
                text = "abcdef",
                selection = TextRange(6, 2)
            )

        val midTextResult = midText.coerceTextLength(4)
        val reversedResult = reversed.coerceTextLength(4)

        assertNotSame(midText, midTextResult)
        assertEquals("abcd", midTextResult.text)
        assertEquals(TextRange(1, 3), midTextResult.selection)
        assertNull(midTextResult.composition)
        assertEquals("abcd", reversedResult.text)
        assertEquals(TextRange(4, 2), reversedResult.selection)
        assertTrue(reversedResult.selection.reversed)
        assertNull(reversedResult.composition)
    }
}
