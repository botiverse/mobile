package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * task #58: the session registry keeps one scroll snapshot per
 * (account, member, tab) so a detail destroyed by a pane transition resumes
 * at its offset, while logout/account switch and the entry cap bound memory
 * and prevent cross-account reuse.
 */
class MemberDetailScrollStateRegistryTest {

    @Test
    fun `saved position restores for same member key and tab`() {
        MemberDetailScrollStateRegistry.clear()
        val saved = MemberDetailScrollPosition(firstVisibleItemIndex = 7, firstVisibleItemScrollOffset = 42)
        MemberDetailScrollStateRegistry.saveAgentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile, saved)
        assertEquals(saved, MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile))
    }

    @Test
    fun `different tab or member or account get distinct positions`() {
        MemberDetailScrollStateRegistry.clear()
        val saved = MemberDetailScrollPosition(firstVisibleItemIndex = 5)
        MemberDetailScrollStateRegistry.saveAgentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile, saved)
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Activity)
        )
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m2", MembersAgentDetailTab.Profile)
        )
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u2:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `clear drops remembered positions so a new session cannot inherit offsets`() {
        MemberDetailScrollStateRegistry.clear()
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 9)
        )
        MemberDetailScrollStateRegistry.clear()
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `human list position is keyed per member`() {
        MemberDetailScrollStateRegistry.clear()
        val saved = MemberDetailScrollPosition(firstVisibleItemIndex = 3)
        MemberDetailScrollStateRegistry.saveHumanListPosition("u1:s1:human:h1", saved)
        assertEquals(saved, MemberDetailScrollStateRegistry.humanListPosition("u1:s1:human:h1"))
        assertEquals(MemberDetailScrollPosition(), MemberDetailScrollStateRegistry.humanListPosition("u1:s1:human:h2"))
    }

    @Test
    fun `entry cap evicts oldest member keys`() {
        MemberDetailScrollStateRegistry.clear()
        (0 until 70).forEach { i ->
            MemberDetailScrollStateRegistry.saveAgentTabPosition(
                "u1:s1:agent:m$i",
                MembersAgentDetailTab.Profile,
                MemberDetailScrollPosition(firstVisibleItemIndex = i)
            )
        }
        // Oldest entries are gone; the most recent still resolves.
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m0", MembersAgentDetailTab.Profile)
        )
        assertEquals(
            MemberDetailScrollPosition(firstVisibleItemIndex = 69),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m69", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `save issued before clear is retired and cannot resurrect the old position`() {
        MemberDetailScrollStateRegistry.clear()
        val generation = MemberDetailScrollStateRegistry.currentGeneration()
        MemberDetailScrollStateRegistry.clear()
        MemberDetailScrollStateRegistry.saveAgentTabPosition(
            "u1:s1:agent:m1",
            MembersAgentDetailTab.Profile,
            MemberDetailScrollPosition(firstVisibleItemIndex = 11),
            expectedGeneration = generation
        )
        assertEquals(
            MemberDetailScrollPosition(),
            MemberDetailScrollStateRegistry.agentTabPosition("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        )
    }

    @Test
    fun `save carrying the current generation still lands after a clear`() {
        MemberDetailScrollStateRegistry.clear()
        MemberDetailScrollStateRegistry.saveHumanListPosition(
            "u2:s1:human:h1",
            MemberDetailScrollPosition(firstVisibleItemIndex = 4),
            expectedGeneration = MemberDetailScrollStateRegistry.currentGeneration()
        )
        assertEquals(
            MemberDetailScrollPosition(firstVisibleItemIndex = 4),
            MemberDetailScrollStateRegistry.humanListPosition("u2:s1:human:h1")
        )
    }
}