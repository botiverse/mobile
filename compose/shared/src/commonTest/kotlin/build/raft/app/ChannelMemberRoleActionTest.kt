package build.raft.app

import build.raft.app.data.ChannelMemberRole
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The members page's role action follows web `MemberRoleActions` (botiverse/slock
 * packages/web/src/components/agent/MemberRoleActions.tsx @ bb8b13a1a3) and the server's per-row
 * `canChangeChannelRole` (routes/channels.ts `buildChannelMembersView`).
 */
class ChannelMemberRoleActionTest {
    private fun member(
        id: String = "user-2",
        kind: MemberKind = MemberKind.Human,
        serverRole: String? = "member",
        effectiveChannelRole: String? = "member",
        channelRole: String? = null,
        canChangeChannelRole: Boolean? = true,
    ) = MemberProfile(
        id = MemberId(id),
        displayName = id,
        handle = id,
        kind = kind,
        serverRole = serverRole,
        effectiveChannelRole = effectiveChannelRole,
        channelRole = channelRole,
        canChangeChannelRole = canChangeChannelRole,
    )

    private fun action(member: MemberProfile, canChange: Boolean = true, viewer: String? = "user-1") =
        channelMemberRoleAction(member, canChangeMemberRoles = canChange, currentUserId = viewer)

    @Test
    fun aPlainMemberTheServerLetsTheViewerChangeCanBeMadeAdmin() {
        assertEquals(ChannelMemberRoleAction.MakeAdmin, action(member()))
        assertEquals(ChannelMemberRole.Admin, ChannelMemberRoleAction.MakeAdmin.nextRole)
    }

    @Test
    fun anExplicitChannelAdminCanBeDemoted() {
        assertEquals(
            ChannelMemberRoleAction.RemoveAdmin,
            action(member(effectiveChannelRole = "admin", channelRole = "admin")),
        )
        assertEquals(ChannelMemberRole.Member, ChannelMemberRoleAction.RemoveAdmin.nextRole)
    }

    @Test
    fun agentsFollowTheSameRule() {
        assertEquals(ChannelMemberRoleAction.MakeAdmin, action(member(id = "agent-1", kind = MemberKind.Agent)))
        assertEquals(
            ChannelMemberRoleAction.RemoveAdmin,
            action(member(id = "agent-1", kind = MemberKind.Agent, effectiveChannelRole = "admin")),
        )
    }

    @Test
    fun withoutTheRowsServerPermissionNothingIsOffered() {
        // Oneself, a guest, another server's person: the server sends canChangeChannelRole false.
        assertNull(action(member(canChangeChannelRole = false)))
        assertNull(action(member(canChangeChannelRole = null)))
        assertNull(action(member(serverRole = "guest", effectiveChannelRole = "guest", canChangeChannelRole = false)))
    }

    @Test
    fun withoutTheChannelCapabilityNothingIsOffered() {
        assertNull(action(member(), canChange = false))
        assertNull(action(member(serverRole = "admin", effectiveChannelRole = "admin", canChangeChannelRole = false), canChange = false))
    }

    @Test
    fun aServerOwnerOrAdminOnlyGetsTheCannotDemoteExplanation() {
        assertEquals(
            ChannelMemberRoleAction.ExplainProtectedDemote,
            action(member(serverRole = "owner", effectiveChannelRole = "owner", canChangeChannelRole = false)),
        )
        assertEquals(
            ChannelMemberRoleAction.ExplainProtectedDemote,
            action(member(serverRole = "admin", effectiveChannelRole = "admin", canChangeChannelRole = false)),
        )
        assertNull(ChannelMemberRoleAction.ExplainProtectedDemote.nextRole)
    }

    @Test
    fun theViewerNeverGetsAnActionOnTheirOwnRow() {
        assertNull(
            action(
                member(id = "user-1", serverRole = "owner", effectiveChannelRole = "owner", canChangeChannelRole = false),
                viewer = "user-1",
            )
        )
    }

    @Test
    fun aMissingEffectiveRoleFallsBackToTheServerAndChannelRoles() {
        // Web: `effectiveChannelRole ?? (owner/admin || channelRole === "admin" ? "admin" : "member")`.
        assertEquals(
            ChannelMemberRoleAction.RemoveAdmin,
            action(member(effectiveChannelRole = null, channelRole = "admin")),
        )
        assertEquals(ChannelMemberRoleAction.MakeAdmin, action(member(effectiveChannelRole = null)))
        assertEquals(
            ChannelMemberRoleAction.ExplainProtectedDemote,
            action(member(serverRole = "admin", effectiveChannelRole = null, canChangeChannelRole = false)),
        )
        // No server role sent (another server's member): a plain member, never protected.
        assertNull(action(member(serverRole = null, effectiveChannelRole = null, canChangeChannelRole = false)))
    }
}
