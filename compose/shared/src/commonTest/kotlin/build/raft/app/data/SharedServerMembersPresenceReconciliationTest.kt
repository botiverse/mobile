package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedServerMembersPresenceReconciliationTest {
    private val serverId = ServerId("server-1")
    private val agentId = MemberId("agent-1")
    private val owner =
        ProcessServerMembersOwner(
            environmentId = "local",
            accountId = "local",
            serverId = serverId.value
        )

    @Test
    fun reconnectRebaselineAllowsCurrentDetailAndRestartedSequence() {
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
        val oldTransport = ProcessPresenceTransportEpoch("family-1", 1L)
        val restartedTransport = ProcessPresenceTransportEpoch("family-1", 2L)
        registry.apply(
            owner,
            presence(AgentStatus.Offline, serverSeq = 10L, detail = "Stopped"),
            registry.writeGeneration,
            oldTransport
        )
        registry.rebaselineTransport(owner, registry.writeGeneration, restartedTransport)

        val request = store.beginAgentDetailPresenceProjection(serverId, agentId)
        store.applyAgentDetailSnapshot(
            request,
            detail(
                summaryStatus = "offline",
                summaryActivity = "working",
                trajectoryStatus = "offline"
            )
        )

        val repaired = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, repaired?.agentStatus)
        assertNull(repaired?.serverSeq)

        registry.apply(
            owner,
            presence(AgentStatus.Working, serverSeq = 1L, detail = "Running"),
            registry.writeGeneration,
            restartedTransport
        )
        val restarted = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, restarted?.agentStatus)
        assertEquals(1L, restarted?.serverSeq)
    }

    @Test
    fun currentDetailRepairPreservesSameTransportSequenceFloor() {
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
        store.applyPresence(serverId, presence(AgentStatus.Offline, serverSeq = 10L, detail = "Stopped"))

        val request = store.beginAgentDetailPresenceProjection(serverId, agentId)
        store.applyAgentDetailSnapshot(
            request,
            detail(summaryStatus = "working", summaryActivity = "working")
        )

        val repaired = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, repaired?.agentStatus)
        assertEquals(10L, repaired?.serverSeq)

        store.applyPresence(serverId, presence(AgentStatus.Offline, serverSeq = 9L, detail = "Late old event"))
        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId.value)?.agentStatus)
        assertEquals(10L, registry.factFor(owner, agentId.value)?.serverSeq)

        store.applyPresence(serverId, presence(AgentStatus.Offline, serverSeq = 10L, detail = "Equal replay"))
        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId.value)?.agentStatus)
        assertEquals(10L, registry.factFor(owner, agentId.value)?.serverSeq)

        store.applyPresence(serverId, presence(AgentStatus.Online, serverSeq = 11L, detail = "Current event"))
        assertEquals(AgentStatus.Online, registry.factFor(owner, agentId.value)?.agentStatus)
        assertEquals(11L, registry.factFor(owner, agentId.value)?.serverSeq)
    }

    @Test
    fun realtimeArrivingDuringDetailRequestWins() {
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
        val request = store.beginAgentDetailPresenceProjection(serverId, agentId)

        store.applyPresence(serverId, presence(AgentStatus.Working, serverSeq = 7L, detail = "Running"))
        store.applyAgentDetailSnapshot(
            request,
            detail(summaryStatus = "offline", summaryActivity = "offline")
        )

        val current = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, current?.agentStatus)
        assertEquals(7L, current?.serverSeq)
        assertEquals("Running", current?.statusLabel)
    }

    @Test
    fun historicalActivityDoesNotInvalidateConcurrentCurrentDetail() {
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
        val detailRequest = store.beginAgentDetailPresenceProjection(serverId, agentId)
        val activityRequest = store.beginAgentActivityPresenceProjection(serverId, agentId)

        store.applyAgentActivitySnapshot(
            request = activityRequest,
            agentId = agentId,
            activity = listOf(statusEntry(status = "offline", timestamp = "200"))
        )
        store.applyAgentDetailSnapshot(
            detailRequest,
            detail(summaryStatus = "working", summaryActivity = "working")
        )

        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId.value)?.agentStatus)
    }

    @Test
    fun historicalActivityStartedAfterCurrentDetailStillCannotReplaceCurrentState() {
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
        val detailRequest = store.beginAgentDetailPresenceProjection(serverId, agentId)
        store.applyAgentDetailSnapshot(
            detailRequest,
            detail(summaryStatus = "working", summaryActivity = "working")
        )

        val activityRequest = store.beginAgentActivityPresenceProjection(serverId, agentId)
        store.applyAgentActivitySnapshot(
            request = activityRequest,
            agentId = agentId,
            activity = listOf(statusEntry(status = "offline", timestamp = "999"))
        )

        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId.value)?.agentStatus)
    }

    @Test
    fun forcedRosterRefreshPublishesRequestFencedCurrentStatus() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        var rosterStatus = AgentStatus.Working
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId
                ): DataResult<MemberSnapshot> =
                    DataResult.Content(
                        MemberSnapshot(
                            server = ServerSummary(id = serverId, name = "Raft"),
                            members =
                                listOf(
                                    MemberProfile(
                                        id = agentId,
                                        displayName = "Agent One",
                                        handle = "agent-one",
                                        kind = MemberKind.Agent,
                                        presence = rosterStatus.presence,
                                        agentStatus = rosterStatus,
                                        serverId = serverId
                                    )
                                )
                        )
                    )
            }
        val store = SharedServerMembersStore(repository, presenceRegistry = registry)
        registry.rebaselineTransport(
            owner,
            registry.writeGeneration,
            ProcessPresenceTransportEpoch("family-1", 1L)
        )

        assertEquals(
            DataResult.Content(Unit),
            store.refreshMembers(serverId, force = true, reason = "test_reconnect")
        )
        val current = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, current?.agentStatus)
        assertNull(current?.serverSeq)

        val activityRequest = store.beginAgentActivityPresenceProjection(serverId, agentId)
        store.applyAgentActivitySnapshot(
            request = activityRequest,
            agentId = agentId,
            activity = listOf(statusEntry(status = "offline", timestamp = "999"))
        )
        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId.value)?.agentStatus)

        registry.apply(
            owner = owner,
            event = presence(AgentStatus.Working, serverSeq = 7L, detail = "Realtime"),
            writeGeneration = registry.writeGeneration,
            transport = ProcessPresenceTransportEpoch("family-1", 1L)
        )
        rosterStatus = AgentStatus.Offline
        store.refreshMembers(serverId, force = true, reason = "test_duplicate_signal")
        val sequenced = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, sequenced?.agentStatus)
        assertEquals(7L, sequenced?.serverSeq)
    }

    @Test
    fun reconnectSuppressesStaleRosterFallbackUntilCurrentStatusRefresh() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId
                ): DataResult<MemberSnapshot> =
                    DataResult.Content(
                        MemberSnapshot(
                            server = ServerSummary(id = serverId, name = "Raft"),
                            members =
                                listOf(
                                    MemberProfile(
                                        id = agentId,
                                        displayName = "Agent One",
                                        handle = "agent-one",
                                        kind = MemberKind.Agent,
                                        presence = Presence.Offline,
                                        agentStatus = AgentStatus.Offline,
                                        serverId = serverId
                                    )
                                )
                        )
                    )
            }
        val store = SharedServerMembersStore(repository, presenceRegistry = registry)
        store.refreshMembers(serverId, force = false, reason = "initial")
        assertEquals(AgentStatus.Offline, store.memberStatusFlow(serverId, agentId.value).first()?.status)

        registry.rebaselineTransport(
            owner,
            registry.writeGeneration,
            ProcessPresenceTransportEpoch("family-1", 1L)
        )

        assertEquals(
            SharedServerMemberStatus(
                status = null,
                presence = Presence.Offline,
                statusLabel = "—",
                external = false
            ),
            store.memberStatusFlow(serverId, agentId.value).first()
        )
    }

    @Test
    fun realtimeDuringForcedRosterRequestWins() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(
                    priority: DataRequestPriority,
                    serverId: ServerId
                ): DataResult<MemberSnapshot> {
                    started.complete(Unit)
                    release.await()
                    return DataResult.Content(
                        MemberSnapshot(
                            server = ServerSummary(id = serverId, name = "Raft"),
                            members =
                                listOf(
                                    MemberProfile(
                                        id = agentId,
                                        displayName = "Agent One",
                                        handle = "agent-one",
                                        kind = MemberKind.Agent,
                                        presence = Presence.Offline,
                                        agentStatus = AgentStatus.Offline,
                                        serverId = serverId
                                    )
                                )
                        )
                    )
                }
            }
        val store = SharedServerMembersStore(repository, presenceRegistry = registry)
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)

        val refresh = async { store.refreshMembers(serverId, force = true, reason = "held") }
        started.await()
        registry.apply(
            owner = owner,
            event = presence(AgentStatus.Working, serverSeq = 7L, detail = "Realtime"),
            writeGeneration = registry.writeGeneration,
            transport = transport
        )
        release.complete(Unit)
        refresh.await()

        val current = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, current?.agentStatus)
        assertEquals(7L, current?.serverSeq)
    }

    @Test
    fun detailAuthorityWinsConcurrentRosterInEitherResponseOrder() = runBlocking {
        for (rosterCompletesFirst in listOf(true, false)) {
            val registry = ProcessMemberPresenceRegistry()
            val rosterStarted = CompletableDeferred<Unit>()
            val releaseRoster = CompletableDeferred<Unit>()
            val repository =
                object : MemberRepository by PresenceNoopMemberRepository {
                    override suspend fun getMembers(
                        priority: DataRequestPriority,
                        serverId: ServerId
                    ): DataResult<MemberSnapshot> {
                        rosterStarted.complete(Unit)
                        releaseRoster.await()
                        return DataResult.Content(
                            MemberSnapshot(
                                server = ServerSummary(id = serverId, name = "Raft"),
                                members =
                                    listOf(
                                        MemberProfile(
                                            id = agentId,
                                            displayName = "Agent One",
                                            handle = "agent-one",
                                            kind = MemberKind.Agent,
                                            presence = Presence.Offline,
                                            agentStatus = AgentStatus.Offline,
                                            serverId = serverId
                                        )
                                    )
                            )
                        )
                    }
                }
            val store = SharedServerMembersStore(repository, presenceRegistry = registry)
            registry.rebaselineTransport(
                owner,
                registry.writeGeneration,
                ProcessPresenceTransportEpoch("family-1", 1L)
            )

            val rosterRefresh =
                async { store.refreshMembers(serverId, force = true, reason = "held_concurrent_roster") }
            rosterStarted.await()
            val detailRequest = store.beginAgentDetailPresenceProjection(serverId, agentId)

            if (rosterCompletesFirst) {
                releaseRoster.complete(Unit)
                assertEquals(DataResult.Content(Unit), rosterRefresh.await())
                assertEquals(AgentStatus.Offline, registry.factFor(owner, agentId.value)?.agentStatus)
                store.applyAgentDetailSnapshot(
                    detailRequest,
                    detail(summaryStatus = "working", summaryActivity = "working")
                )
            } else {
                store.applyAgentDetailSnapshot(
                    detailRequest,
                    detail(summaryStatus = "working", summaryActivity = "working")
                )
                releaseRoster.complete(Unit)
                assertEquals(DataResult.Content(Unit), rosterRefresh.await())
            }

            assertEquals(
                AgentStatus.Working,
                registry.factFor(owner, agentId.value)?.agentStatus,
                "detail must win when rosterCompletesFirst=$rosterCompletesFirst"
            )
        }
    }

    @Test
    fun newerDetailRequestWinsRegardlessOfCompletionOrder() {
        for (newerCompletesFirst in listOf(true, false)) {
            val registry = ProcessMemberPresenceRegistry()
            val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
            val olderRequest = store.beginAgentDetailPresenceProjection(serverId, agentId)
            val newerRequest = store.beginAgentDetailPresenceProjection(serverId, agentId)
            val olderDetail = detail(summaryStatus = "offline", summaryActivity = "offline")
            val newerDetail = detail(summaryStatus = "working", summaryActivity = "working")

            if (newerCompletesFirst) {
                store.applyAgentDetailSnapshot(newerRequest, newerDetail)
                store.applyAgentDetailSnapshot(olderRequest, olderDetail)
            } else {
                store.applyAgentDetailSnapshot(olderRequest, olderDetail)
                store.applyAgentDetailSnapshot(newerRequest, newerDetail)
            }

            assertEquals(
                AgentStatus.Working,
                registry.factFor(owner, agentId.value)?.agentStatus,
                "newer request must win when newerCompletesFirst=$newerCompletesFirst"
            )
        }
    }

    @Test
    fun laterRosterRefreshAdvancesPastDetailAndBlocksOlderDetailSibling() {
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
        val staleDetail = store.beginAgentDetailPresenceProjection(serverId, agentId)
        val acceptedDetail = store.beginAgentDetailPresenceProjection(serverId, agentId)

        store.applyAgentDetailSnapshot(
            acceptedDetail,
            detail(summaryStatus = "working", summaryActivity = "working")
        )
        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId.value)?.agentStatus)

        val rosterRequest =
            registry.captureRosterProjectionRequest(owner, registry.writeGeneration)
                ?.memberRequest(agentId.value)
        checkNotNull(rosterRequest)
        registry.applyProjection(
            request = rosterRequest,
            event =
                MemberPresenceUpdated(
                    memberId = agentId,
                    presence = Presence.Offline,
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Offline,
                    statusLabel = AgentStatus.Offline.label,
                    serverSeq = null
                )
        )
        assertEquals(AgentStatus.Offline, registry.factFor(owner, agentId.value)?.agentStatus)

        store.applyAgentDetailSnapshot(
            staleDetail,
            detail(summaryStatus = "online", summaryActivity = "online")
        )
        assertEquals(AgentStatus.Offline, registry.factFor(owner, agentId.value)?.agentStatus)
    }

    @Test
    fun reconnectInvalidatesHeldDetailAndRejectsOldTransportEpoch() {
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
        val firstTransport = ProcessPresenceTransportEpoch("family-1", 1L)
        val secondTransport = ProcessPresenceTransportEpoch("family-1", 2L)

        assertEquals(
            ProcessPresenceTransportRebaselineResult.Applied,
            registry.rebaselineTransport(owner, registry.writeGeneration, firstTransport)
        )
        registry.apply(
            owner,
            presence(AgentStatus.Offline, serverSeq = 10L, detail = "Stopped"),
            registry.writeGeneration,
            firstTransport
        )
        val heldRequest = store.beginAgentDetailPresenceProjection(serverId, agentId)

        assertEquals(
            ProcessPresenceTransportRebaselineResult.Applied,
            registry.rebaselineTransport(owner, registry.writeGeneration, secondTransport)
        )
        store.applyAgentDetailSnapshot(
            heldRequest,
            detail(summaryStatus = "working", summaryActivity = "working")
        )
        assertNull(registry.factFor(owner, agentId.value))

        registry.apply(
            owner,
            presence(AgentStatus.Offline, serverSeq = 11L, detail = "Late old socket"),
            registry.writeGeneration,
            firstTransport
        )
        assertNull(registry.factFor(owner, agentId.value))

        registry.apply(
            owner,
            presence(AgentStatus.Working, serverSeq = 1L, detail = "New socket"),
            registry.writeGeneration,
            secondTransport
        )
        val current = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, current?.agentStatus)
        assertEquals(1L, current?.serverSeq)
    }

    @Test
    fun firstPresenceEventFromNewTransportRebaselinesBeforeDelayedConnectSignal() {
        val registry = ProcessMemberPresenceRegistry()
        val firstTransport = ProcessPresenceTransportEpoch("family-1", 1L)
        val secondTransport = ProcessPresenceTransportEpoch("family-1", 2L)
        val otherAgentId = MemberId("agent-2")

        registry.apply(
            owner,
            presence(AgentStatus.Offline, serverSeq = 10L, detail = "Stopped"),
            registry.writeGeneration,
            firstTransport
        )
        registry.apply(
            owner,
            MemberPresenceUpdated(
                memberId = otherAgentId,
                presence = Presence.Offline,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Offline,
                serverSeq = 20L
            ),
            registry.writeGeneration,
            firstTransport
        )

        registry.apply(
            owner,
            presence(AgentStatus.Working, serverSeq = 1L, detail = "New socket"),
            registry.writeGeneration,
            secondTransport
        )

        val current = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, current?.agentStatus)
        assertEquals(1L, current?.serverSeq)
        assertNull(registry.factFor(owner, otherAgentId.value))
        assertEquals(
            ProcessPresenceTransportRebaselineResult.Current,
            registry.rebaselineTransport(owner, registry.writeGeneration, secondTransport)
        )
    }

    @Test
    fun transportRebaselineIsOwnerScoped() {
        val registry = ProcessMemberPresenceRegistry()
        val otherAccount = owner.copy(accountId = "other-account")
        val otherServer = owner.copy(serverId = "server-2")
        registry.apply(owner, presence(AgentStatus.Offline, serverSeq = 10L), registry.writeGeneration)
        registry.apply(otherAccount, presence(AgentStatus.Working, serverSeq = 20L), registry.writeGeneration)
        registry.apply(otherServer, presence(AgentStatus.Online, serverSeq = 30L), registry.writeGeneration)

        registry.rebaselineTransport(
            owner,
            registry.writeGeneration,
            ProcessPresenceTransportEpoch("family-1", 1L)
        )

        assertNull(registry.factFor(owner, agentId.value))
        assertEquals(AgentStatus.Working, registry.factFor(otherAccount, agentId.value)?.agentStatus)
        assertEquals(AgentStatus.Online, registry.factFor(otherServer, agentId.value)?.agentStatus)
    }

    @Test
    fun memberEvictionInvalidatesHeldCurrentDetail() {
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
        val heldRequest = store.beginAgentDetailPresenceProjection(serverId, agentId)

        registry.evict(
            owner = owner,
            memberId = agentId.value,
            writeGeneration = registry.writeGeneration
        )
        store.applyAgentDetailSnapshot(
            heldRequest,
            detail(summaryStatus = "working", summaryActivity = "working")
        )

        assertNull(registry.factFor(owner, agentId.value))
    }

    private fun presence(
        status: AgentStatus,
        serverSeq: Long,
        detail: String = status.label
    ) =
        MemberPresenceUpdated(
            memberId = agentId,
            presence = status.presence,
            kind = MemberKind.Agent,
            agentStatus = status,
            statusLabel = detail,
            serverSeq = serverSeq
        )

    private fun detail(
        summaryStatus: String,
        summaryActivity: String,
        trajectoryStatus: String? = null
    ) =
        AgentDetailSnapshot(
            agentId = agentId,
            summary =
                AgentDetailSummary(
                    id = agentId.value,
                    name = "Agent One",
                    status = summaryStatus,
                    activity = summaryActivity,
                    activityDetail = "Current detail"
                ),
            directMessages = emptyList(),
            reminders = emptyList(),
            activity = trajectoryStatus?.let { listOf(statusEntry(it, timestamp = "999")) }.orEmpty()
        )

    private fun statusEntry(status: String, timestamp: String?) =
        AgentActivityEntry(
            id = "activity-$status-$timestamp",
            type = "status",
            title = status,
            timestamp = timestamp,
            statusActivity = status,
            statusDetail = status
        )

    // task #1119: external-ness lives on the authoritative profile; a live presence fact
    // (which never carries it) must not erase it, and a managed agent must not gain it.
    @Test
    fun externalFlagSurvivesLivePresenceFactsAndStaysOffForManagedAgents() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val store = SharedServerMembersStore(PresenceNoopMemberRepository, presenceRegistry = registry)
        val externalAgent =
            MemberProfile(
                id = agentId,
                displayName = "Bridge",
                handle = "bridge",
                kind = MemberKind.Agent,
                presence = Presence.Active,
                agentStatus = AgentStatus.Online,
                isExternal = true,
                serverId = serverId
            )
        val managedAgent =
            externalAgent.copy(
                id = MemberId("agent-2"),
                displayName = "Claude",
                handle = "claude",
                isExternal = false
            )
        store.seed(
            MemberSnapshot(
                ServerSummary(id = serverId, name = "Workspace", slug = "workspace"),
                listOf(externalAgent, managedAgent)
            )
        )

        val seeded = store.memberStatusFlow(serverId, agentId.value).first()
        assertEquals(AgentStatus.Online, seeded?.status)
        assertTrue(seeded?.external == true, "profile external flag reaches the status seam")
        assertFalse(store.memberStatusFlow(serverId, "agent-2").first()?.external == true)

        registry.apply(
            owner,
            presence(AgentStatus.Working, serverSeq = 5L, detail = "Running"),
            registry.writeGeneration,
            ProcessPresenceTransportEpoch("family-1", 1L)
        )
        val live = store.memberStatusFlow(serverId, agentId.value).first()
        assertEquals(AgentStatus.Working, live?.status, "liveness still flows")
        assertEquals("Running", live?.statusLabel)
        assertTrue(live?.external == true, "presence fact must not erase external")
    }
}
