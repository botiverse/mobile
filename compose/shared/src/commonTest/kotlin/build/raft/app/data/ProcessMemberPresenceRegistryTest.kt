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
    fun coarseStateNeverReplacesTheDetailedFrameItCameFromButAStrictlyNewerOneDoes() {
        // agent:activity_state shares agent:activity's serverSeq counter (botiverse/slock 968d102d).
        val registry = ProcessMemberPresenceRegistry()
        val detailed = fact(serverSeq = 10, status = AgentStatus.Thinking).copy(statusLabel = "Planning")
        registry.apply(ownerServerA, detailed, GEN0)

        // The coarse state of that very frame (same seq) and an older one keep the labelled fact.
        registry.apply(ownerServerA, fact(serverSeq = 10, status = AgentStatus.Working).copy(coarseState = true), GEN0)
        registry.apply(ownerServerA, fact(serverSeq = 9, status = AgentStatus.Offline, presence = Presence.Offline).copy(coarseState = true), GEN0)
        assertEquals(detailed, registry.factFor(ownerServerA, "agent-1"))

        // A strictly newer coarse state (the agent is no longer watched in detail) moves the dot.
        registry.apply(ownerServerA, fact(serverSeq = 12, status = AgentStatus.Offline, presence = Presence.Offline).copy(coarseState = true), GEN0)
        val coarse = registry.factFor(ownerServerA, "agent-1")
        assertEquals(AgentStatus.Offline, coarse?.agentStatus)
        assertNull(coarse?.statusLabel)
        assertEquals(12L, coarse?.serverSeq)

        // A detailed frame with the coarse fact's seq still replaces it (it carries more); an older one does not.
        registry.apply(ownerServerA, fact(serverSeq = 11, status = AgentStatus.Working), GEN0)
        assertEquals(12L, registry.factFor(ownerServerA, "agent-1")?.serverSeq)
        val sameSeqDetail = fact(serverSeq = 12, status = AgentStatus.Offline, presence = Presence.Offline).copy(statusLabel = "Stopped")
        registry.apply(ownerServerA, sameSeqDetail, GEN0)
        assertEquals(sameSeqDetail, registry.factFor(ownerServerA, "agent-1"))
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

    // ---- task #137(a): seq-less realtime demoted to projection semantics ----

    @Test
    fun seqLessRealtimeNeverDisplacesSeqCarryingFact() {
        // Wire contract: realtime presence always carries serverSeq, so a
        // seq-less realtime event is non-contract (old server / malformed)
        // and must NOT roll a newer seq-carrying fact backwards (the legacy
        // else→true rule allowed exactly that).
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(serverSeq = 5, presence = Presence.Offline, status = AgentStatus.Offline), GEN0)
        registry.apply(ownerServerA, fact(serverSeq = null, presence = Presence.Active, status = AgentStatus.Working), GEN0)

        val final = registry.factFor(ownerServerA, "agent-1")
        assertEquals(Presence.Offline, final?.presence)
        assertEquals(5L, final?.serverSeq)
    }

    @Test
    fun seqLessRealtimeStillFillsSeqLessSlots() {
        // The demotion only protects seq-carrying facts: gap-fill behavior
        // among seq-less writes is unchanged.
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(serverSeq = null, status = AgentStatus.Working), GEN0)
        assertEquals(AgentStatus.Working, registry.factFor(ownerServerA, "agent-1")?.agentStatus)

        registry.apply(ownerServerA, fact(serverSeq = null, status = AgentStatus.Offline, presence = Presence.Offline), GEN0)
        assertEquals(AgentStatus.Offline, registry.factFor(ownerServerA, "agent-1")?.agentStatus)

        // … and a later seq-carrying event still displaces the seq-less slot,
        // after which further seq-less events are rejected again.
        registry.apply(ownerServerA, fact(serverSeq = 9, status = AgentStatus.Online), GEN0)
        assertEquals(AgentStatus.Online, registry.factFor(ownerServerA, "agent-1")?.agentStatus)
        registry.apply(ownerServerA, fact(serverSeq = null, status = AgentStatus.Offline, presence = Presence.Offline), GEN0)
        assertEquals(AgentStatus.Online, registry.factFor(ownerServerA, "agent-1")?.agentStatus)
    }

    // ---- task #137(b): projection freshness-evidence watermark ----

    @Test
    fun projectionWithOlderEvidenceDoesNotDisplaceNewerEvidence() {
        // Two racing REST snapshots for the same account: the late-arriving
        // STALE one (older server timestamp) must not roll back the fresher
        // projection.
        val registry = ProcessMemberPresenceRegistry()
        registry.applyProjection(
            ownerServerA,
            fact(serverSeq = null, status = AgentStatus.Working),
            GEN0,
            evidenceTimestamp = 200L
        )
        registry.applyProjection(
            ownerServerA,
            fact(serverSeq = null, status = AgentStatus.Offline, presence = Presence.Offline),
            GEN0,
            evidenceTimestamp = 100L
        )

        assertEquals(AgentStatus.Working, registry.factFor(ownerServerA, "agent-1")?.agentStatus)
    }

    @Test
    fun projectionWithEqualOrNewerEvidenceAppliesAndAdvancesWatermark() {
        val registry = ProcessMemberPresenceRegistry()
        registry.applyProjection(ownerServerA, fact(status = AgentStatus.Working), GEN0, evidenceTimestamp = 200L)
        // Equal evidence re-applies (>= rule, same as serverSeq).
        registry.applyProjection(ownerServerA, fact(status = AgentStatus.Online), GEN0, evidenceTimestamp = 200L)
        assertEquals(AgentStatus.Online, registry.factFor(ownerServerA, "agent-1")?.agentStatus)

        // Watermark is now 200: older evidence is rejected …
        registry.applyProjection(
            ownerServerA,
            fact(status = AgentStatus.Offline, presence = Presence.Offline),
            GEN0,
            evidenceTimestamp = 150L
        )
        assertEquals(AgentStatus.Online, registry.factFor(ownerServerA, "agent-1")?.agentStatus)

        // … newer evidence applies.
        registry.applyProjection(
            ownerServerA,
            fact(status = AgentStatus.Offline, presence = Presence.Offline),
            GEN0,
            evidenceTimestamp = 300L
        )
        assertEquals(AgentStatus.Offline, registry.factFor(ownerServerA, "agent-1")?.agentStatus)
    }

    @Test
    fun unevidencedProjectionFillsOnlyUntilEvidenceExists() {
        // Evidence-less fallbacks (detail summary / current-activity entry)
        // keep their gap-fill role, but once evidenced freshness exists they
        // can no longer displace it.
        val registry = ProcessMemberPresenceRegistry()
        registry.applyProjection(ownerServerA, fact(status = AgentStatus.Working), GEN0)
        assertEquals(AgentStatus.Working, registry.factFor(ownerServerA, "agent-1")?.agentStatus)

        registry.applyProjection(ownerServerA, fact(status = AgentStatus.Online), GEN0, evidenceTimestamp = 100L)
        assertEquals(AgentStatus.Online, registry.factFor(ownerServerA, "agent-1")?.agentStatus)

        registry.applyProjection(ownerServerA, fact(status = AgentStatus.Offline, presence = Presence.Offline), GEN0)
        assertEquals(AgentStatus.Online, registry.factFor(ownerServerA, "agent-1")?.agentStatus)
    }

    @Test
    fun projectionEvidenceSurvivesClearAll() {
        // The watermark is an epoch-millis server timestamp: a stale
        // pre-clear in-flight snapshot landing after an auth transition is
        // still stale for the recurring owner.
        val registry = ProcessMemberPresenceRegistry()
        registry.applyProjection(ownerServerA, fact(status = AgentStatus.Working), GEN0, evidenceTimestamp = 200L)

        val newGeneration = registry.clearAll(reason = "authenticated")
        assertNull(registry.factFor(ownerServerA, "agent-1"))

        registry.applyProjection(
            ownerServerA,
            fact(status = AgentStatus.Offline, presence = Presence.Offline),
            newGeneration,
            evidenceTimestamp = 100L
        )
        assertNull(registry.factFor(ownerServerA, "agent-1"))

        registry.applyProjection(
            ownerServerA,
            fact(status = AgentStatus.Online),
            newGeneration,
            evidenceTimestamp = 300L
        )
        assertEquals(AgentStatus.Online, registry.factFor(ownerServerA, "agent-1")?.agentStatus)
    }

    @Test
    fun projectionEvidenceAdvancesWithoutVisibleChurn() {
        // Same-visible newer evidence must still advance the watermark
        // (mirrors the seq watermark doctrine): otherwise an older stale
        // snapshot would pass the gate later.
        val registry = ProcessMemberPresenceRegistry()
        registry.applyProjection(ownerServerA, fact(status = AgentStatus.Working), GEN0, evidenceTimestamp = 100L)
        registry.applyProjection(ownerServerA, fact(status = AgentStatus.Working), GEN0, evidenceTimestamp = 200L)

        registry.applyProjection(
            ownerServerA,
            fact(status = AgentStatus.Offline, presence = Presence.Offline),
            GEN0,
            evidenceTimestamp = 150L
        )

        assertEquals(AgentStatus.Working, registry.factFor(ownerServerA, "agent-1")?.agentStatus)
    }
}
