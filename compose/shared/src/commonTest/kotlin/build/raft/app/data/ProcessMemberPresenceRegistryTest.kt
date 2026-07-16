package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessMemberPresenceRegistryTest {

    private fun fact(
        memberId: String = "agent-1",
        presence: Presence = Presence.Active,
        status: AgentStatus? = AgentStatus.Online,
        serverSeq: Long? = null
    ) = MemberPresenceUpdated(
        memberId = MemberId(memberId),
        presence = presence,
        kind = MemberKind.Agent,
        agentStatus = status,
        serverSeq = serverSeq
    )

    @Test
    fun seqNeverGoesBackwards() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(fact(serverSeq = 5, presence = Presence.Offline, status = AgentStatus.Offline))
        registry.apply(fact(serverSeq = 3, presence = Presence.Active, status = AgentStatus.Online))

        assertEquals(Presence.Offline, registry.factFor("agent-1")?.presence)
        assertEquals(5L, registry.factFor("agent-1")?.serverSeq)
    }

    @Test
    fun projectionNeverDisplacesRealtimeFact() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(fact(serverSeq = 7, presence = Presence.Offline, status = AgentStatus.Offline))
        registry.applyProjection(fact(serverSeq = null, presence = Presence.Active, status = AgentStatus.Working))

        assertEquals(Presence.Offline, registry.factFor("agent-1")?.presence)
    }

    @Test
    fun evictRemovesTheFactForDeletedMembers() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(fact())
        registry.evict("agent-1")

        assertNull(registry.factFor("agent-1"))
    }

    @Test
    fun twoScopeStoresSharingTheRegistryConvergeOnOneTruth() {
        // task #136 "真正的 SSOT" lock: a presence fact written through ONE
        // shell's path is visible in EVERY scope store sharing the registry —
        // machine-offline can never again be a per-shell truth.
        val registry = ProcessMemberPresenceRegistry()
        val offline = fact(serverSeq = 9, presence = Presence.Offline, status = AgentStatus.Offline)

        registry.apply(offline)

        assertEquals(offline, registry.factFor("agent-1"))
        assertEquals(mapOf("agent-1" to offline), registry.facts.value)
    }
}
