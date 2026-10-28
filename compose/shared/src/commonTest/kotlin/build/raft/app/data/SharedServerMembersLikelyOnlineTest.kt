package build.raft.app.data

import build.raft.app.RaftColor
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.raftHomeDmPresenceAgentId
import build.raft.app.toRaftAgentStatusIndicatorState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Likely online" (artin, #triage:#97; web botiverse/slock #11642): an agent the app has no
 * presence fact for, whose lifecycle `status` is `active`, shows a green dot labelled
 * "Likely online". A real fact always wins; `stopped` stays Offline; an unknown or missing
 * lifecycle keeps the neutral "—"; App Bots and external agents get no guess.
 */
class SharedServerMembersLikelyOnlineTest {
    private val serverId = ServerId("server-1")
    private val owner =
        ProcessServerMembersOwner(
            environmentId = "local",
            accountId = "local",
            serverId = serverId.value
        )
    private val transport = ProcessPresenceTransportEpoch("family-1", 1L)

    private val agentsJson =
        """
        [
          {"id":"agent-active","name":"active","status":"active","serverId":"server-1"},
          {"id":"agent-idle","name":"idle","status":"active","activity":"idle","serverId":"server-1"},
          {"id":"agent-stopped","name":"stopped","status":"stopped","serverId":"server-1"},
          {"id":"agent-unknown","name":"unknown","status":"hibernating","serverId":"server-1"},
          {"id":"agent-blank","name":"blank","status":"","serverId":"server-1"},
          {"id":"agent-bot","name":"bot","status":"active","managedByAppInstallId":"install-1","serverId":"server-1"},
          {"id":"agent-ext","name":"ext","status":"active","runtime":"external","serverId":"server-1"},
          {"id":"agent-deleted","name":"gone","status":"active","deletedAt":"2026-10-01T00:00:00Z","serverId":"server-1"}
        ]
        """.trimIndent()

