package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftDuoInnerRailTest {
    private val home = RaftBottomTabItemPayload(key = "home", label = "Home", iconName = RaftLucideIconName.House, selected = true)
    private val tasks = RaftBottomTabItemPayload(key = "tasks", label = "Tasks", iconName = RaftLucideIconName.SquareCheckBig)

    @Test
    fun `Search is the rail's first destination and the tab keeps its selection while Search is closed`() {
        val items = appShellRailItemsWithSearch(listOf(home, tasks), searchLabel = "Search", searchOpen = false)
        assertEquals(listOf(AppShellRailSearchKey, "home", "tasks"), items.map { it.key })
        assertEquals(listOf(false, true, false), items.map { it.selected })
        assertEquals(RaftLucideIconName.Search, items.first().iconName)
    }

    @Test
    fun `open Search is the only selected rail destination`() {
        val items = appShellRailItemsWithSearch(listOf(home, tasks), searchLabel = "Search", searchOpen = true)
        assertEquals(listOf(true, false, false), items.map { it.selected })
    }
}
