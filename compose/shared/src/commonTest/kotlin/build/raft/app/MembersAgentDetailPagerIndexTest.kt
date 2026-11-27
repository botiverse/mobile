package build.raft.app

import build.raft.app.members.MembersAgentDetailTab
import build.raft.app.members.membersAgentDetailPageTab
import build.raft.app.members.membersAgentDetailPagerIndex
import build.raft.app.members.membersAgentDetailShouldComposeTab
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
