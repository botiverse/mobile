package build.raft.app

import androidx.compose.ui.input.key.Key
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftCollapsibleColumn
import build.raft.app.settings.AppShellRailSavedKey
import build.raft.app.settings.AppShellRailSearchKey
import build.raft.app.shell.RaftSidebarCollapseState
import build.raft.app.shell.appShellCollapsibleColumn
import build.raft.app.shell.raftSidebarGivesWay
import build.raft.app.shell.raftSidebarToggleShortcut
import build.raft.app.shell.raftSidebarToggleShortcutLabel
import build.raft.app.thread.key
import build.raft.designsystem.theme.RaftPointerInput
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

    @Test
    fun aSidebarGivingWayIsHiddenUnsavedAndComesBackWithTheRoom() {
        val values = InMemoryRaftKeyValueStore()
        val state = RaftSidebarCollapseState(KeyValueChatLayoutPreferenceStore(values))
        state.setGivingWay(RaftCollapsibleColumn.Sidebar, true)
        assertTrue(state.isHidden(RaftCollapsibleColumn.Sidebar), "Tasks opened on a portrait iPad: the sidebar collapses")
        assertFalse(state.isHidden(RaftCollapsibleColumn.ActivityList))
        assertNull(values.getString("mobile:sidebar_hidden"), "giving way is never saved")
        state.setGivingWay(RaftCollapsibleColumn.Sidebar, false)
        assertFalse(state.isHidden(RaftCollapsibleColumn.Sidebar), "Tasks closed: the sidebar is back")
    }

    @Test
    fun thePersonBringingTheSidebarBackKeepsItUntilTheRowHasRoom() {
        val values = InMemoryRaftKeyValueStore()
        val state = RaftSidebarCollapseState(KeyValueChatLayoutPreferenceStore(values))
        state.setGivingWay(RaftCollapsibleColumn.Sidebar, true)
        // The rail's Expand Sidebar (or the shortcut): shown, the row clipping, as they asked.
        state.toggle(RaftCollapsibleColumn.Sidebar)
        assertFalse(state.isHidden(RaftCollapsibleColumn.Sidebar))
        assertNull(values.getString("mobile:sidebar_hidden"))
        // Still crowded (the plan reports it after every frame): it stays shown.
        state.setGivingWay(RaftCollapsibleColumn.Sidebar, true)
        assertFalse(state.isHidden(RaftCollapsibleColumn.Sidebar))
        // Collapsing it again is the person's own choice, saved as ever.
        state.toggle(RaftCollapsibleColumn.Sidebar)
        assertTrue(state.isHidden(RaftCollapsibleColumn.Sidebar))
        assertEquals("true", values.getString("mobile:sidebar_hidden"))
        state.setHidden(RaftCollapsibleColumn.Sidebar, false)
        // Once the row had room, the next crowded row collapses it again.
        state.setGivingWay(RaftCollapsibleColumn.Sidebar, false)
        state.setGivingWay(RaftCollapsibleColumn.Sidebar, true)
        assertTrue(state.isHidden(RaftCollapsibleColumn.Sidebar))
    }

    @Test
    fun aSidebarTheyCollapsedStaysCollapsedWhenTheRowHasRoomAgain() {
        val state = RaftSidebarCollapseState(KeyValueChatLayoutPreferenceStore(InMemoryRaftKeyValueStore()))
        state.setHidden(RaftCollapsibleColumn.Sidebar, true)
        state.setGivingWay(RaftCollapsibleColumn.Sidebar, true)
        state.setGivingWay(RaftCollapsibleColumn.Sidebar, false)
        assertTrue(state.isHidden(RaftCollapsibleColumn.Sidebar))
    }

    @Test
    fun onlyAPortraitTouchTabletUnder1280GivesWay() {
        // iPad Air / iPad 10th gen upright, iPad mini upright, a 12.9" iPad Pro upright.
        assertTrue(raftSidebarGivesWay(RaftPointerInput.Touch, 820f, 1180f, crowdsRow = true))
        assertTrue(raftSidebarGivesWay(RaftPointerInput.Touch, 744f, 1133f, crowdsRow = true))
        assertTrue(raftSidebarGivesWay(RaftPointerInput.Touch, 1024f, 1366f, crowdsRow = true))
        // The row has room: nothing to do.
        assertFalse(raftSidebarGivesWay(RaftPointerInput.Touch, 820f, 1180f, crowdsRow = false))
        // Landscape, a mouse or trackpad, a wide portrait screen: web's row as it is.
        assertFalse(raftSidebarGivesWay(RaftPointerInput.Touch, 1180f, 820f, crowdsRow = true))
        assertFalse(raftSidebarGivesWay(RaftPointerInput.Fine, 820f, 1180f, crowdsRow = true))
        assertFalse(raftSidebarGivesWay(RaftPointerInput.Touch, 1280f, 1600f, crowdsRow = true))
    }
}
