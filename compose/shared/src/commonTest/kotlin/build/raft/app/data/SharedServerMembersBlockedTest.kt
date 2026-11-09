package build.raft.app.data

import build.raft.app.RaftColor
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.toRaftAgentStatusIndicatorState
import build.raft.app.withoutActivityDetail
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * botiverse/slock #13575: the agent list's `blockedReason` `computer_access_revoked` (its Computer's
 * key was revoked; the agent stays bound and gets no wakes). Web `resolveAgentDisplayState` reads it
 * before any activity: error orange, "Computer access revoked: move the agent…", never "Likely
 * online" nor its last activity. An unknown reason keeps the ordinary state.
 */
class SharedServerMembersBlockedTest {
    private val serverId = ServerId("server-1")
    private val owner = ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = serverId.value)
    private val transport = ProcessPresenceTransportEpoch("family-1", 1L)

    // botiverse/slock routes/agents.ts buildAgentListItems: `blockedReason: agentBlockedReason(...)`.
    private val agentsJson =
        """
        [
          {"id":"agent-revoked","name":"revoked","status":"active","machineId":"m-1","blockedReason":"computer_access_revoked","serverId":"server-1"},
          {"id":"agent-old","name":"old","status":"active","machineId":"m-2","blockedReason":"computer_version_unsupported","serverId":"server-1"},
          {"id":"agent-future","name":"future","status":"active","machineId":"m-3","blockedReason":"some_future_reason","serverId":"server-1"},
          {"id":"agent-ext","name":"ext","status":"active","runtime":"external","blockedReason":"computer_access_revoked","serverId":"server-1"},
          {"id":"agent-bot","name":"bot","status":"active","managedByAppInstallId":"install-1","blockedReason":"computer_access_revoked","serverId":"server-1"},
          {"id":"agent-plain","name":"plain","status":"active","blockedReason":null,"serverId":"server-1"}
        ]
        """.trimIndent()

    private fun store(registry: ProcessMemberPresenceRegistry): SharedServerMembersStore {
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> =
                    DataResult.Content(
                        MemberSnapshot(
                            server = ServerSummary(id = serverId, name = "Raft"),
                            members = assertNotNull(parseSharedSettingsAgentsAsMembers(agentsJson))
                        )
                    )
            }
        return SharedServerMembersStore(repository, presenceRegistry = registry)
    }

    @Test
    fun theAgentListCarriesTheReasonRaw() {
        val members = assertNotNull(parseSharedSettingsAgentsAsMembers(agentsJson)).associateBy { it.id.value }
        assertEquals("computer_access_revoked", members.getValue("agent-revoked").agentBlockedReason)
        assertEquals("computer_version_unsupported", members.getValue("agent-old").agentBlockedReason)
        assertNull(members.getValue("agent-plain").agentBlockedReason)
        // An incremental-sync `agent` row is a `GET /agents` item and carries it the same way.
        val row = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"agent-revoked","name":"revoked","status":"active","blockedReason":"computer_access_revoked"}"""
        ) as kotlinx.serialization.json.JsonObject
        val synced = parseSharedAgentRow(row) as SharedSyncedAgentRow.Member
        assertEquals("computer_access_revoked", synced.profile.agentBlockedReason)
    }

    @Test
    fun onlyARevokedComputerIsAHeldState() {
        assertEquals(AgentBlockedState.ComputerAccessRevoked, agentBlockedState("computer_access_revoked"))
        // The app does not show "Computer too old" yet; an unknown reason keeps the ordinary state.
        assertNull(agentBlockedState("computer_version_unsupported"))
        assertNull(agentBlockedState("some_future_reason"))
        assertNull(agentBlockedState(null))
    }

    @Test
    fun aRevokedComputersAgentReadsWhyInsteadOfAGuessOrItsActivity() = runBlocking<Unit> {
        val registry = ProcessMemberPresenceRegistry()
        val store = store(registry)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "test")

        val held = assertNotNull(store.currentMemberStatus(serverId, "agent-revoked"))
        assertEquals(AgentBlockedState.ComputerAccessRevoked, held.blocked)
        assertEquals(AgentStatus.Error, held.status)
        assertFalse(held.likelyOnline, "a held agent is not guessed online")
        val indicator = held.toRaftAgentStatusIndicatorState()
        assertEquals(RaftColor.AccentOrange, indicator.color)
        assertEquals(AgentBlockedState.ComputerAccessRevoked.englishStatusLabel, indicator.label)
        // The coarse projection (no detailed activity) keeps the held words, not "Error".
        assertEquals(AgentBlockedState.ComputerAccessRevoked.englishStatusLabel, indicator.withoutActivityDetail().label)

        // A last activity the server still projects does not replace it.
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = MemberId("agent-revoked"),
                presence = AgentStatus.Offline.presence,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline,
                statusLabel = AgentStatus.Offline.label,
                serverSeq = null
            ),
            registry.writeGeneration,
            transport
        )
        assertEquals(AgentBlockedState.ComputerAccessRevoked, assertNotNull(store.currentMemberStatus(serverId, "agent-revoked")).blocked)
    }

    @Test
    fun otherReasonsExternalAgentsAndAppBotsKeepTheirOrdinaryState() = runBlocking<Unit> {
        val registry = ProcessMemberPresenceRegistry()
        val store = store(registry)
        store.refreshMembers(serverId, force = true, reason = "test")
        for (id in listOf("agent-old", "agent-future", "agent-ext", "agent-bot", "agent-plain")) {
            assertNull(store.currentMemberStatus(serverId, id)?.blocked, id)
        }
    }
}
