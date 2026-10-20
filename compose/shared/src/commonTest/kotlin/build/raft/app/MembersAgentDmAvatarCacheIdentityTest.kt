package build.raft.app

import build.raft.app.data.AgentDmSummary
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MembersAgentDmAvatarCacheIdentityTest {
    @Test
    fun dmPeerRequiresAnExactProfileFromTheOwningServer() {
        val peer = profile("peer-1", MemberKind.Human, "server-a")
        val state = state("server-a", peer)
        val row = AgentDmSummary(id = "dm-1", peerId = "peer-1", peerName = "Peer")

        val resolved = state.membersAgentDmPeerProfile(row, "server-a")
        assertEquals(peer, resolved)
        assertEquals(
            RaftMemberAvatarCacheIdentity(
                subject = RaftAvatarCacheSubject.Member(MemberKind.Human, "peer-1"),
                serverId = "server-a"
            ),
            resolved?.raftAvatarCacheIdentity("server-a")
        )
    }

    @Test
    fun dmPeerRejectsMissingOrCrossServerProfileIdentity() {
        val row = AgentDmSummary(id = "dm-1", peerId = "peer-1", peerName = "Peer")
        assertNull(SharedServerMembersState().membersAgentDmPeerProfile(row, "server-a"))
        assertNull(state("server-a", profile("peer-2", MemberKind.Human, "server-a"))
            .membersAgentDmPeerProfile(row, "server-a"))
        assertNull(state("server-a", profile("peer-1", MemberKind.Human, "server-b"))
            .membersAgentDmPeerProfile(row, "server-a"))
        assertNull(state("server-b", profile("peer-1", MemberKind.Human, "server-b"))
            .membersAgentDmPeerProfile(row, "server-a"))
        assertNull(profile("peer-1", MemberKind.Human, "server-b").raftAvatarCacheIdentity("server-a"))
        assertNull(
            state("server-a", profile("peer-1", MemberKind.Human, "server-a"))
                .membersAgentDmPeerProfile(row.copy(peerId = null), "server-a")
        )
        assertNull(
            state("server-a", profile("peer-1", MemberKind.Human, "server-a"))
                .membersAgentDmPeerProfile(row, null)
        )
    }

    @Test
    fun systemProfileNeverBecomesAPersistentDmAvatarIdentity() {
        val row = AgentDmSummary(id = "dm-1", peerId = "system-1", peerName = "System")
        val resolved =
            state("server-a", profile("system-1", MemberKind.System, "server-a"))
                .membersAgentDmPeerProfile(row, "server-a")

        assertEquals(MemberKind.System, resolved?.kind)
        assertNull(resolved?.raftAvatarCacheIdentity("server-a"))
    }

    private fun state(serverId: String, vararg members: MemberProfile): SharedServerMembersState =
        SharedServerMembersState(
            entriesByServerId =
                mapOf(
                    serverId to
                        SharedServerMembersEntry(
                            serverId = serverId,
                            members = members.toList()
                        )
                )
        )

    private fun profile(id: String, kind: MemberKind, serverId: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = id,
            handle = id,
            kind = kind,
            serverId = ServerId(serverId)
        )
}
