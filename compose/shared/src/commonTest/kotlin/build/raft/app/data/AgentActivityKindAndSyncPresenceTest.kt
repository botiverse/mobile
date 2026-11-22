package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
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
 * - Wire agent parsers and realtime `agent:activity` decode `activityKind` ahead of `activity`
 *   (falling back cleanly to `activity` when `activityKind` is blank/whitespace).
 * - Incremental sync `agent` rows project their server-resolved `activity` / `activityKind` into
 *   [ProcessMemberPresenceRegistry] for missing or stale carried-over slots without clobbering
 *   live socket pushes or current REST projections, blocking historical trajectory snapshots from
 *   rolling the synced presence backwards while letting concurrent/in-flight roster reads, detail
 *   reads, and realtime pushes supersede it.
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

        val blankFallbackEntries = assertNotNull(parseSharedCurrentAgentActivityEntries(json, MemberId("blank-kind")))
        assertEquals(1, blankFallbackEntries.size)
        assertEquals("online", blankFallbackEntries.single().statusActivity)
    }

    @Test
    fun channelMemberSnapshotAndActivityLogPreferActivityKindAndFallBackWhenBlank() {
        val channelMembersJson =
            """
            {
              "humans": [],
              "agents": [
                {"id":"kind-only","name":"k1","status":"active","activityKind":"online","serverId":"server-1"},
                {"id":"kind-wins","name":"k2","status":"active","activity":"offline","activityKind":"working","serverId":"server-1"},
                {"id":"blank-kind","name":"k3","status":"active","activity":"online","activityKind":"   ","serverId":"server-1"},
                {"id":"active-only","name":"k4","status":"active","serverId":"server-1"}
              ]
            }
            """.trimIndent()
        val snapshot = assertNotNull(parseSharedChannelMemberSnapshot(channelMembersJson, ChannelId("ch-1")))
        val byId = snapshot.members.associateBy { it.id.value }
        assertEquals(AgentStatus.Online, byId.getValue("kind-only").agentStatus)
        assertEquals(AgentStatus.Working, byId.getValue("kind-wins").agentStatus)
        assertEquals(AgentStatus.Online, byId.getValue("blank-kind").agentStatus)
        assertNull(byId.getValue("active-only").agentStatus)
        assertEquals(setOf("kind-only", "kind-wins", "blank-kind"), snapshot.liveActivityAgentIds)

        val activityLogJson =
            """
            [
              {"timestamp":100,"entry":{"kind":"status","activity":"offline","activityKind":"working","detail":"Running"}},
              {"timestamp":101,"entry":{"kind":"status","activity":"online","activityKind":"   ","detail":"Ready"}},
              {"timestamp":102,"entry":{"kind":"custom_event","activity":"online","activityKind":"   "}},
              {"timestamp":103,"entry":{"kind":"custom_event","activity":"offline","activityKind":"working"}}
            ]
            """.trimIndent()
        val log = assertNotNull(parseSharedAgentActivity(activityLogJson, MemberId("agent-1")))
        assertEquals(4, log.activity.size)
        assertEquals("working", log.activity[0].statusActivity)
        assertEquals("Working", log.activity[0].title)
        assertEquals("online", log.activity[1].statusActivity)
        assertEquals("Idle", log.activity[1].title)
        assertEquals("Online", log.activity[2].title)
        assertEquals("Working", log.activity[3].title)
    }

    @Test
    fun realtimeAgentActivityMapperPrefersActivityKindOverActivityAndFallsBackWhenBlank() {
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
        assertEquals("thinking", statusEntry.activityEntries.single().statusActivity)
        assertEquals("Thinking", statusEntry.activityEntries.single().title)

        val topLevelBlankKind =
            assertIs<MemberPresenceUpdated>(
                mapper.map(
                    RealtimeSocketEvents.AgentActivity,
                    """{"agentId":"agent-1","activity":"online","activityKind":"   ","detail":"Ready","serverSeq":6}""",
                )
            )
        assertEquals(AgentStatus.Online, topLevelBlankKind.agentStatus)
        assertEquals(Presence.Active, topLevelBlankKind.presence)

        val entryBlankKind =
            assertIs<MemberPresenceUpdated>(
                mapper.map(
                    RealtimeSocketEvents.AgentActivity,
                    """{"agentId":"agent-1","serverSeq":7,"entries":[{"kind":"status","activity":"thinking","activityKind":"  ","detail":"Planning"}]}""",
                )
            )
        assertEquals(AgentStatus.Thinking, entryBlankKind.agentStatus)
        assertEquals(Presence.Active, entryBlankKind.presence)
        assertEquals("thinking", entryBlankKind.activityEntries.single().statusActivity)
        assertEquals("Thinking", entryBlankKind.activityEntries.single().title)

        val customEntries =
            assertIs<MemberPresenceUpdated>(
                mapper.map(
                    RealtimeSocketEvents.AgentActivity,
                    """{"agentId":"agent-1","activity":"online","serverSeq":8,"entries":[{"kind":"custom_state","activity":"working","activityKind":"   "},{"kind":"custom_state","activity":"offline","activityKind":"thinking"}]}""",
                )
            )
        assertEquals(AgentStatus.Online, customEntries.agentStatus)
        assertEquals(listOf("Working", "Thinking"), customEntries.activityEntries.map { it.title })
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
    fun syncedAgentProjectionBlocksHistoricalRollbackAndYieldsToConcurrentRosterDetailAndRealtime() = runTest {
        val registry = ProcessMemberPresenceRegistry()
        val store =
            storeWithMembers(
                registry,
                listOf(
                    syncedAgent("agent-1", activity = null),
                    syncedAgent("agent-2", activity = null),
                    syncedAgent("agent-3", activity = null),
                ),
            )
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        store.refreshMembers(serverId, force = true, reason = "initial")
        assertNull(registry.factFor(owner, "agent-1"))
        assertNull(registry.factFor(owner, "agent-2"))
        assertNull(registry.factFor(owner, "agent-3"))

        // Capture concurrent /agents roster and detail requests BEFORE incremental sync lands.
        val concurrentRosterReq = assertNotNull(registry.captureRosterProjectionRequest(owner, registry.writeGeneration))
        val concurrentDetailReq = assertNotNull(registry.captureDetailProjectionRequest(owner, "agent-3", registry.writeGeneration))

        // Incremental sync lands with SyncedCurrent (authorityRank = 1): agent-1 Online,
        // agent-2 Offline (stale sync row while /agents recovery is in flight), agent-3 Online.
        store.applySyncedRosterRows(
            serverId = serverId,
            upserts =
                listOf(
                    syncedAgent("agent-1", activity = "online"),
                    syncedAgent("agent-2", activity = "offline"),
                    syncedAgent("agent-3", activity = "online"),
                ),
            deletes = emptyList(),
        )
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)
        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-2")?.agentStatus)
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-3")?.agentStatus)

        // 1. Historical trajectory snapshot (rank 0) cannot roll back SyncedCurrent (rank 1).
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

        // 2. Concurrent / in-flight RosterCurrent (rank 2) supersedes SyncedCurrent (rank 1),
        // so an in-flight machine:status / reconnect /agents read wins over a stale sync row.
        registry.applyProjection(
            request = assertNotNull(concurrentRosterReq.memberRequest("agent-1", freshRoster = true)),
            event = presenceEvent("agent-1", AgentStatus.Working),
        )
        registry.applyProjection(
            request = assertNotNull(concurrentRosterReq.memberRequest("agent-2", freshRoster = true)),
            event = presenceEvent("agent-2", AgentStatus.Online),
        )
        assertEquals(AgentStatus.Working, registry.factFor(owner, "agent-1")?.agentStatus)
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-2")?.agentStatus)

        // 3. Concurrent higher-authority DetailCurrent (rank 3) supersedes SyncedCurrent (rank 1).
        registry.applyProjection(
            request = concurrentDetailReq,
            event = presenceEvent("agent-3", AgentStatus.Thinking),
        )
        assertEquals(AgentStatus.Thinking, registry.factFor(owner, "agent-3")?.agentStatus)

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

    @Test
    fun preSyncRequestSupersedingSyncedCurrentDoesNotBlockNewerPostSyncRequest() = runTest {
        val registry = ProcessMemberPresenceRegistry()
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        // Case A: pre-sync RosterCurrent (req 1) supersedes SyncedCurrent (req 2); newer post-sync
        // DetailCurrent (req 3, base = 2) must still supersede it rather than being dropped.
        val preSyncRoster = assertNotNull(registry.captureRosterProjectionRequest(owner, registry.writeGeneration))
        assertTrue(
            registry.applySyncedAgentProjection(
                owner = owner,
                event = presenceEvent("agent-a", AgentStatus.Offline),
                writeGeneration = registry.writeGeneration,
            )
        )
        val postSyncDetail = assertNotNull(registry.captureDetailProjectionRequest(owner, "agent-a", registry.writeGeneration))

        registry.applyProjection(
            request = assertNotNull(preSyncRoster.memberRequest("agent-a", freshRoster = true)),
            event = presenceEvent("agent-a", AgentStatus.Online),
        )
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-a")?.agentStatus)

        registry.applyProjection(
            request = postSyncDetail,
            event = presenceEvent("agent-a", AgentStatus.Working),
        )
        assertEquals(AgentStatus.Working, registry.factFor(owner, "agent-a")?.agentStatus)

        // Case B: symmetric — pre-sync DetailCurrent (req 4) supersedes SyncedCurrent (req 5);
        // newer post-machine:status RosterCurrent (req 6, base = 5) must still supersede it.
        val preSyncDetail = assertNotNull(registry.captureDetailProjectionRequest(owner, "agent-b", registry.writeGeneration))
        assertTrue(
            registry.applySyncedAgentProjection(
                owner = owner,
                event = presenceEvent("agent-b", AgentStatus.Offline),
                writeGeneration = registry.writeGeneration,
            )
        )
        val postSyncRoster = assertNotNull(registry.captureRosterProjectionRequest(owner, registry.writeGeneration))

        registry.applyProjection(
            request = preSyncDetail,
            event = presenceEvent("agent-b", AgentStatus.Offline),
        )
        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-b")?.agentStatus)

        registry.applyProjection(
            request = assertNotNull(postSyncRoster.memberRequest("agent-b", freshRoster = true)),
            event = presenceEvent("agent-b", AgentStatus.Online),
        )
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-b")?.agentStatus)
    }
}
