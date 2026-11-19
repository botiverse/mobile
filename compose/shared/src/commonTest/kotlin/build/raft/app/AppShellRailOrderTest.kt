package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Nav spec 2026-10-21 §5: the wide-screen rail lists its entries in the phone bar's order. */
class AppShellRailOrderTest {
    private fun order(vararg keys: String) = keys.toList().orderedLikePhoneBar { it }

    @Test fun railFollowsThePhoneBarOrder() {
        // The rail's entries as the shell builds them: the tab keys, then the inline Search entry.
        assertEquals(
            listOf("home", "activity", "tasks", AppShellRailSearchKey, "artifacts", "members", "computers", "apps", "assistant"),
            order(*(AppShellRailTabKeys + AppShellRailSearchKey).toTypedArray()),
        )
        // The phone bar's own destinations, Search included, are in the same relative order.
        val phoneOrder = AppShellPhoneBottomBarKeys.map { if (it == AppShellSearchTabKey) AppShellRailSearchKey else it }
        val railOrder = order(*(AppShellRailTabKeys + AppShellRailSearchKey).toTypedArray())
        assertEquals(phoneOrder.filter { it in railOrder }, railOrder.filter { it in phoneOrder })
    }

    /** Review H1: the shell sorts the rail twice (phone-bar order, then the saved order); Artifacts survives both and a drag. */
    @Test fun artifactsFollowsTasksThroughBothSortsAndADragKeepsIt() {
        fun drawn(order: List<String>) =
            appShellRailTabs(selectedTab = "home", labelFor = { it })
                .orderedLikePhoneBar { it.key }
                .orderedLikeRailTabOrder(order)
                .map { it.key }
        val default = build.raft.app.data.normalizeRaftRailTabOrder(null)
        assertEquals(listOf("home", "activity", "tasks", "artifacts", "members", "computers", "apps"), drawn(default))

        // Artifacts dragged to the top: saved, and drawn there next time.
        val dragged = listOf("artifacts", "home", "activity", "tasks", "members", "computers", "apps")
        val saved = build.raft.app.data.raftRailTabOrderWithVisibleOrder(default, dragged.mapNotNull(::raftRailTabIdForKey))
        assertEquals(listOf("search", "artifacts", "chat", "activity", "tasks", "members", "computers", "apps", "assistant"), saved)
        assertEquals(dragged, drawn(saved))

    }

    /** web RAIL_TAB_IDS: the assistant is the last tab, drawn only where its entry shows. */
    @Test fun theAssistantIsLastAndOnlyWhereItShows() {
        fun drawn(order: List<String>, assistant: Boolean) =
            appShellRailTabs(selectedTab = "home", labelFor = { it }, assistant = assistant)
                .orderedLikePhoneBar { it.key }
                .orderedLikeRailTabOrder(order)
                .map { it.key }
        val default = build.raft.app.data.normalizeRaftRailTabOrder(null)
        assertEquals(listOf("home", "activity", "tasks", "artifacts", "members", "computers", "apps", "assistant"), drawn(default, assistant = true))
        assertEquals(listOf("home", "activity", "tasks", "artifacts", "members", "computers", "apps"), drawn(default, assistant = false))
        // Dragged to the top here, it keeps that place; a server without it draws the rest unchanged.
        val dragged = listOf("assistant", "home", "activity", "tasks", "artifacts", "members", "computers", "apps")
        val saved = build.raft.app.data.raftRailTabOrderWithVisibleOrder(default, dragged.mapNotNull(::raftRailTabIdForKey))
        assertEquals(dragged, drawn(saved, assistant = true))
        assertEquals(dragged - "assistant", drawn(saved, assistant = false))
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
