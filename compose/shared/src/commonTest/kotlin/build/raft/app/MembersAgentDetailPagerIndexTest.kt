package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * task #232 PR2: the agent-detail TabPager is index-aligned with the visible
 * tab list in both directions. These pin the two seams a swipe crosses —
 * selection -> page and page -> selection — including the transient frames
 * where the list and the pager disagree.
 */
class MembersAgentDetailPagerIndexTest {
    private val visible = listOf(
        MembersAgentDetailTab.Profile,
        MembersAgentDetailTab.Activity,
        MembersAgentDetailTab.Chat,
        MembersAgentDetailTab.Workspace,
    )

    @Test
    fun selection_maps_to_its_visible_position_not_the_enum_ordinal() {
        // Workspace is ordinal 4 in the enum but page 3 in this visible order.
        assertEquals(3, membersAgentDetailPagerIndex(visible, MembersAgentDetailTab.Workspace))
        assertEquals(0, membersAgentDetailPagerIndex(visible, MembersAgentDetailTab.Profile))
    }

    @Test
    fun hidden_selection_pins_page_zero_instead_of_a_negative_index() {
        assertEquals(0, membersAgentDetailPagerIndex(visible, MembersAgentDetailTab.Apps))
        assertEquals(0, membersAgentDetailPagerIndex(emptyList(), MembersAgentDetailTab.Profile))
    }

    @Test
    fun page_maps_back_to_the_tab_at_that_visible_position() {
        assertEquals(MembersAgentDetailTab.Chat, membersAgentDetailPageTab(visible, 2))
    }

    @Test
    fun page_outside_the_visible_list_is_null_not_a_crash() {
        assertNull(membersAgentDetailPageTab(visible, 4))
        assertNull(membersAgentDetailPageTab(visible, -1))
        assertNull(membersAgentDetailPageTab(emptyList(), 0))
    }

    @Test
    fun reordered_visible_list_round_trips_every_tab() {
        val reordered = visible.reversed()
        for (tab in reordered) {
            assertEquals(tab, membersAgentDetailPageTab(reordered, membersAgentDetailPagerIndex(reordered, tab)))
        }
    }

    @Test
    fun unvisited_tab_does_not_compose_when_unselected() {
        val visited = setOf(MembersAgentDetailTab.Profile)
        assertEquals(
            false,
            membersAgentDetailShouldComposeTab(
                pageTab = MembersAgentDetailTab.Chat,
                selectedTab = MembersAgentDetailTab.Profile,
                visitedTabs = visited
            )
        )
    }

    @Test
    fun currently_selected_tab_composes_even_if_not_yet_in_visited_set() {
        assertEquals(
            true,
            membersAgentDetailShouldComposeTab(
                pageTab = MembersAgentDetailTab.Chat,
                selectedTab = MembersAgentDetailTab.Chat,
                visitedTabs = setOf(MembersAgentDetailTab.Profile)
            )
        )
    }

    @Test
    fun visited_tab_remains_composed_after_user_switches_away() {
        val visited = setOf(MembersAgentDetailTab.Profile, MembersAgentDetailTab.Chat)
        assertEquals(
            true,
            membersAgentDetailShouldComposeTab(
                pageTab = MembersAgentDetailTab.Profile,
                selectedTab = MembersAgentDetailTab.Chat,
                visitedTabs = visited
            )
        )
    }

    @Test
    fun null_page_tab_never_composes() {
        assertEquals(
            false,
            membersAgentDetailShouldComposeTab(
                pageTab = null,
                selectedTab = MembersAgentDetailTab.Profile,
                visitedTabs = setOf(MembersAgentDetailTab.Profile)
            )
        )
    }
}
