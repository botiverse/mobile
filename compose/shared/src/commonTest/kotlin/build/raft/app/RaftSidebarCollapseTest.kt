package build.raft.app

import androidx.compose.ui.input.key.Key
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftCollapsibleColumn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wide rail's Collapse / Expand Sidebar (web SidebarToggleRailItem, uiStore sidebarHidden). */
class RaftSidebarCollapseTest {
    @Test
    fun theHiddenStateIsRememberedPerColumnOnTheDevice() {
        val values = InMemoryRaftKeyValueStore()
        val store = KeyValueChatLayoutPreferenceStore(values)
        val state = RaftSidebarCollapseState(store)
        assertFalse(state.isHidden(RaftCollapsibleColumn.Sidebar), "a new device shows the sidebar")

        state.toggle(RaftCollapsibleColumn.Sidebar)
        assertTrue(state.isHidden(RaftCollapsibleColumn.Sidebar))
        // Hiding the sidebar never hides the Activity list (web: each column on its own).
        assertFalse(state.isHidden(RaftCollapsibleColumn.ActivityList))
        assertEquals("true", values.getString("mobile:sidebar_hidden"))

        // A new shell (relaunch) starts the way it was left.
        val relaunched = RaftSidebarCollapseState(KeyValueChatLayoutPreferenceStore(values))
        assertTrue(relaunched.isHidden(RaftCollapsibleColumn.Sidebar))
        assertFalse(relaunched.isHidden(RaftCollapsibleColumn.ActivityList))

        // Shown again is the key's absence, like web's localStorage.removeItem.
        relaunched.setHidden(RaftCollapsibleColumn.Sidebar, false)
        assertNull(values.getString("mobile:sidebar_hidden"))
        assertFalse(RaftSidebarCollapseState(KeyValueChatLayoutPreferenceStore(values)).isHidden(RaftCollapsibleColumn.Sidebar))
    }

    @Test
    fun theToggleShowsOnlyWhereAListColumnSitsBesideTheMainArea() {
        fun column(
            navRailActive: Boolean = true,
            tab: String,
            homeDestination: String? = null,
            inline: String? = null,
        ) = appShellCollapsibleColumn(navRailActive, tab, homeDestination, inline)

        assertEquals(RaftCollapsibleColumn.Sidebar, column(tab = "home"))
        assertEquals(RaftCollapsibleColumn.Sidebar, column(tab = "settings"))
        assertEquals(RaftCollapsibleColumn.ActivityList, column(tab = "activity"))
        // Phone layout (iPhone Duo's outer screen included): no rail, no column beside the main area.
        assertNull(column(navRailActive = false, tab = "home"))
        assertNull(column(navRailActive = false, tab = "activity"))
        // Pages without a list column, a page pushed over Home, the inline Search / Saved lists.
        assertNull(column(tab = "tasks"))
        assertNull(column(tab = "members"))
        assertNull(column(tab = "home", homeDestination = "saved"))
        assertNull(column(tab = "home", inline = AppShellRailSearchKey))
        assertNull(column(tab = "activity", inline = AppShellRailSavedKey))
    }

    @Test
    fun theShortcutIsCommandBackslashOnAppleAndCtrlBackslashElsewhere() {
        fun shortcut(
            key: Key = Key.Backslash,
            meta: Boolean = false,
            ctrl: Boolean = false,
            alt: Boolean = false,
            shift: Boolean = false,
            apple: Boolean,
            keyDown: Boolean = true,
        ) = raftSidebarToggleShortcut(keyDown, key, meta, ctrl, alt, shift, apple)

        assertTrue(shortcut(meta = true, apple = true))
        assertTrue(shortcut(ctrl = true, apple = false))
        assertFalse(shortcut(ctrl = true, apple = true))
        assertFalse(shortcut(meta = true, apple = false))
        assertFalse(shortcut(meta = true, shift = true, apple = true))
        assertFalse(shortcut(ctrl = true, alt = true, apple = false))
        assertFalse(shortcut(key = Key.Slash, ctrl = true, apple = false))
        assertFalse(shortcut(ctrl = true, apple = false, keyDown = false))
        assertEquals("⌘\\", raftSidebarToggleShortcutLabel(apple = true))
        assertEquals("Ctrl+\\", raftSidebarToggleShortcutLabel(apple = false))
    }
}
