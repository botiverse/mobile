package build.raft.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.data.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class ThreadComposerImagePasteTest {
    private val placeholder = ThreadComposerImagePastePlaceholder

    @Test
    fun placeholderPastedIntoAnEmptyComposerIsAnImagePaste() {
        val before = TextFieldValue("", TextRange(0))
        val pasted = TextFieldValue(placeholder, TextRange(placeholder.length))

        assertTrue(threadComposerIsImagePastePlaceholderEdit(before, pasted))
    }

    @Test
    fun placeholderPastedAtTheCaretOrOverTheSelectionIsAnImagePaste() {
        val atCaret = TextFieldValue("see here", TextRange(4))
        assertTrue(
            threadComposerIsImagePastePlaceholderEdit(
                atCaret,
                TextFieldValue("see ${placeholder}here", TextRange(4 + placeholder.length)),
            )
        )
        val overSelection = TextFieldValue("see this here", TextRange(4, 9))
        assertTrue(
            threadComposerIsImagePastePlaceholderEdit(
                overSelection,
                TextFieldValue("see $placeholder here", TextRange(4 + placeholder.length)),
            )
        )
    }

    @Test
    fun theUsersOwnTextIsNeverTakenForAnImagePaste() {
        // Typing the last character of the word, or text that already holds it, is an ordinary edit.
        val typed = placeholder.dropLast(1)
        assertFalse(
            threadComposerIsImagePastePlaceholderEdit(
                TextFieldValue(typed, TextRange(typed.length)),
                TextFieldValue(placeholder, TextRange(placeholder.length)),
            )
        )
        assertFalse(
            threadComposerIsImagePastePlaceholderEdit(
                TextFieldValue("$placeholder ", TextRange(placeholder.length + 1)),
                TextFieldValue("$placeholder x", TextRange(placeholder.length + 2)),
            )
        )
        // Placeholder inserted somewhere other than the selection.
        assertFalse(
            threadComposerIsImagePastePlaceholderEdit(
                TextFieldValue("ab", TextRange(2)),
                TextFieldValue("${placeholder}ab", TextRange(placeholder.length)),
            )
        )
    }

    @Test
    fun onePastePerPress() {
        val start = TimeSource.Monotonic.markNow()
        assertTrue(ThreadComposerImagePasteRequests.claim(start + 10_000.milliseconds))
        assertFalse(ThreadComposerImagePasteRequests.claim(start + 10_100.milliseconds))
        assertTrue(ThreadComposerImagePasteRequests.claim(start + 11_000.milliseconds))
    }

    @Test
    fun hostPastedFilesAreTakenOnce() {
        val file = PlatformFile(id = "a", fileName = "Pasted image.jpg", mimeType = "image/jpeg")
        ThreadComposerImagePasteRequests.deliver(listOf(file))

        assertEquals(listOf(file), ThreadComposerImagePasteRequests.takePending())
        assertNull(ThreadComposerImagePasteRequests.takePending())
    }

    @Test
    fun onlyTheFocusedComposerOwnsThePaste() {
        val a = Any()
        val b = Any()
        val seen = mutableListOf<Boolean>()
        ThreadComposerImagePasteRequests.focusListener = { seen += it }
        try {
            ThreadComposerImagePasteRequests.editorFocusChanged(a, true)
            ThreadComposerImagePasteRequests.editorFocusChanged(b, true)
            // A's blur arrives after B took focus: B keeps it.
            ThreadComposerImagePasteRequests.editorFocusChanged(a, false)
            assertTrue(ThreadComposerImagePasteRequests.focusedOwner === b)
            ThreadComposerImagePasteRequests.editorFocusChanged(b, false)
            assertNull(ThreadComposerImagePasteRequests.focusedOwner)
            assertEquals(listOf(true, true, false), seen)
        } finally {
            ThreadComposerImagePasteRequests.focusListener = null
        }
    }
}
