package build.raft.app

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #338 (Kirby 11-24): the wide shell gives the room above the keyboard to typing. */
class RaftWideKeyboardChromeTest {
    @Test
    fun theTopBarStepsAsideOnlyOnTheWideShellWithTheKeyboardUp() {
        assertTrue(raftAppShellTopBarShown(hasTopBar = true, keyboardUp = false))
        assertFalse(raftAppShellTopBarShown(hasTopBar = true, keyboardUp = true))
        assertFalse(raftAppShellTopBarShown(hasTopBar = false, keyboardUp = false))
    }

    @Test
    fun theRoomIsTheWindowLessTheStatusBarAndTheKeyboard() {
        assertEquals(137.dp, raftWideKeyboardRoom(windowHeight = 411.dp, topSafeArea = 24.dp, keyboard = 250.dp))
        assertNull(raftWideKeyboardRoom(windowHeight = 411.dp, topSafeArea = 24.dp, keyboard = 0.dp))
        assertNull(raftWideKeyboardRoom(windowHeight = 0.dp, topSafeArea = 0.dp, keyboard = 250.dp))
        // An iPad's shortcut bar over a hardware keyboard is no keyboard here.
        assertNull(raftWideKeyboardRoom(windowHeight = 744.dp, topSafeArea = 24.dp, keyboard = 55.dp))
        assertEquals(0.dp, raftWideKeyboardRoom(windowHeight = 300.dp, topSafeArea = 24.dp, keyboard = 300.dp))
    }

    @Test
    fun theHeadStaysWhileItTheComposerAndAFewRowsFit() {
        // A tablet held sideways: 1024 x 768, a 320 keyboard leave 424.
        assertFalse(raftWideKeyboardHidesHead(424.dp, headHeight = 56.dp))
        // Exactly the head, the composer's 160 and 96 of messages.
        assertFalse(raftWideKeyboardHidesHead(304.dp, headHeight = 48.dp))
        // 960 x 600 with a 300 keyboard: 276 left, the head steps aside.
        assertTrue(raftWideKeyboardHidesHead(276.dp, headHeight = 48.dp))
        // 914 x 411: 137 left, the head is aside and the composer keeps its height (#5630).
        assertTrue(raftWideKeyboardHidesHead(137.dp, headHeight = 48.dp))
        // Keyboard down or not the wide shell: it stays.
        assertFalse(raftWideKeyboardHidesHead(null, headHeight = 48.dp))
    }

    @Test
    fun onlyTheComposerAboveTheKeyboardWhenItAndAFewRowsDontFit() {
        // 914 x 411: 137 left, the composer alone, full width while typed in (Kirby 640ad7a2).
        assertTrue(raftWideKeyboardComposerOnly(137.dp))
        // Just short of the composer's 160 and 96 of messages.
        assertTrue(raftWideKeyboardComposerOnly(255.dp))
        assertFalse(raftWideKeyboardComposerOnly(256.dp))
        // 960 x 600 with a 300 keyboard: 276 left, only the head steps aside; rail and list stay.
        assertFalse(raftWideKeyboardComposerOnly(276.dp))
        // Keyboard down or not the wide shell.
        assertFalse(raftWideKeyboardComposerOnly(null))
    }

    @Test
    fun theComposerFocusNoteKeepsTheLatestOwner() {
        val note = RaftWideKeyboardComposerFocus()
        val first = Any()
        val second = Any()
        assertFalse(note.focused)
        note.report(first, true)
        assertTrue(note.focused)
        // Focus moves to another composer: the first losing it clears nothing.
        note.report(second, true)
        note.report(first, false)
        assertTrue(note.focused)
        note.report(second, false)
        assertFalse(note.focused)
    }
}
