package build.raft.app

import build.raft.designsystem.components.RaftMobileNavServerPopoverState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Design D1a: the current-server tab, its menu, the switch that keeps the tab, the dot mapping. */
class RaftServerMenuModelTest {
    private val botiverse = RaftServerSwitcherItem(id = "s1", name = "Botiverse", slug = "botiverse", activityUnreadCount = 0L)
    private val acme = RaftServerSwitcherItem(id = "s2", name = "Acme Labs", slug = "acme", activityUnreadCount = 3L)
    private val quiet = RaftServerSwitcherItem(id = "s3", name = "Quiet", slug = "quiet", activityUnreadCount = 0L)

    private fun model(
        servers: List<RaftServerSwitcherItem> = listOf(botiverse, acme, quiet),
        current: String = "s1",
        attention: Set<RaftMoreAttention> = emptySet(),
    ) = raftServerMenuModel(servers, currentServerId = current, currentServerName = "fallback", moreAttention = attention)

    @Test
    fun menuListsEveryServerMarksTheCurrentOneAndHoldsTheMoreRows() {
        val model = model()
        assertEquals(listOf("s1", "s2", "s3"), model.servers.map { it.id })
        assertEquals("s1", model.currentServer.id)
        assertEquals("Botiverse", model.currentServer.name)
        assertEquals(
            listOf(RaftServerMenuEntry.Members, RaftServerMenuEntry.Computers, RaftServerMenuEntry.Settings),
            model.entries,
        )
        assertEquals(listOf("members", SettingsDestinationComputers, "settings"), model.entries.map { it.key })
    }

    @Test
    fun unreadComesFromTheHomeSwitcherBadges() {
        val model = model()
        assertEquals(listOf(null, "3", null), model.servers.map(::raftServerMenuUnreadLabel))
        // Acme (not current) has unread: the avatar's other-servers light.
        assertTrue(model.otherServersUnread)
        // Only the current server has unread: no light.
        assertFalse(model(current = "s2").otherServersUnread)
    }

    @Test
    fun currentServerFallsBackToTheLoadedNameBeforeTheListHasIt() {
        val model = raftServerMenuModel(emptyList(), currentServerId = "s9", currentServerName = "Loading Co")
        assertEquals("s9", model.currentServer.id)
        assertEquals("Loading Co", model.currentServer.name)
    }

    @Test
    fun theMoreDotMovesToTheMenuRowBehindIt() {
        assertEquals(RaftServerMenuEntry.Computers, RaftMoreAttention.Computers.serverMenuEntry())
        assertEquals(RaftServerMenuEntry.Settings, RaftMoreAttention.AppUpdate.serverMenuEntry())
        val model = model(attention = setOf(RaftMoreAttention.Computers))
        assertEquals(setOf(RaftServerMenuEntry.Computers), model.attentionEntries)
        // Built from the same Home notices the More tab read (an unseen offline computer).
        val fromNotices = raftMoreAttention(listOf("machine-offline:c1"))
        assertEquals(setOf(RaftMoreAttention.Computers), fromNotices)
        assertEquals(setOf(RaftServerMenuEntry.Computers), model(attention = fromNotices).attentionEntries)
    }

    @Test
    fun avatarDotMeansOnlyAnotherServerHasUnread() {
        val unread = model()
        val rowDotOnly = model(servers = listOf(botiverse, quiet), attention = setOf(RaftMoreAttention.AppUpdate, RaftMoreAttention.Computers))
        assertTrue(appShellServerTabShowsAttention(unread))
        // An app update / a computer needing action dots its own panel row, not the avatar.
        assertFalse(appShellServerTabShowsAttention(rowDotOnly))
        assertEquals(setOf(RaftServerMenuEntry.Settings, RaftServerMenuEntry.Computers), rowDotOnly.attentionEntries)
    }

    @Test
    fun phoneBarLastTabIsTheCurrentServerAndNeverSelected() {
        val build = { selectedTab: String, server: RaftServerMenuModel? ->
            appShellPhoneBottomTabs(
                selectedTab = selectedTab,
                homeLabel = "h",
                activityLabel = "a",
                searchLabel = "s",
                tasksLabel = "t",
                moreLabel = "More",
                moreAttention = setOf(RaftMoreAttention.Computers),
                serverTab = server,
            )
        }
        val tabs = build("activity", model())
        // Still five tabs; the last keeps the `more` key.
        assertEquals(listOf("home", "activity", "tasks", AppShellSearchTabKey, AppShellServerTabKey), tabs.map { it.key })
        val serverTab = tabs.last()
        assertEquals("Botiverse", serverTab.label)
        assertEquals(RaftLucideIconName.Server, serverTab.iconName)
        assertTrue(serverTab.attentionDot)
        // It holds no page: never selected, even on its own pages; the dot stays.
        listOf("settings", "members", "more").forEach { selected ->
            val tab = build(selected, model()).last()
            assertFalse(tab.selected, selected)
            assertTrue(tab.attentionDot, selected)
        }
        // A computer needing action alone does not light the server tab.
        assertFalse(build("home", model(servers = listOf(botiverse, quiet))).last().attentionDot)
        // Before a server loads, the plain More tab.
        val plain = build("home", null).last()
        assertEquals("More", plain.label)
        assertEquals(RaftLucideIconName.Ellipsis, plain.iconName)
    }

