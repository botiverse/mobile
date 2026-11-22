package build.raft.app.desktop

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test fun `the shell alone takes every step and a page over it takes Back as closing`() {
        assertEquals(RaftDesktopHistoryRoute.Shell, raftDesktopHistoryRoute(1, topIsShell = true, RaftDesktopHistoryDirection.Back))
        assertEquals(RaftDesktopHistoryRoute.Shell, raftDesktopHistoryRoute(1, topIsShell = true, RaftDesktopHistoryDirection.Forward))
        assertEquals(RaftDesktopHistoryRoute.ClosePage, raftDesktopHistoryRoute(2, topIsShell = false, RaftDesktopHistoryDirection.Back))
        assertEquals(RaftDesktopHistoryRoute.None, raftDesktopHistoryRoute(2, topIsShell = false, RaftDesktopHistoryDirection.Forward))
        assertEquals(RaftDesktopHistoryRoute.None, raftDesktopHistoryRoute(1, topIsShell = false, RaftDesktopHistoryDirection.Back))
        assertEquals(RaftDesktopHistoryRoute.None, raftDesktopHistoryRoute(0, topIsShell = false, RaftDesktopHistoryDirection.Back))
    }
}
