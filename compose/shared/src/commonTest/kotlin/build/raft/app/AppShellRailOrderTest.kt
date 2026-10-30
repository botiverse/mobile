package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Nav spec 2026-10-21 §5: the wide-screen rail lists its entries in the phone bar's order. */
class AppShellRailOrderTest {
    private fun order(vararg keys: String) = keys.toList().orderedLikePhoneBar { it }

    @Test fun railFollowsThePhoneBarOrder() {
        // The rail's entries as the shell builds them: the tab keys, then the inline Search entry.
        assertEquals(
            listOf("home", "activity", "tasks", AppShellRailSearchKey, "members", "computers", "apps"),
            order(*(AppShellRailTabKeys + AppShellRailSearchKey).toTypedArray()),
        )
        // The phone bar's own destinations, Search included, are in the same relative order.
        val phoneOrder = AppShellPhoneBottomBarKeys.map { if (it == AppShellSearchTabKey) AppShellRailSearchKey else it }
        val railOrder = order(*(AppShellRailTabKeys + AppShellRailSearchKey).toTypedArray())
        assertEquals(phoneOrder.filter { it in railOrder }, railOrder.filter { it in phoneOrder })
    }

    @Test fun dmsStayBesideHome() {
        assertEquals(
            listOf("home", "dms", "activity", AppShellRailSearchKey, "more"),
            order("more", AppShellRailSearchKey, "activity", "dms", "home"),
        )
    }

    @Test fun entriesTheRailOrderLacksKeepTheirRelativeOrderAtTheEnd() {
        assertEquals(
            listOf("home", "tasks", "x", "y"),
            order("x", "tasks", "y", "home"),
        )
        assertEquals(emptyList(), order())
    }
}
