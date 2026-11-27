package build.raft.app

import build.raft.app.data.ResolvedActor
import build.raft.app.memberDetailIdentity
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.thread.withJointAuthorHomeServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Web AgentDetailPanel `isRemoteJointAgent`: the limited profile of another server's agent. */
class MembersRemoteAgentProfileTest {
    private val agentId = MemberId("agent-partner")

    private fun actor(
        id: String = agentId.value,
        kind: MemberKind = MemberKind.Agent,
        home: String = "server-partner"
    ) = ResolvedActor(
        kind = kind,
        id = id,
        homeServerId = home,
        name = "ruby",
        displayName = "Ruby",
        avatarUrl = null,
        gravatarHash = null
    )

    @Test
    fun jointAuthorHomeServerMakesTheProfileRemote() {
        val identity = memberDetailIdentity(agentId.value, MemberKind.Agent)!!
        val opened = identity.withJointAuthorHomeServer(actor())

        assertEquals(ServerId("server-partner"), opened.sourceServerId)
        assertEquals(MemberDetailAccessMode.RemoteProfileOnly, opened.memberDetailAccessMode("server-home"))
        assertTrue(membersAgentProfileIsRemote(opened.memberDetailAccessMode("server-home")))
        // A sender of this server (no actor row), or the viewer's own server, stays local.
        assertEquals(MemberDetailAccessMode.LocalFull, identity.withJointAuthorHomeServer(null).memberDetailAccessMode("server-home"))
        assertEquals(
            MemberDetailAccessMode.LocalFull,
            identity.withJointAuthorHomeServer(actor(home = "server-home")).memberDetailAccessMode("server-home")
        )
    }

    @Test
    fun jointAuthorNeverOverridesARosterSourceOrAnotherActor() {
        val fromRoster = memberDetailIdentity(agentId.value, MemberKind.Agent, "server-roster")!!
        assertSame(fromRoster, fromRoster.withJointAuthorHomeServer(actor()))

        val identity = memberDetailIdentity(agentId.value, MemberKind.Agent)!!
        assertNull(identity.withJointAuthorHomeServer(actor(id = "agent-other")).sourceServerId)
        assertNull(identity.withJointAuthorHomeServer(actor(kind = MemberKind.Human)).sourceServerId)
    }

    @Test
    fun remoteProfileShowsProfileTabOnly() {
        assertEquals(
            listOf(MembersAgentDetailTab.Profile),
            membersAgentDetailTabsForAccess(MemberDetailAccessMode.RemoteProfileOnly, canViewPrivateSurfaces = true, deleted = false)
        )
        assertFalse(membersAgentProfileIsRemote(MemberDetailAccessMode.LocalFull))
    }

    @Test
    fun fromRowNamesTheServerNeverItsId() {
        val member = MemberProfile(id = agentId, displayName = "Ruby", handle = "ruby", kind = MemberKind.Agent)
        assertEquals(
            "Partner Labs",
            membersRemoteAgentSourceServerName(member.copy(serverId = ServerId("s1"), serverName = "Partner Labs"))
        )
        assertEquals("partner", membersRemoteAgentSourceServerName(member.copy(serverId = ServerId("s1"), serverSlug = "partner")))
        assertNull(membersRemoteAgentSourceServerName(member.copy(serverId = ServerId("s1"), serverName = "s1")))
        assertNull(membersRemoteAgentSourceServerName(member))
    }
}
