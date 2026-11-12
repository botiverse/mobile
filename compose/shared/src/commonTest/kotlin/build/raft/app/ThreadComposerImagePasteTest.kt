package build.raft.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ThreadComposerImagePasteTest {
    private val placeholder = ThreadComposerImagePastePlaceholder

    @Test
    fun imageOnlyPasteIntoAnEmptyComposerTypesNothingAndAttaches() {
        val pasted = TextFieldValue(placeholder, TextRange(placeholder.length))

        val kept = threadComposerTakeImagePaste(pasted)

        // Non-null: the composer attaches the clipboard's images; the text stays empty.
        assertEquals(TextFieldValue("", TextRange(0)), kept)
    }

    @Test
    fun imageOnlyPasteKeepsTheTextAroundItAndTheCaretWhereItWas() {
        val pasted = TextFieldValue("see ${placeholder}here", TextRange(4 + placeholder.length))

        val kept = threadComposerTakeImagePaste(pasted)

        assertEquals(TextFieldValue("see here", TextRange(4)), kept)
        assertFalse(kept!!.text.contains(placeholder))
    }

    @Test
    fun ordinaryTextIsNotAnImagePaste() {
        assertNull(threadComposerTakeImagePaste(TextFieldValue("raft paste image", TextRange(16))))
        assertNull(threadComposerTakeImagePaste(TextFieldValue("", TextRange(0))))
    }
}
