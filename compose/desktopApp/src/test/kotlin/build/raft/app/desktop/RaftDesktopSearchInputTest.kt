package build.raft.app.desktop

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftDesktopSearchInputTest {
    private fun shortcut(
        key: Key = Key.K,
        meta: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        mac: Boolean,
        keyDown: Boolean = true,
    ) = raftDesktopSearchShortcut(keyDown, key, meta, ctrl, alt, shift, mac)

    @Test fun `Cmd K opens search on macOS and Ctrl K elsewhere`() {
        assertTrue(shortcut(meta = true, mac = true))
        assertFalse(shortcut(ctrl = true, mac = true), "Ctrl+K is the text field's on macOS")
        assertTrue(shortcut(ctrl = true, mac = false))
        assertFalse(shortcut(meta = true, mac = false))
    }
}
