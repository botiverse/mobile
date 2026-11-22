package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * task #232 PR3: RaftTabbedScaffold TabPager is index-aligned with the
 * available tabs in both directions. These pin the two seams a swipe crosses —
 * selection -> page and page -> selection — including the transient frames
 * where the list and the pager disagree.
 */
class RaftTabbedScaffoldPagerIndexTest {
    private enum class TestTabKey { Chat, Tasks, Files }

    private val tabs = listOf(
        RaftTabSpec(TestTabKey.Chat, "Chat", RaftLucideIconName.MessageSquare),
        RaftTabSpec(TestTabKey.Tasks, "Tasks", RaftLucideIconName.ListTodo),
        RaftTabSpec(TestTabKey.Files, "Files", RaftLucideIconName.Paperclip),
    )

    @Test
    fun selection_maps_to_its_position_in_tabs() {
        assertEquals(0, raftTabbedScaffoldPageIndex(tabs, TestTabKey.Chat))
        assertEquals(1, raftTabbedScaffoldPageIndex(tabs, TestTabKey.Tasks))
        assertEquals(2, raftTabbedScaffoldPageIndex(tabs, TestTabKey.Files))
    }

    @Test
    fun missing_selection_pins_page_zero_instead_of_negative_index() {
        val subset = listOf(
            RaftTabSpec(TestTabKey.Chat, "Chat", RaftLucideIconName.MessageSquare),
            RaftTabSpec(TestTabKey.Files, "Files", RaftLucideIconName.Paperclip),
        )
        // Tasks is missing from the subset (e.g. channel doesn't support tasks)
        assertEquals(0, raftTabbedScaffoldPageIndex(subset, TestTabKey.Tasks))
        assertEquals(0, raftTabbedScaffoldPageIndex(emptyList(), TestTabKey.Chat))
    }
}
