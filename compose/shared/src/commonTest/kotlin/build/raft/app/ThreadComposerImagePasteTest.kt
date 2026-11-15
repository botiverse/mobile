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
        // "this" selected (4 until 8).
        val overSelection = TextFieldValue("see this here", TextRange(4, 8))
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
    fun onePastePerPressAndTheLogSaysWhichRouteTookIt() = withPasteLog { lines ->
        val start = TimeSource.Monotonic.markNow()
        val system = ThreadComposerImagePasteRoute.SystemPaste
        val placeholder = ThreadComposerImagePasteRoute.Placeholder
        assertTrue(ThreadComposerImagePasteRequests.claim(system, start + 10_000.milliseconds))
        assertFalse(ThreadComposerImagePasteRequests.claim(placeholder, start + 10_100.milliseconds))
        assertTrue(ThreadComposerImagePasteRequests.claim(placeholder, start + 11_000.milliseconds))
        assertEquals(
            listOf(
                "composer_image_paste_claim route=system_paste granted=true",
                "composer_image_paste_claim route=placeholder granted=false previousRoute=system_paste sinceLastMs=100",
                "composer_image_paste_claim route=placeholder granted=true previousRoute=system_paste sinceLastMs=1000",
            ),
            // The first line's previous claim depends on the tests that ran before.
            listOf(lines[0].substringBefore(" previousRoute=")) + lines.drop(1),
        )
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
    fun textAndOtherKeysPassThroughTheKeyHandler() = withPasteLog { lines ->
        var asked = 0
        var pasted = 0
        for (keyHandlerPastes in listOf(true, false)) {
            assertFalse(threadComposerHandlePasteShortcut(false, { asked++; true }, keyHandlerPastes) { pasted++ })
            assertFalse(threadComposerHandlePasteShortcut(true, { false }, keyHandlerPastes) { pasted++ })
        }
        // A key that is not the paste shortcut never asks the clipboard and logs nothing.
        assertEquals(0, asked)
        assertEquals(0, pasted)
        assertEquals(List(2) { "composer_paste_key route=key_handler decision=paste_text" }, lines)
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
    fun imagesNoComposerTookAreDroppedAndTheirFilesDeleted() {
        val discarded = mutableListOf<PlatformFile>()
        ThreadComposerImagePasteRequests.discardFiles = { discarded += it }
        val composer = Any()
        val late = PlatformFile(id = "late", fileName = "Pasted image.jpg", mimeType = "image/jpeg")
        val untaken = PlatformFile(id = "untaken", fileName = "Pasted image.png", mimeType = "image/png")
        try {
            // Encoding finished after the keyboard went away: nobody is focused.
            ThreadComposerImagePasteRequests.deliver(listOf(late))
            assertNull(ThreadComposerImagePasteRequests.takePending())

            // Delivered, then the composer lost focus before taking them.
            ThreadComposerImagePasteRequests.editorFocusChanged(composer, true)
            ThreadComposerImagePasteRequests.deliver(listOf(untaken))
            ThreadComposerImagePasteRequests.editorFocusChanged(composer, false)
            assertNull(ThreadComposerImagePasteRequests.takePending())

            assertEquals(listOf(late, untaken), discarded)
        } finally {
            ThreadComposerImagePasteRequests.discardFiles = null
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
