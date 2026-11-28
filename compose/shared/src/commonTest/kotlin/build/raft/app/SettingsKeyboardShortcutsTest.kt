package build.raft.app

import androidx.compose.ui.input.key.Key
import build.raft.app.resources.*
import build.raft.app.settings.raftKeyboardShortcutsHelpShortcut
import build.raft.app.settings.raftKeyboardShortcutsHelpShortcutLabel
import build.raft.app.settings.settingsKeyboardShortcutGroups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Appearance › Keyboard shortcuts & Rail Help menu: only where a hardware keyboard is plausible,
 * and only the shortcuts the app handles (web `features/shortcuts/registry.ts` rows `search`,
 * `sidebar`, `keyboard-shortcuts`, `previous-match`), with ⌘ on Apple platforms and Ctrl elsewhere.
 */
class SettingsKeyboardShortcutsTest {
    @Test
    fun listsTheAppsOwnShortcutsInTheWebsOrder() {
        val apple = settingsKeyboardShortcutGroups(apple = true)
        assertEquals(
            listOf(Res.string.settings_keyboard_shortcuts_group_global, Res.string.settings_keyboard_shortcuts_group_channels_threads),
            apple.map { it.title }
        )
        assertEquals(listOf("search", "sidebar", "keyboard-shortcuts", "previous-match"), apple.flatMap { group -> group.shortcuts.map { it.id } })
        assertEquals(
            listOf(listOf("⌘", "K"), listOf("⌘", "\\"), listOf("⌘", "/"), listOf("⇧", "Enter")),
            apple.flatMap { group -> group.shortcuts.map { it.keys } },
        )
        val other = settingsKeyboardShortcutGroups(apple = false)
        assertEquals(
            listOf(listOf("Ctrl", "K"), listOf("Ctrl", "\\"), listOf("Ctrl", "/"), listOf("Shift", "Enter")),
            other.flatMap { group -> group.shortcuts.map { it.keys } },
        )
    }

    @Test
    fun keyboardShortcutsHelpShortcutMatchesPlatformModifier() {
        assertEquals("⌘/", raftKeyboardShortcutsHelpShortcutLabel(apple = true))
        assertEquals("Ctrl+/", raftKeyboardShortcutsHelpShortcutLabel(apple = false))
        assertTrue(raftKeyboardShortcutsHelpShortcut(keyDown = true, key = Key.Slash, meta = true, ctrl = false, alt = false, shift = false, apple = true))
        assertFalse(raftKeyboardShortcutsHelpShortcut(keyDown = true, key = Key.Slash, meta = false, ctrl = true, alt = false, shift = false, apple = true))
        assertTrue(raftKeyboardShortcutsHelpShortcut(keyDown = true, key = Key.Slash, meta = false, ctrl = true, alt = false, shift = false, apple = false))
        assertFalse(raftKeyboardShortcutsHelpShortcut(keyDown = false, key = Key.Slash, meta = false, ctrl = true, alt = false, shift = false, apple = false))
        assertFalse(raftKeyboardShortcutsHelpShortcut(keyDown = true, key = Key.Slash, meta = false, ctrl = true, alt = false, shift = true, apple = false))
    }
}
