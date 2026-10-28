package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server-switch "agent reads offline" (artin, botiverse): a presence-transport rebaseline used to
 * wipe the owner's facts, so an agent the forced roster refill could not restate (lifecycle
 * `active`, no runtime activity) fell to the neutral grey that reads as Offline. Tenny's option 1
 * (PR #3856): carry the last known fact over the advance at lowest precedence instead.
 */
class SharedServerMembersRebaselineCarryOverTest {
    private val serverId = ServerId("server-1")
    private val owner =
        ProcessServerMembersOwner(
            environmentId = "local",
            accountId = "local",
            serverId = serverId.value
        )
    private val oldTransport = ProcessPresenceTransportEpoch("family-1", 1L)
    private val newTransport = ProcessPresenceTransportEpoch("family-1", 2L)

    private fun agentsJson(agentAStatus: String = "active") =
        """
        [
          {"id":"agent-a","name":"a","status":"$agentAStatus","serverId":"server-1"},
          {"id":"agent-never","name":"never","status":"active","serverId":"server-1"},
          {"id":"agent-peer","name":"peer","status":"active","serverId":"server-2"}
        ]
        """.trimIndent()

    private fun storeWith(
        registry: ProcessMemberPresenceRegistry,
        logs: MutableList<String> = mutableListOf(),
        agentAStatus: () -> String = { "active" }
    ): SharedServerMembersStore {
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId
                ): DataResult<MemberSnapshot> =
                    DataResult.Content(
                        MemberSnapshot(
                            server = ServerSummary(id = serverId, name = "Raft"),
                            members = assertNotNull(parseSharedSettingsAgentsAsMembers(agentsJson(agentAStatus())))
                        )
                    )
            }
        return SharedServerMembersStore(repository, logger = { logs += it }, presenceRegistry = registry)
    }

    private fun activity(memberId: String, status: AgentStatus, serverSeq: Long?) =
        MemberPresenceUpdated(
            memberId = MemberId(memberId),
            presence = status.presence,
            kind = MemberKind.Agent,
            agentStatus = status,
            statusLabel = status.label,
            serverSeq = serverSeq
        )

    /** agent-a was seen Online (seq 10) on the old transport, then the transport advanced. */
    private fun switchWithKnownOnline(registry: ProcessMemberPresenceRegistry) {
        registry.rebaselineTransport(owner, registry.writeGeneration, oldTransport)
        registry.apply(owner, activity("agent-a", AgentStatus.Online, 10L), registry.writeGeneration, oldTransport)
        registry.rebaselineTransport(owner, registry.writeGeneration, newTransport)
    }

    @Test
    fun refillWithoutUsableStatusKeepsPreSwitchOnline() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val logs = mutableListOf<String>()
        val store = storeWith(registry, logs)
        switchWithKnownOnline(registry)

        assertEquals(DataResult.Content(Unit), store.refreshMembers(serverId, force = true, reason = "presence_transport_connected"))

        val fact = registry.factFor(owner, "agent-a")
        assertEquals(AgentStatus.Online, fact?.agentStatus)
        assertNull(fact?.serverSeq, "the old epoch's seq is no floor for the new transport")
        assertEquals(setOf("agent-a"), registry.carriedOverMemberIds(owner))
        val status = store.currentMemberStatus(serverId, "agent-a")
        assertEquals(AgentStatus.Online, status?.status)
        assertEquals(Presence.Active, status?.presence)

        val line = logs.single { it.startsWith("roster_presence_projection ") }
        assertTrue("retained=1[agent-a]" in line, line)
        assertTrue("skipped=2[agent-a:active,agent-never:active]" in line, line)
        assertTrue("filtered=1[agent-peer:server-2]" in line, line)
    }

    @Test
    fun postRebaselineRealtimeOfflineBeatsCarriedFact() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val store = storeWith(registry)
        switchWithKnownOnline(registry)
        store.refreshMembers(serverId, force = true, reason = "presence_transport_connected")

        // A restarted low seq on the new transport still wins over the carried seq-10 fact.
        registry.apply(owner, activity("agent-a", AgentStatus.Offline, 1L), registry.writeGeneration, newTransport)

        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-a")?.agentStatus)
        assertEquals(1L, registry.factFor(owner, "agent-a")?.serverSeq)
        assertTrue(registry.carriedOverMemberIds(owner).isEmpty())
        assertEquals(AgentStatus.Offline, store.currentMemberStatus(serverId, "agent-a")?.status)
    }

    @Test
    fun oldTransportEventAfterRebaselineCannotReplaceCarriedFact() {
        val registry = ProcessMemberPresenceRegistry()
        switchWithKnownOnline(registry)

        registry.apply(owner, activity("agent-a", AgentStatus.Offline, 11L), registry.writeGeneration, oldTransport)

        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-a")?.agentStatus)
        assertEquals(setOf("agent-a"), registry.carriedOverMemberIds(owner))
    }

    @Test
    fun rosterProjectionWithRealStatusOverwritesCarriedFact() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val logs = mutableListOf<String>()
        val store = storeWith(registry, logs, agentAStatus = { "stopped" })
        switchWithKnownOnline(registry)

        store.refreshMembers(serverId, force = true, reason = "presence_transport_connected")

        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-a")?.agentStatus)
        assertTrue(registry.carriedOverMemberIds(owner).isEmpty())
        val line = logs.single { it.startsWith("roster_presence_projection ") }
        assertTrue("retained=0 " in line, line)
        assertTrue("offline=1[agent-a]" in line, line)
    }

    /** artin #triage:#97: no fact + lifecycle `active` reads "Likely online", display only. */
    @Test
    fun neverSeenActiveAgentReadsLikelyOnlineWithoutAFact() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val store = storeWith(registry)
        switchWithKnownOnline(registry)
        store.refreshMembers(serverId, force = true, reason = "presence_transport_connected")

        assertNull(registry.factFor(owner, "agent-never"), "the guess is never written as a fact")
        val status = assertNotNull(store.currentMemberStatus(serverId, "agent-never"))
        assertNull(status.status)
        assertTrue(status.likelyOnline)
        assertEquals(Presence.Active, status.presence)
        assertEquals(LikelyOnlineAgentStatusLabel, status.statusLabel)
    }

    @Test
    fun parserKeepsActiveLifecycleUnknown() {
        val agent = assertNotNull(parseSharedSettingsAgentsAsMembers(agentsJson())).single { it.id.value == "agent-a" }
        assertNull(agent.agentStatus)
        assertEquals("active", agent.agentLifecycleStatus)
    }
}
