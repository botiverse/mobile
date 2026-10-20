package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Web parity (`isLocalProjectionMember`, packages/web/src/utils/channelLocalMembership.ts):
 * a joint channel roster merges every server's projection, but an add only
 * writes this server's projection, so humans listed through another server stay
 * addable. Agents and same-server humans stay excluded.
 */
class ThreadAddMemberJointCandidatesTest {
    private val local = "server_local"
    private val peer = "server_peer"

    private val serverDirectory = listOf(
        RaftThreadMemberItem(id = "agent_a", displayName = "Agent A", kind = "agent", sourceServerId = local),
        RaftThreadMemberItem(id = "agent_b", displayName = "Agent B", kind = "agent", sourceServerId = local),
        RaftThreadMemberItem(id = "user_artin", displayName = "artin", kind = "human", sourceServerId = local),
        RaftThreadMemberItem(id = "user_local", displayName = "Local", kind = "human", sourceServerId = local),
        RaftThreadMemberItem(id = "user_new", displayName = "New", kind = "human", sourceServerId = local),
    )

    @Test
    fun jointRosterHumansFromPeerServerRemainAddableCandidates() {
        val jointRoster = listOf(
            RaftThreadMemberItem(id = "agent_a", displayName = "Agent A", kind = "agent", sourceServerId = peer),
            RaftThreadMemberItem(id = "user_artin", displayName = "artin", kind = "human", sourceServerId = peer),
            RaftThreadMemberItem(id = "user_local", displayName = "Local", kind = "human", sourceServerId = local),
        )

        val candidates = threadAddMemberCandidatesExcludingJoined(serverDirectory, jointRoster, local)

        assertEquals(
            listOf("agent:agent_b", "human:user_artin", "human:user_new"),
            candidates.map { "${it.kind}:${it.id}" }
        )
        assertEquals(
            setOf("agent:agent_a", "human:user_local"),
            threadAddMemberJoinedKeys(jointRoster, local)
        )
    }

    @Test
    fun ordinaryChannelRosterStillExcludesEveryExistingMember() {
        val roster = listOf(
            RaftThreadMemberItem(id = "agent_a", displayName = "Agent A", kind = "agent", sourceServerId = local),
            RaftThreadMemberItem(id = "user_artin", displayName = "artin", kind = "human", sourceServerId = local),
            RaftThreadMemberItem(id = "user_local", displayName = "Local", kind = "human", sourceServerId = null),
        )

        assertEquals(
            listOf("agent:agent_b", "human:user_new"),
            threadAddMemberCandidatesExcludingJoined(serverDirectory, roster, local).map { "${it.kind}:${it.id}" }
        )
        // Without a known local server every roster row counts as joined.
        assertEquals(
            listOf("agent:agent_b", "human:user_new"),
            threadAddMemberCandidatesExcludingJoined(
                serverDirectory,
                roster.map { it.copy(sourceServerId = peer) },
                localServerId = null
            ).map { "${it.kind}:${it.id}" }
        )
    }
}
