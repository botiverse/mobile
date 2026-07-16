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
        registry.apply(ownerServerA, fact(serverSeq = 5, presence = Presence.Offline, status = AgentStatus.Offline))
        registry.apply(ownerServerA, fact(serverSeq = 3, presence = Presence.Active, status = AgentStatus.Online))

        assertEquals(Presence.Offline, registry.factFor(ownerServerA, "agent-1")?.presence)
        assertEquals(5L, registry.factFor(ownerServerA, "agent-1")?.serverSeq)
    }

    @Test
    fun sameVisiblePresenceAdvancesTheSeqWatermark() {
        // QA blocker contract: equal visible presence must still advance the
        // canonical fact/watermark, otherwise an out-of-order older event can
        // pass the >= gate later and roll the status backwards.
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(serverSeq = 1, status = AgentStatus.Working, presence = Presence.Active))
        registry.apply(ownerServerA, fact(serverSeq = 2, status = AgentStatus.Working, presence = Presence.Active))

        assertEquals(2L, registry.factFor(ownerServerA, "agent-1")?.serverSeq)
    }

    @Test
    fun outOfOrderOlderDifferentStatusIsRejectedAfterSameVisibleAdvance() {
        // QA regression pin: seq1 online → seq3 online (same visible) →
        // late seq2 offline ⇒ final ONLINE, watermark 3.
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(serverSeq = 1, status = AgentStatus.Online, presence = Presence.Active))
        registry.apply(ownerServerA, fact(serverSeq = 3, status = AgentStatus.Online, presence = Presence.Active))
        registry.apply(ownerServerA, fact(serverSeq = 2, status = AgentStatus.Offline, presence = Presence.Offline))

        val final = registry.factFor(ownerServerA, "agent-1")
        assertEquals(AgentStatus.Online, final?.agentStatus)
        assertEquals(3L, final?.serverSeq)
    }

    @Test
    fun projectionNeverDisplacesRealtimeFact() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(serverSeq = 7, presence = Presence.Offline, status = AgentStatus.Offline))
        registry.applyProjection(
            ownerServerA,
            fact(serverSeq = null, presence = Presence.Active, status = AgentStatus.Working)
        )

        assertEquals(Presence.Offline, registry.factFor(ownerServerA, "agent-1")?.presence)
    }

    // ---- Cindy's isolation matrix (task #136 gates) ----

    @Test
    fun sameMemberIdOnTwoServersNeverCrossOverrides() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(presence = Presence.Offline, status = AgentStatus.Offline, serverSeq = 4))
        registry.apply(ownerServerB, fact(presence = Presence.Active, status = AgentStatus.Working, serverSeq = 9))

        assertEquals(Presence.Offline, registry.factFor(ownerServerA, "agent-1")?.presence)
        assertEquals(Presence.Active, registry.factFor(ownerServerB, "agent-1")?.presence)
    }

    @Test
    fun evictOnOneServerDoesNotDeleteTheOtherServersFact() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact())
        registry.apply(ownerServerB, fact())

        registry.evict(ownerServerA, "agent-1")

        assertNull(registry.factFor(ownerServerA, "agent-1"))
        assertEquals(Presence.Active, registry.factFor(ownerServerB, "agent-1")?.presence)
    }

    @Test
    fun accountsAreIsolatedForTheSameServerAndMember()  {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact(presence = Presence.Offline, status = AgentStatus.Offline))
        registry.apply(ownerOtherAccount, fact(presence = Presence.Active, status = AgentStatus.Online))

        assertEquals(Presence.Offline, registry.factFor(ownerServerA, "agent-1")?.presence)
        assertEquals(Presence.Active, registry.factFor(ownerOtherAccount, "agent-1")?.presence)
    }

    @Test
    fun writeEdgeFreezesContextPerDeliveryAndDropsWhenSignedOut() {
        // Review blocker ④: the sink resolves the owner from ONE atomic
        // context snapshot per delivery — null between Cleared and the next
        // Authenticated, so late old-auth events are dropped and can never be
        // attributed to the account that signs in afterwards. This exercises
        // the production ownerFor derivation the provider sink uses.
        val registry = ProcessMemberPresenceRegistry()
        var context: ProcessPresenceAuthContext? =
            ProcessPresenceAuthContext(environmentId = "env-1", accountId = "acct-A")
        fun deliver(serverId: String, event: MemberPresenceUpdated) {
            context?.ownerFor(serverId)?.let { registry.apply(it, event) }
        }

        deliver("server-a", fact(presence = Presence.Active, status = AgentStatus.Online))
        val ownerA = ProcessServerMembersOwner("env-1", "acct-A", "server-a")
        assertEquals(AgentStatus.Online, registry.factFor(ownerA, "agent-1")?.agentStatus)

        // Signed out: context null + partitions cleared → late event dropped.
        context = null
        registry.clearAll(reason = "cleared")
        deliver("server-a", fact(presence = Presence.Offline, status = AgentStatus.Offline))
        assertEquals(emptyMap(), registry.factsByOwner.value)

        // Signed into B: fresh deliveries land in B's partition only.
        context = ProcessPresenceAuthContext(environmentId = "env-1", accountId = "acct-B")
        deliver("server-a", fact(presence = Presence.Active, status = AgentStatus.Working))
        val ownerB = ProcessServerMembersOwner("env-1", "acct-B", "server-a")
        assertEquals(AgentStatus.Working, registry.factFor(ownerB, "agent-1")?.agentStatus)
        assertNull(registry.factFor(ownerA, "agent-1"))
    }

    @Test
    fun authTransitionClearsEveryPartition() {
        val registry = ProcessMemberPresenceRegistry()
        registry.apply(ownerServerA, fact())
        registry.apply(ownerServerB, fact())
        registry.apply(ownerOtherAccount, fact())

        registry.clearAll(reason = "cleared")

        assertNull(registry.factFor(ownerServerA, "agent-1"))
        assertNull(registry.factFor(ownerServerB, "agent-1"))
        assertNull(registry.factFor(ownerOtherAccount, "agent-1"))
        assertEquals(emptyMap(), registry.factsByOwner.value)
    }
}
