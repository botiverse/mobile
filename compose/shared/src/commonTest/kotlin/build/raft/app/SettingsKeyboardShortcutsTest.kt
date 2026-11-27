package build.raft.app

import build.raft.app.resources.*
import build.raft.app.settings.settingsKeyboardShortcutGroups
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Appearance › Keyboard shortcuts: only where a hardware keyboard is plausible, and only the
 * shortcuts the app handles (web `features/shortcuts/registry.ts` rows `search`, `sidebar`,
 * `previous-match`), with ⌘ on Apple platforms and Ctrl elsewhere.
 */
class SettingsKeyboardShortcutsTest {
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