    private fun store(registry: ProcessMemberPresenceRegistry): SharedServerMembersStore {
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId
                ): DataResult<MemberSnapshot> =
                    DataResult.Content(
                        MemberSnapshot(
                            server = ServerSummary(id = serverId, name = "Raft"),
                            members = assertNotNull(parseSharedSettingsAgentsAsMembers(agentsJson))
                        )
                    )
            }
        return SharedServerMembersStore(repository, presenceRegistry = registry)
    }

    /** Both shapes of "no fact": before any transport, and a live transport that never saw it. */
    private fun forEachNoFactShape(block: (shape: String, SharedServerMembersStore, ProcessMemberPresenceRegistry) -> Unit) =
        runBlocking {
            for (liveTransport in listOf(false, true)) {
                val registry = ProcessMemberPresenceRegistry()
                val store = store(registry)
                if (liveTransport) registry.rebaselineTransport(owner, registry.writeGeneration, transport)
                store.refreshMembers(serverId, force = true, reason = "test")
                block(if (liveTransport) "live transport" else "no transport", store, registry)
            }
        }

    @Test
    fun noFactAndActiveLifecycleReadsLikelyOnlineGreen() =
        forEachNoFactShape { shape, store, registry ->
            assertNull(registry.factFor(owner, "agent-active"), shape)
            val status = assertNotNull(store.currentMemberStatus(serverId, "agent-active"), shape)
            assertTrue(status.likelyOnline, shape)
            assertNull(status.status, "$shape: a guess is not a server-sent Online")

            val indicator = status.toRaftAgentStatusIndicatorState()
            assertTrue(indicator.likelyOnline, shape)
            assertEquals(RaftColor.AccentLime, indicator.color, shape)
            assertFalse(indicator.pulsing, shape)
            assertEquals(LikelyOnlineAgentStatusLabel, indicator.label, shape)
        }

    @Test
    fun noFactAndStoppedLifecycleStaysOffline() =
        forEachNoFactShape { shape, store, _ ->
            val status = assertNotNull(store.currentMemberStatus(serverId, "agent-stopped"), shape)
            assertFalse(status.likelyOnline, shape)
            val indicator = status.toRaftAgentStatusIndicatorState()
            assertEquals(RaftColor.StatusNeutralGray, indicator.color, shape)
            assertEquals(AgentStatus.Offline.label, indicator.label, shape)
        }

    @Test
    fun noFactAndUnknownOrMissingLifecycleStaysNeutral() = runBlocking<Unit> {
        val registry = ProcessMemberPresenceRegistry()
        val store = store(registry)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "test")

        for (id in listOf("agent-unknown", "agent-blank")) {
            val status = assertNotNull(store.currentMemberStatus(serverId, id), id)
            assertFalse(status.likelyOnline, id)
            assertNull(status.status, id)
            assertEquals(NeutralAgentStatusLabel, status.statusLabel, id)
        }
        // A profile with no lifecycle at all (identity seed shape) is never guessed.
        val noLifecycle = MemberProfile(id = MemberId("agent-x"), displayName = "x", handle = "x", kind = MemberKind.Agent)
        assertNull(noLifecycle.likelyOnlineMemberStatusOrNull())
        assertNull(noLifecycle.copy(agentLifecycleStatus = "paused").likelyOnlineMemberStatusOrNull())
        assertNotNull(noLifecycle.copy(agentLifecycleStatus = "active").likelyOnlineMemberStatusOrNull())
    }

    @Test
    fun rosterActivityIsARealFactAndWins() =
        forEachNoFactShape { shape, store, _ ->
            val status = assertNotNull(store.currentMemberStatus(serverId, "agent-idle"), shape)
            assertFalse(status.likelyOnline, shape)
            assertEquals(AgentStatus.Idle, status.status, shape)
        }

    @Test
    fun realtimeFactReplacesTheGuess() = runBlocking<Unit> {
        val registry = ProcessMemberPresenceRegistry()
        val store = store(registry)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "test")
        assertTrue(assertNotNull(store.currentMemberStatus(serverId, "agent-active")).likelyOnline)

        for (real in listOf(AgentStatus.Offline, AgentStatus.Online, AgentStatus.Working)) {
            registry.apply(
                owner,
                MemberPresenceUpdated(
                    memberId = MemberId("agent-active"),
                    presence = real.presence,
                    kind = MemberKind.Agent,
                    agentStatus = real,
                    statusLabel = real.label,
                    serverSeq = null
                ),
                registry.writeGeneration,
                transport
            )
            val status = assertNotNull(store.currentMemberStatus(serverId, "agent-active"), real.name)
            assertFalse(status.likelyOnline, real.name)
            assertEquals(real, status.status, real.name)
        }
    }

    @Test
    fun carriedOverFactIsAFactAndWins() = runBlocking<Unit> {
        val registry = ProcessMemberPresenceRegistry()
        val store = store(registry)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = MemberId("agent-active"),
                presence = Presence.Offline,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline,
                statusLabel = AgentStatus.Offline.label,
                serverSeq = 5L
            ),
            registry.writeGeneration,
            transport
        )
        registry.rebaselineTransport(owner, registry.writeGeneration, ProcessPresenceTransportEpoch("family-1", 2L))
        store.refreshMembers(serverId, force = true, reason = "presence_transport_connected")

        assertEquals(setOf("agent-active"), registry.carriedOverMemberIds(owner))
        val status = assertNotNull(store.currentMemberStatus(serverId, "agent-active"))
        assertFalse(status.likelyOnline)
        assertEquals(AgentStatus.Offline, status.status)
    }

    @Test
    fun appBotsExternalAndDeletedAgentsAreNeverGuessed() =
        forEachNoFactShape { shape, store, _ ->
            for (id in listOf("agent-bot", "agent-ext", "agent-deleted")) {
                val status = store.currentMemberStatus(serverId, id)
                assertFalse(status?.likelyOnline == true, "$shape $id")
            }
            // And App Bot rows paint no presence at all.
            assertNull(raftHomeDmPresenceAgentId("agent-bot", isAgent = true, isAppBot = true))
        }
}
