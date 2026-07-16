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

    // A fresh registry starts at write generation 0; tests that never clear
    // stamp their writes with it.
    private val GEN0 = 0L

    private val ownerServerA =
        ProcessServerMembersOwner(environmentId = "env-1", accountId = "acct-1", serverId = "server-a")
    private val ownerServerB =
        ProcessServerMembersOwner(environmentId = "env-1", accountId = "acct-1", serverId = "server-b")
    private val ownerOtherAccount =
        ProcessServerMembersOwner(environmentId = "env-1", accountId = "acct-2", serverId = "server-a")

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
    fun seqNeverGoesBackwardsWithinAnOwner() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(serverSeq = 5, presence = Presence.Offline, status = AgentStatus.Offline), GEN0)
        registry.apply(ownerServerA, fact(serverSeq = 3, presence = Presence.Active, status = AgentStatus.Online), GEN0)

        assertEquals(Presence.Offline, registry.factFor(ownerServerA, "agent-1")?.presence)
        assertEquals(5L, registry.factFor(ownerServerA, "agent-1")?.serverSeq)
    }

    @Test
    fun sameVisiblePresenceAdvancesTheSeqWatermark() {
        // QA blocker contract: equal visible presence must still advance the
        // canonical fact/watermark, otherwise an out-of-order older event can
        // pass the >= gate later and roll the status backwards.
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(serverSeq = 1, status = AgentStatus.Working, presence = Presence.Active), GEN0)
        registry.apply(ownerServerA, fact(serverSeq = 2, status = AgentStatus.Working, presence = Presence.Active), GEN0)

        assertEquals(2L, registry.factFor(ownerServerA, "agent-1")?.serverSeq)
    }

    @Test
    fun outOfOrderOlderDifferentStatusIsRejectedAfterSameVisibleAdvance() {
        // QA regression pin: seq1 online → seq3 online (same visible) →
        // late seq2 offline ⇒ final ONLINE, watermark 3.
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(serverSeq = 1, status = AgentStatus.Online, presence = Presence.Active), GEN0)
        registry.apply(ownerServerA, fact(serverSeq = 3, status = AgentStatus.Online, presence = Presence.Active), GEN0)
        registry.apply(ownerServerA, fact(serverSeq = 2, status = AgentStatus.Offline, presence = Presence.Offline), GEN0)

        val final = registry.factFor(ownerServerA, "agent-1")
        assertEquals(AgentStatus.Online, final?.agentStatus)
        assertEquals(3L, final?.serverSeq)
    }

    @Test
    fun projectionNeverDisplacesRealtimeFact() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(serverSeq = 7, presence = Presence.Offline, status = AgentStatus.Offline), GEN0)
        registry.applyProjection(
            ownerServerA,
            fact(serverSeq = null, presence = Presence.Active, status = AgentStatus.Working),
            GEN0
        )

        assertEquals(Presence.Offline, registry.factFor(ownerServerA, "agent-1")?.presence)
    }

    // ---- Cindy's isolation matrix (task #136 gates) ----

    @Test
    fun sameMemberIdOnTwoServersNeverCrossOverrides() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(presence = Presence.Offline, status = AgentStatus.Offline, serverSeq = 4), GEN0)
        registry.apply(ownerServerB, fact(presence = Presence.Active, status = AgentStatus.Working, serverSeq = 9), GEN0)

        assertEquals(Presence.Offline, registry.factFor(ownerServerA, "agent-1")?.presence)
        assertEquals(Presence.Active, registry.factFor(ownerServerB, "agent-1")?.presence)
    }

    @Test
    fun evictOnOneServerDoesNotDeleteTheOtherServersFact() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(), GEN0)
        registry.apply(ownerServerB, fact(), GEN0)

        registry.evict(ownerServerA, "agent-1")

        assertNull(registry.factFor(ownerServerA, "agent-1"))
        assertEquals(Presence.Active, registry.factFor(ownerServerB, "agent-1")?.presence)
    }

    @Test
    fun accountsAreIsolatedForTheSameServerAndMember()  {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(presence = Presence.Offline, status = AgentStatus.Offline), GEN0)
        registry.apply(ownerOtherAccount, fact(presence = Presence.Active, status = AgentStatus.Online), GEN0)

        assertEquals(Presence.Offline, registry.factFor(ownerServerA, "agent-1")?.presence)
        assertEquals(Presence.Active, registry.factFor(ownerOtherAccount, "agent-1")?.presence)
    }

    @Test
    fun clearAtomicallyRetiresTheGenerationAndRejectsHeldWrites() {
        // Post-merge audit blocker on PR #1054: clearAll used to assign empty
        // OUTSIDE the CAS loop, so an apply that lost the race could retry
        // against the cleared map and resurrect a retired fact. Now clear and
        // generation advance are one atomic swap and every apply re-checks
        // the generation INSIDE its CAS attempt.
        val registry = ProcessMemberPresenceRegistry()
        val heldGeneration = registry.writeGeneration
        registry.apply(ownerServerA, fact(serverSeq = 5, status = AgentStatus.Working), heldGeneration)
        assertEquals(AgentStatus.Working, registry.factFor(ownerServerA, "agent-1")?.agentStatus)

        val newGeneration = registry.clearAll(reason = "authenticated")
        assertEquals(heldGeneration + 1L, newGeneration)
        assertEquals(newGeneration, registry.writeGeneration)

        // A held write stamped with the retired generation is rejected even
        // though the partition is empty — no resurrection.
        registry.apply(ownerServerA, fact(serverSeq = 9, status = AgentStatus.Offline), heldGeneration)
        assertNull(registry.factFor(ownerServerA, "agent-1"))
        assertEquals(emptyMap(), registry.factsByOwner)

        // A write stamped with the CURRENT generation lands normally.
        registry.apply(ownerServerA, fact(serverSeq = 1, status = AgentStatus.Online), newGeneration)
        assertEquals(AgentStatus.Online, registry.factFor(ownerServerA, "agent-1")?.agentStatus)
    }

    @Test
    fun retiredGenerationProjectionIsAlsoRejected() {
        val registry = ProcessMemberPresenceRegistry()
        val heldGeneration = registry.writeGeneration
        registry.clearAll(reason = "cleared")

        registry.applyProjection(
            ownerServerA,
            fact(serverSeq = null, presence = Presence.Active, status = AgentStatus.Working),
            heldGeneration
        )

        assertEquals(emptyMap(), registry.factsByOwner)
    }

    @Test
    fun authTransitionClearsEveryPartition() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(), GEN0)
        registry.apply(ownerServerB, fact(), GEN0)
        registry.apply(ownerOtherAccount, fact(), GEN0)

        registry.clearAll(reason = "cleared")

        assertNull(registry.factFor(ownerServerA, "agent-1"))
        assertNull(registry.factFor(ownerServerB, "agent-1"))
        assertNull(registry.factFor(ownerOtherAccount, "agent-1"))
        assertEquals(emptyMap(), registry.factsByOwner)
    }
}
