package build.raft.app

import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The section dialog's emoji picker (web `SidebarSectionEmojiPicker`): pick sets the icon, Remove clears it. */
class RaftHomeSectionDialogStateTest {
    @Test
    fun pickingAnEmojiSetsTheSectionIconAndClosesThePicker() {
        val state = RaftHomeSectionDialogState(initialName = "Design", initialEmoji = "")
        state.pickerOpen = true

        state.pickEmoji("🚀")

        assertEquals("🚀", state.emoji)
        assertFalse(state.pickerOpen)
        assertEquals("Design" to "🚀", state.submission())
    }

    @Test
    fun pickingReplacesTheCurrentEmoji() {
        val state = RaftHomeSectionDialogState(initialName = "Design", initialEmoji = "🎨")

        state.pickEmoji("👍🏽")

        assertEquals("Design" to "👍🏽", state.submission())
    }

    @Test
    fun removingTheEmojiSavesNoIcon() {
        val state = RaftHomeSectionDialogState(initialName = "Design", initialEmoji = "🎨")
        state.pickerOpen = true

        state.removeEmoji()

        assertEquals("", state.emoji)
        assertFalse(state.pickerOpen)
        assertEquals("Design" to null, state.submission())
    }

    @Test
    fun theNameIsTrimmedCappedAtEightyAndRequired() {
        val state = RaftHomeSectionDialogState(initialName = "", initialEmoji = "🎨")
        assertNull(state.submission())

        state.updateName(TextFieldValue("   "))
        assertNull(state.submission())

        state.updateName(TextFieldValue("  Design  "))
        assertEquals("Design" to "🎨", state.submission())

        state.updateName(TextFieldValue("x".repeat(100)))
        assertEquals(80, state.name.text.length)
    }
}
