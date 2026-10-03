package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** task #49: wide-screen rail entries follow the web LeftRail order. */
class AppShellRailWebOrderTest {
    private fun order(vararg keys: String) = keys.toList().orderedLikeWebRail { it }

    @Test fun legacyNavRailMatchesTheWebOrder() {
        // Today's legacy rail: Home, Tasks, Members, then Activity and Search appended.
        assertEquals(
            listOf(AppShellRailSearchKey, "home", "activity", "tasks", "members"),
            order("home", "tasks", "members", "activity", AppShellRailSearchKey),
        )
    }

    @Test fun slackNavRailKeepsDmsBesideHome() {
        assertEquals(
            listOf(AppShellRailSearchKey, "home", "dms", "activity", "more"),
            order("home", "dms", "activity", "more", AppShellRailSearchKey),
        )
    }

    @Test fun entriesTheWebRailLacksKeepTheirRelativeOrderAtTheEnd() {
        assertEquals(
            listOf("home", "tasks", "x", "y"),
            order("x", "tasks", "y", "home"),
        )
        assertEquals(emptyList(), order())
    }
}
