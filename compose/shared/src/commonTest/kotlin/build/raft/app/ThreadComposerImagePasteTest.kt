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

    private fun withPasteLog(block: (MutableList<String>) -> Unit) {
        val lines = mutableListOf<String>()
        val previous = ThreadComposerImagePasteLog.sink
        ThreadComposerImagePasteLog.sink = { lines += it }
        try {
            block(lines)
        } finally {
            ThreadComposerImagePasteLog.sink = previous
        }
    }

    @Test
    fun onIosTheKeyHandlerLeavesCmdVWithImagesToTheSystemPasteAction() = withPasteLog { lines ->
        var pasted = 0
        val consumed =
            threadComposerHandlePasteShortcut(
                shortcut = true,
                clipboardHasAttachments = { true },
                // iOS: raftPlatformComposerKeyHandlerPastesAttachments() is false.
                keyHandlerPastesAttachments = false,
                onPaste = { pasted++ },
            )
        // The key passes through to UIKit, which sends the editor paste: (the host's hook).
        assertFalse(consumed)
        assertEquals(0, pasted)
        // No claim: the system paste action's claim for this press is not denied.
        assertEquals(listOf("composer_paste_key route=key_handler decision=leave_to_system_paste"), lines)
        val later = TimeSource.Monotonic.markNow() + 100_000.milliseconds
        assertTrue(ThreadComposerImagePasteRequests.claim(ThreadComposerImagePasteRoute.SystemPaste, later))
    }

    @Test
    fun onDesktopTheKeyHandlerStillAttachesOnCmdV() = withPasteLog { lines ->
        var pasted = 0
        val consumed =
            threadComposerHandlePasteShortcut(
                shortcut = true,
                clipboardHasAttachments = { true },
                keyHandlerPastesAttachments = true,
                onPaste = { pasted++ },
            )
        assertTrue(consumed)
        assertEquals(1, pasted)
        assertEquals("composer_paste_key route=key_handler decision=paste_attachments", lines.first())
        assertTrue(lines[1].startsWith("composer_image_paste_claim route=key_handler granted=true"), lines[1])
    }

    @Test
    fun hostPastedFilesAreTakenOnce() {
        val composer = Any()
        val file = PlatformFile(id = "a", fileName = "Pasted image.jpg", mimeType = "image/jpeg")
        ThreadComposerImagePasteRequests.editorFocusChanged(composer, true)
        try {
            ThreadComposerImagePasteRequests.deliver(listOf(file))

            assertEquals(listOf(file), ThreadComposerImagePasteRequests.takePending())
            assertNull(ThreadComposerImagePasteRequests.takePending())
        } finally {
            ThreadComposerImagePasteRequests.editorFocusChanged(composer, false)
        }
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
