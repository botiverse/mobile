package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One navigation model for every platform (artin, #proj-mobile 10-30): RaftAppDestinations.kt. */
class RaftAppDestinationsTest {
    @Test
    fun idsAreStableAndUnique() {
        // The ids are the persisted shell tab keys and the future Nav3 back-stack keys.
        assertEquals(
            mapOf(
                RaftAppDestination.Chat to "home",
                RaftAppDestination.Activity to "activity",
                RaftAppDestination.Tasks to "tasks",
                RaftAppDestination.Search to "search",
                RaftAppDestination.Members to "members",
                RaftAppDestination.Computers to "computers",
                RaftAppDestination.Apps to "apps",
                RaftAppDestination.Server to "more",
                RaftAppDestination.Help to "help",
                RaftAppDestination.Settings to "settings",
            ),
            RaftAppDestination.entries.associateWith { it.id },
        )
        RaftAppDestination.entries.forEach { assertEquals(it, RaftAppDestination.fromId(it.id)) }
    }

    @Test
    fun thePhoneBarKeepsTodaysTabsAndIcons() {
        // The phone bar must not change: keys, order and icons as before the shared list.
        assertEquals(listOf("home", "activity", "tasks", AppShellSearchTabKey, "more"), AppShellPhoneBottomBarKeys)
        assertEquals(
            listOf(
                RaftLucideIconName.House,
                RaftLucideIconName.Activity,
                RaftLucideIconName.SquareCheckBig,
                RaftLucideIconName.Search,
                RaftLucideIconName.Ellipsis,
            ),
            AppShellPhoneBottomBarKeys.map(::appShellTabIcon),
        )
        assertEquals(RaftLucideIconName.MessageCircle, appShellTabIcon("dms"))
        assertEquals(RaftLucideIconName.Users, appShellTabIcon("members"))
        assertEquals(RaftLucideIconName.Settings, appShellTabIcon("settings"))
    }

    @Test
    fun theWindowWidthPicksThePresentation() {
        assertEquals(RaftNavPresentation.PhoneBar, raftNavPresentation(navRailActive = false))
        assertEquals(RaftNavPresentation.Rail, raftNavPresentation(navRailActive = true))
        assertEquals(
            listOf("home", "activity", "tasks", "members", "computers", "apps"),
            RaftNavPresentation.Rail.destinations.map { it.id },
        )
        assertEquals(listOf("help", "settings"), RaftNavPresentation.Rail.footer.map { it.id })
    }

    @Test
    fun eachDestinationHasOneBadgeSource() {
        val all = RaftNavBadgeInputs(activityUnreadCount = 4, moreAttention = RaftMoreAttention.entries.toSet())
        val lit = RaftAppDestination.entries.filter { raftNavShowsBadge(it, all, selected = false) }
        assertEquals(
            listOf(
                RaftAppDestination.Activity,
                RaftAppDestination.Computers,
                RaftAppDestination.Server,
                RaftAppDestination.Settings,
            ),
            lit,
        )
        // Selected never shows its dot.
        RaftAppDestination.entries.forEach { assertFalse(raftNavShowsBadge(it, all, selected = true), it.name) }
        // Only Activity has a count for the iOS system bar.
        assertEquals(4, raftNavNativeBadgeCount(RaftAppDestination.Activity, all, selected = false))
        assertEquals(0, raftNavNativeBadgeCount(RaftAppDestination.Computers, all, selected = false))
    }

    @Test
    fun routesSayWhatATapDoes() {
        assertEquals(
            setOf(RaftAppDestination.Computers, RaftAppDestination.Apps),
            RaftAppDestination.entries.filter { it.route == RaftNavRoute.Page }.toSet(),
        )
        assertEquals(RaftNavRoute.Menu, RaftAppDestination.Help.route)
        assertEquals(RaftNavRoute.ServerPanel, RaftAppDestination.Server.route)
        // A page entry is never selected, a menu only while open.
        val items =
            raftNavItems(RaftNavPresentation.Rail, listOf("computers", "help"), selectedKey = "computers", labelFor = { it }, menuOpen = true)
        assertEquals(listOf(false, true), items.map { it.selected })
        assertTrue(items.none { it.attentionDot })
    }
}
