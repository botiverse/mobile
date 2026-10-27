package build.raft.app

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadComposerPasteShortcutTest {
    private fun shortcut(
        keyDown: Boolean = true,
        key: Key = Key.V,
        meta: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ) = threadComposerIsPasteShortcut(keyDown, key, meta, ctrl, shift, alt)

    @Test
    fun cmdOrCtrlVOnKeyDownIsThePasteShortcut() {
        assertTrue(shortcut(meta = true))
        assertTrue(shortcut(ctrl = true))
    }

    @Test
    fun otherKeysModifiersOrKeyUpAreNot() {
        assertFalse(shortcut())
        assertFalse(shortcut(meta = true, keyDown = false))
        assertFalse(shortcut(meta = true, key = Key.C))
        assertFalse(shortcut(meta = true, shift = true))
        assertFalse(shortcut(meta = true, alt = true))
    }
}
