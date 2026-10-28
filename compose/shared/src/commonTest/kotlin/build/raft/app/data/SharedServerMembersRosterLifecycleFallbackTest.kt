package build.raft.app.data

import build.raft.app.model.AgentStatus
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
 * Server-switch "agent reads offline" (artin, botiverse): an agent whose `/agents` row is
 * lifecycle `active` with no runtime `activity` normalizes to an unknown status, so the forced
 * roster refill after a presence-transport rebaseline projected nothing and the dot fell to the
 * neutral grey that reads as Offline. Web's no-live-activity fallback
 * (`agentStatusFallbackActivity`, ChannelMembers.tsx; `resolveAgentDisplayState`, agentStore.ts)
 * reads `active` as online; the roster projection now does the same.
 */
class SharedServerMembersRosterLifecycleFallbackTest {
    private val serverId = ServerId("server-1")
    private val owner =
        ProcessServerMembersOwner(
            environmentId = "local",
            accountId = "local",
            serverId = serverId.value
        )

    private val agentsJson =
        """
        [
          {"id":"agent-active","name":"active-idle","status":"active","serverId":"server-1"},
          {"id":"agent-stopped","name":"stopped","status":"stopped","serverId":"server-1"},
          {"id":"agent-working","name":"working","status":"active","activity":"working","serverId":"server-1"},
          {"id":"agent-bot","name":"bot","status":"active","managedByAppInstallId":"install-1","serverId":"server-1"},
          {"id":"agent-external","name":"ext","status":"active","runtime":"external","serverId":"server-1"},
          {"id":"agent-peer","name":"peer","status":"active","serverId":"server-2"}
        ]
        """.trimIndent()

    private fun storeWith(registry: ProcessMemberPresenceRegistry, logs: MutableList<String>): SharedServerMembersStore {
        val agents = assertNotNull(parseSharedSettingsAgentsAsMembers(agentsJson))
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId
                ): DataResult<MemberSnapshot> =
                    DataResult.Content(
                        MemberSnapshot(server = ServerSummary(id = serverId, name = "Raft"), members = agents)
                    )
            }
        return SharedServerMembersStore(repository, logger = { logs += it }, presenceRegistry = registry)
    }

    @Test
    fun forcedRefillAfterRebaselineProjectsActiveLifecycleWithoutActivityAsOnline() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val logs = mutableListOf<String>()
        val store = storeWith(registry, logs)
        registry.rebaselineTransport(owner, registry.writeGeneration, ProcessPresenceTransportEpoch("family-1", 1L))

        assertEquals(DataResult.Content(Unit), store.refreshMembers(serverId, force = true, reason = "presence_transport_connected"))

        val active = registry.factFor(owner, "agent-active")
        assertEquals(AgentStatus.Online, active?.agentStatus)
        assertEquals(Presence.Active, active?.presence)
        val status = store.currentMemberStatus(serverId, "agent-active")
        assertEquals(AgentStatus.Online, status?.status)
        assertEquals(Presence.Active, status?.presence)

        // `stopped` stays Offline; real runtime activity still wins over the fallback.
        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-stopped")?.agentStatus)
        assertEquals(AgentStatus.Working, registry.factFor(owner, "agent-working")?.agentStatus)
        // App bots (no presence on web) and external agents (lastSeenAt presence) get no fallback.
        assertNull(registry.factFor(owner, "agent-bot"))
        assertNull(registry.factFor(owner, "agent-external"))
        // Another server's agent is not this roster's to project.
        assertNull(registry.factFor(owner, "agent-peer"))

        val line = logs.single { it.startsWith("roster_presence_projection ") }
        assertTrue("serverId=server-1" in line, line)
        assertTrue("lifecycleFallback=1[agent-active]" in line, line)
        assertTrue("offline=1[agent-stopped]" in line, line)
        assertTrue("skipped=2[agent-bot:active,agent-external:active]" in line, line)
        assertTrue("filtered=1[agent-peer:server-2]" in line, line)
    }

    @Test
    fun realtimeOfflineAfterFallbackStillWins() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val store = storeWith(registry, mutableListOf())
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "presence_transport_connected")
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-active")?.agentStatus)

        registry.apply(
            owner = owner,
            event =
                build.raft.app.model.MemberPresenceUpdated(
                    memberId = build.raft.app.model.MemberId("agent-active"),
                    presence = Presence.Offline,
                    kind = build.raft.app.model.MemberKind.Agent,
                    agentStatus = AgentStatus.Offline,
                    serverSeq = 3L
                ),
            writeGeneration = registry.writeGeneration,
            transport = transport
        )
        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-active")?.agentStatus)
    }

    @Test
    fun parserKeepsActiveLifecycleUnknownOutsideTheRosterProjection() {
        val agent = assertNotNull(parseSharedSettingsAgentsAsMembers(agentsJson)).single { it.id.value == "agent-active" }
        assertNull(agent.agentStatus)
        assertEquals("active", agent.agentLifecycleStatus)
        assertNull(normalizedAgentRuntimeStatus(activity = null, lifecycleStatus = "active"))
    }
}
