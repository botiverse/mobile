package build.raft.app.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive

class RaftDesktopHistoryInputTest {
    private fun shortcut(
        key: Key,
        meta: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        mac: Boolean,
        keyDown: Boolean = true,
    ) = raftDesktopHistoryShortcut(keyDown, key, meta, ctrl, alt, shift, mac)

    @Test fun `Cmd brackets step through the history on macOS`() {
        assertEquals(RaftDesktopHistoryDirection.Back, shortcut(Key.LeftBracket, meta = true, mac = true))
        assertEquals(RaftDesktopHistoryDirection.Forward, shortcut(Key.RightBracket, meta = true, mac = true))
        // Ctrl brackets, Option arrows (move by word) and Cmd arrows (line start and end) stay the text field's.
        assertNull(shortcut(Key.LeftBracket, ctrl = true, mac = true))
        assertNull(shortcut(Key.DirectionLeft, alt = true, mac = true))
        assertNull(shortcut(Key.DirectionLeft, meta = true, mac = true))
    }

    @Test fun `Ctrl brackets and Alt arrows step through the history off macOS`() {
        assertEquals(RaftDesktopHistoryDirection.Back, shortcut(Key.LeftBracket, ctrl = true, mac = false))
        assertEquals(RaftDesktopHistoryDirection.Forward, shortcut(Key.RightBracket, ctrl = true, mac = false))
        assertEquals(RaftDesktopHistoryDirection.Back, shortcut(Key.DirectionLeft, alt = true, mac = false))
        assertEquals(RaftDesktopHistoryDirection.Forward, shortcut(Key.DirectionRight, alt = true, mac = false))
        assertNull(shortcut(Key.LeftBracket, meta = true, mac = false))
        assertNull(shortcut(Key.DirectionLeft, ctrl = true, mac = false))
    }

    @Test fun `extra modifiers key ups and other keys map to nothing`() {
        assertNull(shortcut(Key.LeftBracket, meta = true, shift = true, mac = true))
        assertNull(shortcut(Key.LeftBracket, meta = true, alt = true, mac = true))
        assertNull(shortcut(Key.LeftBracket, meta = true, ctrl = true, mac = true))
        assertNull(shortcut(Key.DirectionLeft, alt = true, ctrl = true, mac = false))
        assertNull(shortcut(Key.LeftBracket, meta = true, mac = true, keyDown = false))
        assertNull(shortcut(Key.LeftBracket, mac = true))
        assertNull(shortcut(Key.A, meta = true, mac = true))
        assertNull(shortcut(Key.Escape, mac = false))
    }

    @Test fun `the mouse side buttons are back and forward`() {
        assertEquals(RaftDesktopHistoryDirection.Back, raftDesktopHistoryMouseButton(PointerButton.Back))
        assertEquals(RaftDesktopHistoryDirection.Forward, raftDesktopHistoryMouseButton(PointerButton.Forward))
        assertNull(raftDesktopHistoryMouseButton(PointerButton.Primary))
        assertNull(raftDesktopHistoryMouseButton(PointerButton.Secondary))
        assertNull(raftDesktopHistoryMouseButton(null))
    }

    @Test fun `the shell alone takes every step and a page over it takes Back as closing`() {
        assertEquals(RaftDesktopHistoryRoute.Shell, raftDesktopHistoryRoute(1, topIsShell = true, RaftDesktopHistoryDirection.Back))
        assertEquals(RaftDesktopHistoryRoute.Shell, raftDesktopHistoryRoute(1, topIsShell = true, RaftDesktopHistoryDirection.Forward))
        assertEquals(RaftDesktopHistoryRoute.ClosePage, raftDesktopHistoryRoute(2, topIsShell = false, RaftDesktopHistoryDirection.Back))
        assertEquals(RaftDesktopHistoryRoute.None, raftDesktopHistoryRoute(2, topIsShell = false, RaftDesktopHistoryDirection.Forward))
        assertEquals(RaftDesktopHistoryRoute.None, raftDesktopHistoryRoute(1, topIsShell = false, RaftDesktopHistoryDirection.Back))
        assertEquals(RaftDesktopHistoryRoute.None, raftDesktopHistoryRoute(0, topIsShell = false, RaftDesktopHistoryDirection.Back))
    }

    @Test fun `the step reaches the shell as its pager event`() {
        assertEquals("slockWideHistoryNavigate", RaftDesktopHistoryInput.EVENT_NAME)
        assertEquals(JsonPrimitive("back"), RaftDesktopHistoryInput.eventData(RaftDesktopHistoryDirection.Back)["direction"])
        assertEquals(JsonPrimitive("forward"), RaftDesktopHistoryInput.eventData(RaftDesktopHistoryDirection.Forward)["direction"])
    }
}
