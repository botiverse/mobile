package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Appearance › Keyboard shortcuts: only where a hardware keyboard is plausible, and only the
 * shortcuts the app handles (web `features/shortcuts/registry.ts` rows `search`, `sidebar`,
 * `previous-match`), with ⌘ on Apple platforms and Ctrl elsewhere.
 */
class SettingsKeyboardShortcutsTest {
    @Test
    fun showsWhereAHardwareKeyboardIsPlausible() {
        assertTrue(settingsKeyboardShortcutsVisible(finePointer = true, largeScreenPossible = true), "the desktop app")
        assertTrue(settingsKeyboardShortcutsVisible(finePointer = false, largeScreenPossible = true), "a tablet or an iPad")
        assertFalse(settingsKeyboardShortcutsVisible(finePointer = false, largeScreenPossible = false), "a phone")
        assertTrue(settingsAppPreferenceVisibility(finePointer = true, largeScreenPossible = true).keyboardShortcuts)
        assertFalse(settingsAppPreferenceVisibility(finePointer = false, largeScreenPossible = false).keyboardShortcuts)
    }

    @Test
    fun listsTheAppsOwnShortcutsInTheWebsOrder() {
        val apple = settingsKeyboardShortcutGroups(apple = true)
        assertEquals(
            listOf(Res.string.settings_keyboard_shortcuts_group_global, Res.string.settings_keyboard_shortcuts_group_channels_threads),
            apple.map { it.title }
        )
        assertEquals(listOf("search", "sidebar", "previous-match"), apple.flatMap { group -> group.shortcuts.map { it.id } })
        assertEquals(listOf(listOf("⌘", "K"), listOf("⌘", "\\"), listOf("⇧", "Enter")), apple.flatMap { group -> group.shortcuts.map { it.keys } })
        val other = settingsKeyboardShortcutGroups(apple = false)
        assertEquals(listOf(listOf("Ctrl", "K"), listOf("Ctrl", "\\"), listOf("Shift", "Enter")), other.flatMap { group -> group.shortcuts.map { it.keys } })
    }
}
