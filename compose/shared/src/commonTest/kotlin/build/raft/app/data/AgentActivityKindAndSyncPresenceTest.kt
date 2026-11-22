package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `#proj-mobile:#68` item 6 (web `a.activityKind ?? a.activity` + `/api/sync/changes` agent row
 * presence projection parity):
 * - Wire agent parsers and realtime `agent:activity` decode `activityKind` ahead of `activity`.
 * - Incremental sync `agent` rows project their server-resolved `activity` / `activityKind` into
 *   [ProcessMemberPresenceRegistry] for missing or stale carried-over slots without clobbering
 *   live socket pushes or current REST projections, and without letting older roster/historical
 *   snapshots roll the synced presence backwards.
 */
class AgentActivityKindAndSyncPresenceTest {
    private val serverId = ServerId("server-1")
    private val owner = ProcessServerMembersOwner("local", "local", serverId.value)
    private val transport1 = ProcessPresenceTransportEpoch("family-1", 1L)
    private val transport2 = ProcessPresenceTransportEpoch("family-1", 2L)

    private fun storeWithMembers(
        registry: ProcessMemberPresenceRegistry,
        members: List<MemberProfile>,
    ): SharedServerMembersStore {
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId,
                ): DataResult<MemberSnapshot> =
                    DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Raft"), members))
            }
        return SharedServerMembersStore(memberRepository = repository, presenceRegistry = registry)
    }

    private fun presenceEvent(
        memberId: String,
        status: AgentStatus,
        serverSeq: Long? = null,
    ): MemberPresenceUpdated =
        MemberPresenceUpdated(
            memberId = MemberId(memberId),
            presence = status.presence,
            kind = MemberKind.Agent,
            agentStatus = status,
            statusLabel = status.label,
            serverSeq = serverSeq,
        )

    private fun syncedAgent(
        id: String,
        status: String = "active",
        activity: String? = null,
        activityKind: String? = null,
    ): MemberProfile {
        val fields =
            buildList {
                add(""""id":"$id"""")
                add(""""name":"$id"""")
                add(""""status":"$status"""")
                add(""""serverId":"${serverId.value}"""")
                if (activity != null) add(""""activity":"$activity"""")
                if (activityKind != null) add(""""activityKind":"$activityKind"""")
            }
        val row = Json.parseToJsonElement("{${fields.joinToString(",")}}").jsonObject
        return assertIs<SharedSyncedAgentRow.Member>(parseSharedAgentRow(row)).profile
    }

    @Test
    fun wireAgentParsersPreferActivityKindOverActivityAndKeepStoppedOrOffline() {
        val json =
            """
            [
              {"id":"kind-only","name":"k1","status":"active","activityKind":"online","serverId":"server-1"},
              {"id":"kind-wins","name":"k2","status":"active","activity":"offline","activityKind":"working","serverId":"server-1"},
              {"id":"blank-kind","name":"k3","status":"active","activity":"online","activityKind":"   ","serverId":"server-1"},
              {"id":"explicit-offline","name":"k4","status":"active","activity":"offline","serverId":"server-1"},
              {"id":"stopped-agent","name":"k5","status":"stopped","serverId":"server-1"},
              {"id":"active-no-activity","name":"k6","status":"active","serverId":"server-1"}
            ]
            """.trimIndent()

        val members = assertNotNull(parseSharedSettingsAgentsAsMembers(json)).associateBy { it.id.value }
        assertEquals(AgentStatus.Online, members.getValue("kind-only").agentStatus)
        assertEquals(Presence.Active, members.getValue("kind-only").presence)
        assertEquals(AgentStatus.Working, members.getValue("kind-wins").agentStatus)
        assertEquals(AgentStatus.Online, members.getValue("blank-kind").agentStatus)
        assertEquals(AgentStatus.Offline, members.getValue("explicit-offline").agentStatus)
        assertEquals(AgentStatus.Offline, members.getValue("stopped-agent").agentStatus)
        // Lifecycle "active" alone is not liveness (#135 / PR #3856 / #triage:#97).
        assertNull(members.getValue("active-no-activity").agentStatus)
        assertEquals("active", members.getValue("active-no-activity").agentLifecycleStatus)

        val currentEntries = assertNotNull(parseSharedCurrentAgentActivityEntries(json, MemberId("kind-wins")))
        assertEquals(1, currentEntries.size)
        assertEquals("working", currentEntries.single().statusActivity)
    }

    @Test
    fun realtimeAgentActivityMapperPrefersActivityKindOverActivity() {
        val mapper = RealtimeEventMapper()
        val topLevel =
            assertIs<MemberPresenceUpdated>(
                mapper.map(
                    RealtimeSocketEvents.AgentActivity,
                    """{"agentId":"agent-1","activity":"offline","activityKind":"working","detail":"Running","serverSeq":4}""",
                )
            )
        assertEquals(AgentStatus.Working, topLevel.agentStatus)
        assertEquals(Presence.Active, topLevel.presence)

        val statusEntry =
            assertIs<MemberPresenceUpdated>(
                mapper.map(
                    RealtimeSocketEvents.AgentActivity,
                    """{"agentId":"agent-1","serverSeq":5,"entries":[{"kind":"status","activity":"offline","activityKind":"thinking","detail":"Planning"}]}""",
                )
            )
        assertEquals(AgentStatus.Thinking, statusEntry.agentStatus)
        assertEquals(Presence.Active, statusEntry.presence)
    }

    @Test
    fun applySyncedRosterRowsProjectsAgentActivityForAbsentAndCarriedOverFacts() = runTest {
        val registry = ProcessMemberPresenceRegistry()
        val initialMembers =
            listOf(
                syncedAgent("agent-carried", activity = "online"),
                syncedAgent("agent-absent", activity = null),
                syncedAgent("agent-stopped", status = "stopped", activity = "offline"),
            )
        val store = storeWithMembers(registry, initialMembers)

        // Epoch 1: agent-carried was Offline; transport advances to epoch 2 so Offline is carried over.
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        registry.apply(owner, presenceEvent("agent-carried", AgentStatus.Offline, serverSeq = 9L), registry.writeGeneration, transport1)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport2)
        store.refreshMembers(serverId, force = true, reason = "initial")
        // Put agent-carried back into carried-over Offline to test the sync upsert path cleanly.
        registry.apply(owner, presenceEvent("agent-carried", AgentStatus.Offline, serverSeq = 10L), registry.writeGeneration, transport2)
        registry.rebaselineTransport(owner, registry.writeGeneration, ProcessPresenceTransportEpoch("family-1", 3L))
        assertTrue("agent-carried" in registry.carriedOverMemberIds(owner))
        assertEquals(AgentStatus.Offline, store.currentMemberStatus(serverId, "agent-carried")?.status)
        assertNull(registry.factFor(owner, "agent-absent"))

        // Incremental sync delivers server-resolved activity (including activityKind-only row).
        // Note: agent-carried's MemberProfile matches the roster entry, yet presence still recovers!
        store.applySyncedRosterRows(
            serverId = serverId,
            upserts =
                listOf(
                    syncedAgent("agent-carried", activity = "online"),
                    syncedAgent("agent-absent", activityKind = "working"),
                    syncedAgent("agent-stopped", status = "stopped", activity = "offline"),
                ),
            deletes = emptyList(),
        )

        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-carried")?.agentStatus)
        assertFalse("agent-carried" in registry.carriedOverMemberIds(owner))
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, "agent-carried")?.status)
        assertEquals(AgentStatus.Working, registry.factFor(owner, "agent-absent")?.agentStatus)
        assertEquals(AgentStatus.Working, store.currentMemberStatus(serverId, "agent-absent")?.status)
        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-stopped")?.agentStatus)
    }

    @Test
    fun applySyncedRosterRowsNeverClobbersLivePushOrCurrentRestProjection() = runTest {
        val registry = ProcessMemberPresenceRegistry()
        val store =
            storeWithMembers(
                registry,
                listOf(
                    syncedAgent("agent-live", activity = "online"),
                    syncedAgent("agent-rest", activity = "online"),
                ),
            )
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        store.refreshMembers(serverId, force = true, reason = "initial")
        registry.apply(owner, presenceEvent("agent-live", AgentStatus.Working, serverSeq = 7L), registry.writeGeneration, transport1)

        // Both agent-live (realtime push) and agent-rest (fresh RosterCurrent) have live facts on transport1.
        store.applySyncedRosterRows(
            serverId = serverId,
            upserts =
                listOf(
                    syncedAgent("agent-live", activity = "offline"),
                    syncedAgent("agent-rest", activity = "offline"),
                ),
            deletes = emptyList(),
        )

        assertEquals(AgentStatus.Working, registry.factFor(owner, "agent-live")?.agentStatus)
        assertEquals(7L, registry.factFor(owner, "agent-live")?.serverSeq)
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-rest")?.agentStatus)
    }

    @Test
    fun syncedAgentProjectionBlocksOlderRosterAndHistoricalRollbackButYieldsToNewerAuthority() = runTest {
        val registry = ProcessMemberPresenceRegistry()
        val store = storeWithMembers(registry, listOf(syncedAgent("agent-1", activity = null)))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        store.refreshMembers(serverId, force = true, reason = "initial")
        assertNull(registry.factFor(owner, "agent-1"))

        // Capture a roster request and a detail request BEFORE sync arrives.
        val olderRosterReq = assertNotNull(registry.captureRosterProjectionRequest(owner, registry.writeGeneration))
        val concurrentDetailReq = assertNotNull(registry.captureDetailProjectionRequest(owner, "agent-1", registry.writeGeneration))

        // Sync arrives with server-resolved Online.
        store.applySyncedRosterRows(
            serverId = serverId,
            upserts = listOf(syncedAgent("agent-1", activity = "online")),
            deletes = emptyList(),
        )
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)

        // 1. Stale pre-sync roster read answering Offline cannot roll back synced Online.
        registry.applyProjection(
            request = assertNotNull(olderRosterReq.memberRequest("agent-1", freshRoster = true)),
            event = presenceEvent("agent-1", AgentStatus.Offline),
        )
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)

        // 2. Historical trajectory snapshot cannot roll back synced Online.
        store.applyAgentActivitySnapshotForTest(
            serverId = serverId,
            agentId = MemberId("agent-1"),
            activity =
                listOf(
                    AgentActivityEntry(
                        id = "hist-1",
                        type = "status",
                        title = "Offline",
                        timestamp = "100",
                        statusActivity = "offline",
                        statusDetail = "Offline",
                    )
                ),
        )
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)

        // 3. Concurrent higher-authority DetailCurrent supersedes synced RosterCurrent.
        registry.applyProjection(
            request = concurrentDetailReq,
            event = presenceEvent("agent-1", AgentStatus.Working),
        )
        assertEquals(AgentStatus.Working, registry.factFor(owner, "agent-1")?.agentStatus)

        // 4. Subsequent realtime push supersedes as well.
        registry.apply(owner, presenceEvent("agent-1", AgentStatus.Online, serverSeq = 1L), registry.writeGeneration, transport1)
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)
        assertEquals(1L, registry.factFor(owner, "agent-1")?.serverSeq)
    }

    @Test
    fun syncedAgentProjectionRejectsRetiredWriteGeneration() {
        val registry = ProcessMemberPresenceRegistry()
        val staleGeneration = registry.writeGeneration
        registry.clearAll(reason = "logout")

        val applied =
            registry.applySyncedAgentProjection(
                owner = owner,
                event = presenceEvent("agent-1", AgentStatus.Online),
                writeGeneration = staleGeneration,
            )

        assertFalse(applied)
        assertNull(registry.factFor(owner, "agent-1"))
    }
}
