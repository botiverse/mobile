package build.raft.app

import com.tencent.kuikly.compose.foundation.ExperimentalFoundationApi
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * task #58: the session registry keeps one LazyListState per
 * (account, member, tab) so a detail destroyed by a pane transition resumes
 * at its offset, while logout/account switch and the entry cap bound memory
 * and prevent cross-account reuse.
 */
@OptIn(ExperimentalFoundationApi::class)
class MemberDetailScrollStateRegistryTest {

    @Test
    fun `same member key and tab reuse one state`() {
        MemberDetailScrollStateRegistry.clear()
        val first =
            MemberDetailScrollStateRegistry.agentTabState("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        val again =
            MemberDetailScrollStateRegistry.agentTabState("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        assertSame(first, again)
    }

    @Test
    fun `different tab or member or account get distinct states`() {
        MemberDetailScrollStateRegistry.clear()
        val profile =
            MemberDetailScrollStateRegistry.agentTabState("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        val activity =
            MemberDetailScrollStateRegistry.agentTabState("u1:s1:agent:m1", MembersAgentDetailTab.Activity)
        val otherMember =
            MemberDetailScrollStateRegistry.agentTabState("u1:s1:agent:m2", MembersAgentDetailTab.Profile)
        val otherAccount =
            MemberDetailScrollStateRegistry.agentTabState("u2:s1:agent:m1", MembersAgentDetailTab.Profile)
        assertNotSame(profile, activity)
        assertNotSame(profile, otherMember)
        assertNotSame(profile, otherAccount)
    }

    @Test
    fun `clear drops remembered states so a new session cannot inherit offsets`() {
        MemberDetailScrollStateRegistry.clear()
        val before =
            MemberDetailScrollStateRegistry.agentTabState("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        MemberDetailScrollStateRegistry.clear()
        val after =
            MemberDetailScrollStateRegistry.agentTabState("u1:s1:agent:m1", MembersAgentDetailTab.Profile)
        assertNotSame(before, after)
    }

    @Test
    fun `human list state is keyed per member`() {
        MemberDetailScrollStateRegistry.clear()
        val one = MemberDetailScrollStateRegistry.humanListState("u1:s1:human:h1")
        assertSame(one, MemberDetailScrollStateRegistry.humanListState("u1:s1:human:h1"))
        assertNotSame(one, MemberDetailScrollStateRegistry.humanListState("u1:s1:human:h2"))
    }
}
