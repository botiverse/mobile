package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When Add Reaction opens web's floating picker panel instead of the centered dialog. */
class RaftEmojiPickerPopoverTest {
    @Test
    fun phonesKeepTheDialog() {
        assertFalse(raftEmojiPickerUsesPopover(windowWidthDp = 390f, hasAnchor = true))
        // A small tablet held portrait lays the app out as a phone, so the picker follows it.
        assertFalse(raftEmojiPickerUsesPopover(windowWidthDp = 744f, hasAnchor = true))
    }

    @Test
    fun wideWindowsFloatThePanel() {
        assertTrue(raftEmojiPickerUsesPopover(windowWidthDp = DualPaneMinimumWidthDp, hasAnchor = true))
        assertTrue(raftEmojiPickerUsesPopover(windowWidthDp = 1366f, hasAnchor = true))
    }

    @Test
    fun noPlaceToOpenAtKeepsTheDialog() {
        assertFalse(raftEmojiPickerUsesPopover(windowWidthDp = 1366f, hasAnchor = false))
    }

    @Test
    fun anUnmeasuredWindowKeepsTheDialog() {
        assertFalse(raftEmojiPickerUsesPopover(windowWidthDp = 0f, hasAnchor = true))
    }
}
