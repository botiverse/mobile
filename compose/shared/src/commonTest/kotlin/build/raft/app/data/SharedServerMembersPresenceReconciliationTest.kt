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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    // Hands f75b5d1c: on a server switch the Members page / Home load (not forced) joins the
    // reconnect's forced roster flight and publishes that version first. The forced caller then
    // sees version_unchanged; it must still project roster status into the rebaselined registry,
    // or every agent reads "offline" until its next realtime event.
    @Test
    fun forcedRosterProjectsStatusWhenJoinedNonForcedCallerPublishedFirst() = runTest {
        val registry = ProcessMemberPresenceRegistry()
        val release = CompletableDeferred<Unit>()
        val forcedPublication = CompletableDeferred<Unit>()
        var publications = 0
        val fetch: suspend (DataRequestPriority, ServerId) -> DataResult<MemberSnapshot> = { _, id ->
            release.await()
            DataResult.Content(
                MemberSnapshot(
                    server = ServerSummary(id = id, name = "Raft"),
                    members =
                        listOf(
                            MemberProfile(
                                id = agentId,
                                displayName = "Agent One",
                                handle = "agent-one",
                                kind = MemberKind.Agent,
                                presence = AgentStatus.Working.presence,
                                agentStatus = AgentStatus.Working,
                                serverId = id
                            )
                        )
                )
            )
        }
        val store =
            SharedServerMembersStore(
                memberRepository = PresenceNoopMemberRepository,
                processCore = ProcessServerMembersCore(fetchMembers = fetch, scope = backgroundScope),
                // The forced (first-started) caller is held at publication so the joined
                // non-forced caller publishes the version first, as on device.
                beforeProcessPublication = {
                    publications += 1
                    if (publications == 1) forcedPublication.await()
                },
                presenceRegistry = registry
            )
        registry.rebaselineTransport(
            owner,
            registry.writeGeneration,
            ProcessPresenceTransportEpoch("family-1", 1L)
        )

        val reconnect =
            async {
                store.refreshMembers(
                    serverId,
                    priority = DataRequestPriority.Background,
                    force = true,
                    reason = "presence_transport_connected"
                )
            }
        runCurrent()
        val membersPage =
            async { store.refreshMembers(serverId, priority = DataRequestPriority.Visible, reason = "members_page") }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(DataResult.Content(Unit), membersPage.await())
        // The non-forced caller joined a network flight while the reconnect refill is pending, so it
        // writes the roster's status too (Hands 81ad5dae); the forced caller's later write agrees.
        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId.value)?.agentStatus)

        forcedPublication.complete(Unit)
        assertEquals(DataResult.Content(Unit), reconnect.await())

        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId.value)?.agentStatus)
        assertEquals(AgentStatus.Working, store.memberStatusFlow(serverId, agentId.value).first()?.status)
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
        // PR #3856: the rebaseline carries the last known fact over (seq dropped, lowest
        // precedence) instead of wiping it; the held pre-advance detail still cannot write.
        assertCarriedOver(registry, AgentStatus.Offline)

        registry.apply(
            owner,
            presence(AgentStatus.Offline, serverSeq = 11L, detail = "Late old socket"),
            registry.writeGeneration,
            firstTransport
        )
        // The late old-socket event is rejected: the carried fact stays, still marked carried.
        assertCarriedOver(registry, AgentStatus.Offline)

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
        assertTrue(agentId.value !in registry.carriedOverMemberIds(owner))
        // PR #3856: the other agent's old-epoch fact is carried over without its seq, not wiped.
        val carried = registry.factFor(owner, otherAgentId.value)
        assertEquals(AgentStatus.Offline, carried?.agentStatus)
        assertNull(carried?.serverSeq)
        assertEquals(setOf(otherAgentId.value), registry.carriedOverMemberIds(owner))
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

        // PR #3856: only this owner's facts are carried over (seq dropped); others are untouched.
        assertCarriedOver(registry, AgentStatus.Offline)
        assertEquals(20L, registry.factFor(otherAccount, agentId.value)?.serverSeq)
        assertTrue(registry.carriedOverMemberIds(otherAccount).isEmpty())
        assertTrue(registry.carriedOverMemberIds(otherServer).isEmpty())
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

    /** `/members` answers: an `http_0` error while [failures] > 0, then the agent at [status]. */
    private class FlakyRosterRepository(
        private val serverId: ServerId,
        private val agentId: MemberId,
        var failures: Int,
        var status: AgentStatus = AgentStatus.Online,
    ) : MemberRepository by PresenceNoopMemberRepository {
        var calls = 0

        override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
            calls += 1
            if (failures > 0) {
                failures -= 1
                return DataResult.Error(ScreenError(code = "http_0", retryable = true))
            }
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
                                presence = status.presence,
                                agentStatus = status,
                                serverId = serverId
                            )
                        )
                )
            )
        }
    }

    @Test
    fun failedReconnectRefillIsRepairedByTheNextNonForcedLoad() = runBlocking {
        // Hands 81ad5dae: the forced refill after the rebaseline failed (cancelled in the background,
        // http_0); a later ordinary load succeeded but wrote no status, so every agent stayed offline.
        val registry = ProcessMemberPresenceRegistry()
        val repository = FlakyRosterRepository(serverId, agentId, failures = 1)
        val store = SharedServerMembersStore(repository, presenceRegistry = registry)
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        assertTrue(registry.needsRosterRefill(owner))

        val refill = store.refreshAfterPresenceTransportConnected(serverId, transport, reason = "presence_transport_connected")
        assertEquals("http_0", (refill as DataResult.Error).error.code)
        assertNull(registry.factFor(owner, agentId.value))
        assertTrue(registry.needsRosterRefill(owner), "a failed refill leaves it pending")

        assertEquals(DataResult.Content(Unit), store.refreshMembers(serverId, force = false, reason = "members_page"))

        assertEquals(AgentStatus.Online, registry.factFor(owner, agentId.value)?.agentStatus)
        assertEquals(AgentStatus.Online, store.memberStatusFlow(serverId, agentId.value).first()?.status)
        assertFalse(registry.needsRosterRefill(owner))
    }

    @Test
    fun nonForcedLoadWithoutAPendingRefillWritesNoStatus() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val repository = FlakyRosterRepository(serverId, agentId, failures = 0)
        val store = SharedServerMembersStore(repository, presenceRegistry = registry)

        store.refreshMembers(serverId, force = false, reason = "members_page")

        assertFalse(registry.needsRosterRefill(owner))
        assertNull(registry.factFor(owner, agentId.value), "no rebaseline: realtime owns presence, as before")
    }

    @Test
    fun cachedRosterReadBeforeTheRebaselineDoesNotCountAsTheRefill() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val repository = FlakyRosterRepository(serverId, agentId, failures = 0, status = AgentStatus.Working)
        val store = SharedServerMembersStore(repository, presenceRegistry = registry)
        store.refreshMembers(serverId, force = false, reason = "initial")
        // Sync-on reconnect: the rebaseline does not retire the process roster cache.
        registry.rebaselineTransport(owner, registry.writeGeneration, ProcessPresenceTransportEpoch("family-1", 1L))
        repository.status = AgentStatus.Online

        store.refreshMembers(serverId, force = false, reason = "home")

        assertEquals(1, repository.calls, "answered from the process cache")
        assertNull(registry.factFor(owner, agentId.value), "a pre-rebaseline read is not the current status")
        assertTrue(registry.needsRosterRefill(owner))
    }

    @Test
    fun failedReconnectRefillIsRetriedOnceOnTheSameTransport() = runTest {
        val registry = ProcessMemberPresenceRegistry()
        val repository = FlakyRosterRepository(serverId, agentId, failures = 1)
        val store =
            SharedServerMembersStore(
                memberRepository = repository,
                processCore = ProcessServerMembersCore(fetchMembers = repository::getMembers, scope = backgroundScope),
                presenceRegistry = registry
            )
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        val logs = mutableListOf<String>()
        val refetch =
            PresenceTransportRosterRefetch(
                jitter = RaftReconnectRefetchJitter(scope = backgroundScope),
                invalidate = { _, _ -> },
                storesForServer = { listOf(store) },
                warnLogger = { logs += it },
                retryScope = backgroundScope
            )

        val decision = refetch.onTransportRebaselined(owner, transport, ProcessPresenceTransportRebaselineResult.Applied)

        assertEquals(RaftReconnectRefetchDecision.Immediate, decision)
        assertEquals(1, repository.calls)
        assertNull(registry.factFor(owner, agentId.value))
        assertTrue(logs.any { "refresh_failed=http_0" in it && "retry=scheduled" in it }, "$logs")

        advanceTimeBy(SharedServerMembersPresenceRefillRetryDelay.inWholeMilliseconds - 1L)
        runCurrent()
        assertEquals(1, repository.calls, "the retry waits its backoff")
        advanceTimeBy(2L)
        runCurrent()

        assertEquals(2, repository.calls)
        assertEquals(AgentStatus.Online, registry.factFor(owner, agentId.value)?.agentStatus)
        assertFalse(registry.needsRosterRefill(owner))
    }

    @Test
    fun failedRefillIsNotRetriedOnceANewerConnectOwnsTheRepair() = runTest {
        val registry = ProcessMemberPresenceRegistry()
        val repository = FlakyRosterRepository(serverId, agentId, failures = 1)
        val store =
            SharedServerMembersStore(
                memberRepository = repository,
                processCore = ProcessServerMembersCore(fetchMembers = repository::getMembers, scope = backgroundScope),
                presenceRegistry = registry
            )
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        val refetch =
            PresenceTransportRosterRefetch(
                jitter = RaftReconnectRefetchJitter(scope = backgroundScope),
                invalidate = { _, _ -> },
                storesForServer = { listOf(store) },
                retryScope = backgroundScope
            )
        refetch.onTransportRebaselined(owner, transport, ProcessPresenceTransportRebaselineResult.Applied)
        assertEquals(1, repository.calls)

        registry.rebaselineTransport(owner, registry.writeGeneration, ProcessPresenceTransportEpoch("family-1", 2L))
        advanceTimeBy(SharedServerMembersPresenceRefillRetryDelay.inWholeMilliseconds + 1L)
        runCurrent()

        assertEquals(1, repository.calls, "the epoch moved: the retry is skipped")
        assertTrue(registry.needsRosterRefill(owner))
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

    private fun assertCarriedOver(registry: ProcessMemberPresenceRegistry, status: AgentStatus) {
        val fact = registry.factFor(owner, agentId.value)
        assertEquals(status, fact?.agentStatus)
        assertNull(fact?.serverSeq)
        assertTrue(agentId.value in registry.carriedOverMemberIds(owner))
    }
}
