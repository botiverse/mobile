package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * task #232 PR3: SlockTabbedScaffold TabPager is index-aligned with the
 * available tabs in both directions. These pin the two seams a swipe crosses —
 * selection -> page and page -> selection — including the transient frames
 * where the list and the pager disagree.
 */
class SlockTabbedScaffoldPagerIndexTest {
    private enum class TestTabKey { Chat, Tasks, Files }

    private val tabs = listOf(
        SlockTabSpec(TestTabKey.Chat, "Chat", SlockLucideIconName.MessageSquare),
        SlockTabSpec(TestTabKey.Tasks, "Tasks", SlockLucideIconName.ListTodo),
        SlockTabSpec(TestTabKey.Files, "Files", SlockLucideIconName.Paperclip),
    )

    @Test
    fun selection_maps_to_its_position_in_tabs() {
        assertEquals(0, slockTabbedScaffoldPageIndex(tabs, TestTabKey.Chat))
        assertEquals(1, slockTabbedScaffoldPageIndex(tabs, TestTabKey.Tasks))
        assertEquals(2, slockTabbedScaffoldPageIndex(tabs, TestTabKey.Files))
    }

    @Test
    fun missing_selection_pins_page_zero_instead_of_negative_index() {
        val subset = listOf(
            SlockTabSpec(TestTabKey.Chat, "Chat", SlockLucideIconName.MessageSquare),
            SlockTabSpec(TestTabKey.Files, "Files", SlockLucideIconName.Paperclip),
        )
        // Tasks is missing from the subset (e.g. channel doesn't support tasks)
        assertEquals(0, slockTabbedScaffoldPageIndex(subset, TestTabKey.Tasks))
        assertEquals(0, slockTabbedScaffoldPageIndex(emptyList(), TestTabKey.Chat))
    }

    @Test
    fun page_maps_back_to_tab_key_at_that_position() {
        assertEquals(TestTabKey.Chat, slockTabbedScaffoldPageKey(tabs, 0))
        assertEquals(TestTabKey.Tasks, slockTabbedScaffoldPageKey(tabs, 1))
        assertEquals(TestTabKey.Files, slockTabbedScaffoldPageKey(tabs, 2))
    }

    @Test
    fun page_outside_tabs_list_is_null_not_a_crash() {
        assertNull(slockTabbedScaffoldPageKey(tabs, 3))
        assertNull(slockTabbedScaffoldPageKey(tabs, -1))
        assertNull(slockTabbedScaffoldPageKey(emptyList(), 0))
    }

    @Test
    fun round_trips_every_tab_key() {
        for (spec in tabs) {
            val page = slockTabbedScaffoldPageIndex(tabs, spec.key)
            assertEquals(spec.key, slockTabbedScaffoldPageKey(tabs, page))
        }
    }
}