    @Test
    fun serverPagesArePushedAndGoBackToWhereThePanelOpened() {
        // Members and Settings are pushed pages: no bar.
        listOf("members", "more").forEach { tab ->
            assertFalse(appShellBottomTabsVisible(selectedTab = tab, selectedHomeDestination = null), tab)
        }
        // Opened from the panel on Activity: Back returns to Activity.
        val origin = appShellServerPageOrigin(selectedTab = "activity", currentOrigin = null)
        assertEquals("activity", origin)
        assertEquals("activity", appShellMoreChildBackTarget("members", navRailActive = false, origin = origin))
        assertEquals("activity", appShellMoreChildBackTarget("more", navRailActive = false, origin = origin))
        // Another server page opened over one keeps the first origin.
        assertEquals("activity", appShellServerPageOrigin(selectedTab = "members", currentOrigin = origin))
        // A deep link (no origin) goes back to Home.
        assertEquals("home", appShellMoreChildBackTarget("more", navRailActive = false, origin = null))
        // The wide rail keeps them as roots.
        assertNull(appShellMoreChildBackTarget("members", navRailActive = true, origin = origin))
        // Tab roots keep the ordinary rules.
        assertNull(appShellMoreChildBackTarget("tasks", navRailActive = false, origin = origin))
    }

    @Test
    fun switchingKeepsTheCurrentTab() {
        listOf("activity", "tasks", AppShellSearchTabKey, "home", "more", "members").forEach { tab ->
            assertEquals(
                AppShellServerSwitchPlan(serverId = "s2", tab = tab),
                appShellServerMenuSwitchPlan(currentServerId = "s1", pickedServerId = "s2", selectedTab = tab),
                tab,
            )
        }
        // The current server (or nothing) just closes the panel.
        assertNull(appShellServerMenuSwitchPlan("s1", "s1", "activity"))
        assertNull(appShellServerMenuSwitchPlan("s1", "  ", "activity"))
    }

    @Test
    fun heldLongPressLetsGoOverARow() {
        val state = RaftMobileNavServerPopoverState()
        state.register(raftServerMenuServerKey("s2"), Rect(0f, 0f, 100f, 40f))
        state.register(raftServerMenuEntryKey(RaftServerMenuEntry.Settings), Rect(0f, 50f, 100f, 90f))
        // Moves without a hold do nothing.
        state.moveHold(Offset(10f, 10f))
        assertNull(state.highlightedKey)
        state.beginHold()
        assertTrue(state.open)
        state.moveHold(Offset(10f, 10f))
        assertEquals(RaftServerMenuPick.Server("s2"), raftServerMenuPick(state.highlightedKey!!))
        state.moveHold(Offset(10f, 60f))
        val released = state.endHold()
        assertEquals(RaftServerMenuPick.Page(RaftServerMenuEntry.Settings), raftServerMenuPick(released!!))
        // Let go outside every row: nothing picked, the panel stays open.
        state.beginHold()
        state.moveHold(Offset(500f, 500f))
        assertNull(state.endHold())
        assertTrue(state.open)
        state.dismiss()
        assertFalse(state.open)
        assertNull(raftServerMenuPick("bogus"))
    }

    @Test
    fun serverTabIsAPanelButtonWithItsAvatarAndNoCountOnTheNativeBar() {
        val pictured = botiverse.copy(avatarUrl = "https://cdn.example/b.png")
        val loud = RaftServerSwitcherItem(id = "s4", name = "Loud", slug = "loud", activityUnreadCount = 120L)
        val tabs =
            appShellPhoneBottomTabs(
                selectedTab = "home",
                homeLabel = "h",
                activityLabel = "a",
                searchLabel = "s",
                tasksLabel = "t",
                moreLabel = "m",
                serverTab = model(listOf(pictured, acme, loud)),
                serverTabAccessibilityLabel = "Current server: Botiverse",
            )
        val serverTab = tabs.single { it.key == AppShellServerTabKey }
        // Android's Compose bar keeps the dot; the iOS bar shows no badge (artin 10-23).
        assertTrue(serverTab.attentionDot)
        assertTrue(serverTab.opensPanel)
        assertEquals("Current server: Botiverse", serverTab.accessibilityLabel)
        assertEquals("https://cdn.example/b.png", serverTab.avatarSource)
        assertEquals("B", serverTab.avatarFallbackLabel)
        val native = raftNativeTabBarItem(serverTab)
        assertNull(native.badgeValue)
        assertTrue(native.opensPanel)
        assertEquals("Current server: Botiverse", native.accessibilityLabel)
        assertEquals("B", native.avatarFallbackLabel)
        // Only the panel tab carries the decoded avatar.
        val avatar = RaftNativeTabBarAvatar(1, 1, intArrayOf(0xFF102030.toInt()))
        val state = raftNativeTabBarState(tabs, visible = true, panelAvatar = avatar)
        assertTrue(state.items.single { it.key == AppShellServerTabKey }.avatar === avatar)
        assertTrue(state.items.filter { it.key != AppShellServerTabKey }.all { it.avatar == null && !it.opensPanel })
        // Without a server loaded, More is a page tab, not a panel button.
        val plain = appShellPhoneBottomTabs("home", "h", "a", "s", "t", "m").single { it.key == "more" }
        assertFalse(plain.opensPanel)
    }
}
