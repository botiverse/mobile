package build.raft.app

import build.raft.app.data.AgentDmSummary
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.members.membersAgentDmPeerProfile
import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals

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
