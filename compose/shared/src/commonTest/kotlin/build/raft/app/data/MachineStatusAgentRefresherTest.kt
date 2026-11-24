@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `#proj-mobile:#68` item 6 (web `executeMachineReloadRecovery` parity, slock `socketBridge.ts` /
 * `agentOrchestrator.ts`): after a server restart or daemon reconnect, the server emits
 * `machine:status` without pushing `agent:activity_state: "online"` for idle agents. Re-reading
 * `/agents` via [MachineStatusAgentRefresher] recovers agent online presence while folding bursts
 * and respecting server selection, access loss, logout, and pre-event in-flight reads.
 */
class MachineStatusAgentRefresherTest {
    private val serverId = ServerId("server-1")
    private val otherServerId = ServerId("server-2")
    private val owner = ProcessServerMembersOwner("local", "local", serverId.value)

    private class ScriptedAgentsRepository(
        var membersJson: String,
        var agentsJson: String = membersJson,
    ) : MemberRepository by PresenceNoopMemberRepository {
        var memberCalls = 0
        var agentCalls = 0
        val membersJsonByServerId = mutableMapOf<String, String>()
        val agentsJsonByServerId = mutableMapOf<String, String>()
        val startedByServerId = mutableMapOf<String, CompletableDeferred<Unit>>()
        val gatesByServerId = mutableMapOf<String, CompletableDeferred<Unit>>()
        var nextAgentsGate: CompletableDeferred<Unit>? = null
        var nextAgentsStarted: CompletableDeferred<Unit>? = null

        override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
            memberCalls += 1
            val snapshot = membersJsonByServerId[serverId.value] ?: membersJson
            val members = assertNotNull(parseSharedSettingsAgentsAsMembers(snapshot))
            return DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Raft"), members))
        }

        override suspend fun getServerAgents(priority: DataRequestPriority, serverId: ServerId): DataResult<List<MemberProfile>> {
            agentCalls += 1
            val snapshot = agentsJsonByServerId[serverId.value] ?: agentsJson
            startedByServerId.remove(serverId.value)?.complete(Unit)
            nextAgentsStarted?.also { nextAgentsStarted = null }?.complete(Unit)
            gatesByServerId.remove(serverId.value)?.await()
            nextAgentsGate?.also { nextAgentsGate = null }?.await()
            val agents = assertNotNull(parseSharedSettingsAgentsAsMembers(snapshot))
            return DataResult.Content(agents)
        }
    }

    private fun TestScope.createStore(
        repository: ScriptedAgentsRepository,
        registry: ProcessMemberPresenceRegistry,
        core: ProcessServerMembersCore =
            ProcessServerMembersCore(
                fetchMembers = repository::getMembers,
                fetchAgents = repository::getServerAgents,
                scope = backgroundScope,
                timeSource = testScheduler.timeSource,
            ),
    ): SharedServerMembersStore =
        SharedServerMembersStore(
            memberRepository = repository,
            processCore = core,
            presenceRegistry = registry,
        )

    private suspend fun createRealtimeAuthority(vararg serverIds: String): Pair<RealtimeAuthorityRegistry, RealtimeGrantRecord> {
        val authority =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "machine-status-test",
            )
        authority.initialize()
        authority.advanceAuth(
            RealtimeAuthRevision(
                generation = 1L,
                session = RealtimeAuthoritySession("account-1", serverIds.toList(), environmentId = "env-test"),
            )
        )
        val lease = authority.register("process-realtime-host")
        val grant = requireNotNull(authority.currentGrant(lease.id, serverIds.first()))
        return authority to grant
    }

    @Test
    fun machineStatusRecoversOfflineAgentsAfterServerRestart() = runTest {
        val initialJson =
            """
            [
              {"id":"agent-idle","name":"idle","status":"active","activity":"online","serverId":"server-1"},
              {"id":"agent-kind","name":"kind","status":"active","activityKind":"online","serverId":"server-1"},
              {"id":"agent-stopped","name":"stopped","status":"stopped","activity":"offline","serverId":"server-1"}
            ]
            """.trimIndent()
        val offlineDuringRestartJson =
            """
            [
              {"id":"agent-idle","name":"idle","status":"active","activity":"offline","serverId":"server-1"},
              {"id":"agent-kind","name":"kind","status":"active","activityKind":"offline","serverId":"server-1"},
              {"id":"agent-stopped","name":"stopped","status":"stopped","activity":"offline","serverId":"server-1"}
            ]
            """.trimIndent()
        val recoveredJson =
            """
            [
              {"id":"agent-idle","name":"idle","status":"active","activity":"online","serverId":"server-1"},
              {"id":"agent-kind","name":"kind","status":"active","activityKind":"online","serverId":"server-1"},
              {"id":"agent-stopped","name":"stopped","status":"stopped","activity":"offline","serverId":"server-1"}
            ]
            """.trimIndent()

        val registry = ProcessMemberPresenceRegistry()
        val repository = ScriptedAgentsRepository(membersJson = initialJson, agentsJson = offlineDuringRestartJson)
        val store = createStore(repository, registry)
        val refresher =
            MachineStatusAgentRefresher(
                scope = backgroundScope,
                storesForActiveServer = { id -> if (id == serverId.value) listOf(store) else emptyList() },
                windowMillis = 1_500L,
                timeSource = testScheduler.timeSource,
            )
        val (authority, grant) = createRealtimeAuthority(serverId.value)
        val domainOwner =
            SharedRealtimeDomainEventOwner(runIfCurrentGrant = authority::runIfCurrentGrant).also { owner ->
                owner.installMachineStatusSink { targetServerId, _ ->
                    refresher.onMachineStatus(targetServerId)
                }
            }

        // Before restart: connected on epoch 1, idle agents online.
        registry.rebaselineTransport(owner, registry.writeGeneration, ProcessPresenceTransportEpoch("family-1", 1L))
        assertEquals(DataResult.Content(Unit), store.refreshMembers(serverId, force = true, reason = "initial"))
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, "agent-idle")?.status)
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, "agent-kind")?.status)
        assertEquals(AgentStatus.Offline, store.currentMemberStatus(serverId, "agent-stopped")?.status)

        // Server restarts: socket reconnects on epoch 2 BEFORE daemons reconnect, so reconnect
        // /agents observes offline for every agent.
        registry.rebaselineTransport(owner, registry.writeGeneration, ProcessPresenceTransportEpoch("family-1", 2L))
        assertEquals(DataResult.Content(Unit), store.refreshAgents(serverId, reason = "presence_transport_connected"))
        assertEquals(AgentStatus.Offline, store.currentMemberStatus(serverId, "agent-idle")?.status)
        assertEquals(AgentStatus.Offline, store.currentMemberStatus(serverId, "agent-kind")?.status)
        assertEquals(1, repository.agentCalls)

        // Daemons reconnect to the restarted server: server emits machine:status (no agent:activity_state: "online").
        // Advance virtual clock so machine:status arrives strictly after the reconnect /agents read started.
        advanceTimeBy(100L)
        repository.agentsJson = recoveredJson
        val machineOnline =
            assertIs<RealtimeRefreshRequired>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.MachineStatus,
                    """{"machineId":"machine-1","status":"online","statusVersion":2}""",
                )
            )
        assertTrue(domainOwner.acceptProcessMutation(grant, machineOnline, source = "process_normal_realtime"))

        advanceTimeBy(1_501L)
        runCurrent()

        assertEquals(2, repository.agentCalls)
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-idle")?.agentStatus)
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, "agent-idle")?.status)
        assertEquals(Presence.Active, store.currentMemberStatus(serverId, "agent-idle")?.presence)
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-kind")?.agentStatus)
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, "agent-kind")?.status)
        // A genuinely stopped agent stays offline.
        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-stopped")?.agentStatus)
        assertEquals(AgentStatus.Offline, store.currentMemberStatus(serverId, "agent-stopped")?.status)
    }

    @Test
    fun machineStatusRecoversSeqCarryingOfflineAgentOnSameTransport() = runTest {
        val onlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"online","serverId":"server-1"}]"""
        val registry = ProcessMemberPresenceRegistry()
        val repository = ScriptedAgentsRepository(membersJson = onlineJson, agentsJson = onlineJson)
        val store = createStore(repository, registry)
        val refresher =
            MachineStatusAgentRefresher(
                scope = backgroundScope,
                storesForActiveServer = { id -> if (id == serverId.value) listOf(store) else emptyList() },
                windowMillis = 1_500L,
                timeSource = testScheduler.timeSource,
            )

        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "initial")

        // Daemon disconnects on the same socket: server emits seq-carrying offline.
        registry.apply(
            owner = owner,
            event =
                MemberPresenceUpdated(
                    memberId = MemberId("agent-1"),
                    presence = Presence.Offline,
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Offline,
                    statusLabel = AgentStatus.Offline.label,
                    serverSeq = 15L,
                    coarseState = true,
                ),
            writeGeneration = registry.writeGeneration,
            transport = transport,
        )
        assertEquals(AgentStatus.Offline, store.currentMemberStatus(serverId, "agent-1")?.status)

        // Daemon reconnects -> machine:status triggers fresh /agents read that replaces seq-carrying Offline
        // while keeping the seq=15 floor intact.
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(1_501L)
        runCurrent()

        val recovered = assertNotNull(registry.factFor(owner, "agent-1"))
        assertEquals(AgentStatus.Online, recovered.agentStatus)
        assertEquals(15L, recovered.serverSeq)
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, "agent-1")?.status)

        // Delayed older seq cannot roll the recovered Online status back to Offline.
        registry.apply(
            owner = owner,
            event =
                MemberPresenceUpdated(
                    memberId = MemberId("agent-1"),
                    presence = Presence.Offline,
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Offline,
                    serverSeq = 14L,
                ),
            writeGeneration = registry.writeGeneration,
            transport = transport,
        )
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)
    }

    @Test
    fun machineStatusSupersedesPreEventInFlightAgentsFetch() = runTest {
        val offlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"offline","serverId":"server-1"}]"""
        val onlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"online","serverId":"server-1"}]"""
        val registry = ProcessMemberPresenceRegistry()
        val repository = ScriptedAgentsRepository(membersJson = offlineJson, agentsJson = offlineJson)
        val sharedCore =
            ProcessServerMembersCore(
                fetchMembers = repository::getMembers,
                fetchAgents = repository::getServerAgents,
                scope = backgroundScope,
                timeSource = testScheduler.timeSource,
            )
        val store = createStore(repository, registry, sharedCore)
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "initial")
        assertEquals(AgentStatus.Offline, store.currentMemberStatus(serverId, "agent-1")?.status)

        val refresher =
            MachineStatusAgentRefresher(
                scope = backgroundScope,
                storesForActiveServer = { id -> if (id == serverId.value) listOf(store) else emptyList() },
                windowMillis = 1_500L,
                timeSource = testScheduler.timeSource,
            )

        // Pre-event /agents fetch starts and hangs before machine:status arrives.
        val preEventStarted = CompletableDeferred<Unit>()
        val preEventRelease = CompletableDeferred<Unit>()
        repository.nextAgentsStarted = preEventStarted
        repository.nextAgentsGate = preEventRelease
        val staleRefresh = async { store.refreshAgents(serverId, reason = "pre_event_refetch") }
        preEventStarted.await()
        assertEquals(1, repository.agentCalls)

        // Advance time so machine:status has a strictly newer boundary than the pre-event flight.
        advanceTimeBy(100L)
        repository.agentsJson = onlineJson
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(1_501L)
        runCurrent()

        // The pre-event flight was detached and replaced by a fresh post-event /agents fetch.
        assertEquals(2, repository.agentCalls)
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)

        // When the stale pre-event flight finally returns offline, it must not clobber Online.
        preEventRelease.complete(Unit)
        staleRefresh.await()
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, "agent-1")?.status)
    }

    @Test
    fun machineStatusSkipsInactiveServerAndCancelsOnSwitchAccessLossOrLogout() = runTest {
        val onlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"online","serverId":"server-1"}]"""
        val registry = ProcessMemberPresenceRegistry()
        val repository = ScriptedAgentsRepository(membersJson = onlineJson, agentsJson = onlineJson)
        val store = createStore(repository, registry)
        store.refreshMembers(serverId, force = true, reason = "initial")

        var activeServerId: String? = serverId.value
        val refresher =
            MachineStatusAgentRefresher(
                scope = backgroundScope,
                storesForActiveServer = { id -> if (id == activeServerId) listOf(store) else emptyList() },
                windowMillis = 1_500L,
                timeSource = testScheduler.timeSource,
            )

        // 1. Unselected server is ignored immediately.
        refresher.onMachineStatus(otherServerId.value)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(0, repository.agentCalls)

        // 2. User switches away before the debounce window fires: nothing runs.
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(500L)
        activeServerId = otherServerId.value
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(0, repository.agentCalls)

        // 3. Server access loss cancels pending work for that server.
        activeServerId = serverId.value
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(500L)
        refresher.cancel(serverId.value)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(0, repository.agentCalls)

        // 4. Logout / account switch cancels all pending work.
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(500L)
        refresher.cancelAll()
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(0, repository.agentCalls)
    }

    @Test
    fun eventArrivingWhileRefreshIsInFlightSchedulesFollowUpWithOwnTimestamp() = runTest {
        val offlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"offline","serverId":"server-1"}]"""
        val onlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"online","serverId":"server-1"}]"""
        val registry = ProcessMemberPresenceRegistry()
        val repository = ScriptedAgentsRepository(membersJson = offlineJson, agentsJson = offlineJson)
        val core =
            ProcessServerMembersCore(
                fetchMembers = repository::getMembers,
                fetchAgents = repository::getServerAgents,
                scope = backgroundScope,
                timeSource = testScheduler.timeSource,
            )
        val store = createStore(repository, registry, core)
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "initial")

        val refresher =
            MachineStatusAgentRefresher(
                scope = backgroundScope,
                storesForActiveServer = { id -> if (id == serverId.value) listOf(store) else emptyList() },
                windowMillis = 1_500L,
                minRefreshIntervalMillis = 0L,
                timeSource = testScheduler.timeSource,
            )

        // t=0: first machine:status; at t=1500 flight 1 starts with Offline and stays in flight.
        val flight1Started = CompletableDeferred<Unit>()
        val flight1Gate = CompletableDeferred<Unit>()
        repository.nextAgentsStarted = flight1Started
        repository.nextAgentsGate = flight1Gate
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(1_501L)
        runCurrent()
        flight1Started.await()
        assertEquals(1, repository.agentCalls)

        // t=1700: a second machine:status arrives while flight 1 is still in flight; it schedules
        // a follow-up with arrival timestamp t=1700 (> flight 1's startedAt t=1500).
        advanceTimeBy(199L)
        refresher.onMachineStatus(serverId.value)

        // t=1800: flight 1 finishes Offline (caching startedAt=t=1500 inside coalesceFreshWithin).
        advanceTimeBy(100L)
        repository.agentsJson = onlineJson
        flight1Gate.complete(Unit)
        runCurrent()
        assertEquals(1, repository.agentCalls)
        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-1")?.agentStatus)

        // t=3201: the follow-up fires with notStartedBefore=t=1700 > t=1500, issuing a fresh read
        // instead of reusing flight 1's cached Offline answer.
        advanceTimeBy(1_401L)
        runCurrent()
        assertEquals(2, repository.agentCalls)
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, "agent-1")?.status)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, repository.agentCalls)
    }

    @Test
    fun hasLoadedNonDurableRosterUsesPresenceOwnerAndExcludesDurableSeeds() = runTest {
        val onlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"online","serverId":"server-1"}]"""
        val storage =
            object : RaftServerMembersDurableStorage {
                private val rows = linkedMapOf<String, String>()

                override suspend fun read(id: String): String? = rows[id]

                override suspend fun write(id: String, value: String) {
                    rows[id] = value
                }

                override suspend fun delete(id: String) {
                    rows.remove(id)
                }

                override suspend fun ids(): List<String> = rows.keys.toList()
            }
        val durable = RaftServerMembersDurableStore(storage = storage, scope = backgroundScope)
        val repository = ScriptedAgentsRepository(membersJson = onlineJson, agentsJson = onlineJson)

        // Seed durable storage from an initial process store.
        val seedStore =
            SharedServerMembersStore(
                memberRepository = repository,
                processCore =
                    ProcessServerMembersCore(
                        fetchMembers = repository::getMembers,
                        fetchAgents = repository::getServerAgents,
                        scope = backgroundScope,
                        timeSource = testScheduler.timeSource,
                    ),
                durableStore = durable,
            )
        assertEquals(DataResult.Content(Unit), seedStore.refreshMembers(serverId, force = true, reason = "seed"))
        runCurrent()

        // Second store with a mutable presenceOwner (while processOwner stays fixed on "local")
        // proves hasLoadedNonDurableRoster resolves synchronously via presenceOwner.
        var activePresenceAccount: String? = "local"
        val store =
            SharedServerMembersStore(
                memberRepository = repository,
                processCore =
                    ProcessServerMembersCore(
                        fetchMembers = repository::getMembers,
                        fetchAgents = repository::getServerAgents,
                        scope = backgroundScope,
                        timeSource = testScheduler.timeSource,
                    ),
                processOwner = { id -> ProcessServerMembersOwner("local", "local", id.value) },
                presenceOwner = { id ->
                    activePresenceAccount?.let { account -> ProcessServerMembersOwner("local", account, id.value) }
                },
                durableStore = durable,
            )

        assertFalse(store.hasLoadedNonDurableRoster(serverId))
        assertFalse(store.hasLoadedNonDurableRoster(ServerId("   ")))

        // Hydrated from durable cache only: still NOT a non-durable loaded roster.
        assertTrue(store.hydrateFromDurable(serverId))
        assertFalse(store.hasLoadedNonDurableRoster(serverId))

        // Authoritative network load flips it to true.
        assertEquals(DataResult.Content(Unit), store.refreshMembers(serverId, force = true, reason = "network"))
        assertTrue(store.hasLoadedNonDurableRoster(serverId))

        // Retiring or switching presenceOwner (even while processOwner still points at "local")
        // synchronously reports false until presenceOwner matches again.
        activePresenceAccount = null
        assertFalse(store.hasLoadedNonDurableRoster(serverId))
        activePresenceAccount = "other-account"
        assertFalse(store.hasLoadedNonDurableRoster(serverId))
        activePresenceAccount = "local"
        assertTrue(store.hasLoadedNonDurableRoster(serverId))
    }

    @Test
    fun machineStatusStormRespectsMinRefreshIntervalAndCoalescesTrailingRecovery() = runTest {
        val offlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"offline","serverId":"server-1"}]"""
        val onlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"online","serverId":"server-1"}]"""
        val registry = ProcessMemberPresenceRegistry()
        val repository = ScriptedAgentsRepository(membersJson = offlineJson, agentsJson = offlineJson)
        val core =
            ProcessServerMembersCore(
                fetchMembers = repository::getMembers,
                fetchAgents = repository::getServerAgents,
                scope = backgroundScope,
                timeSource = testScheduler.timeSource,
            )
        val store = createStore(repository, registry, core)
        val transport = ProcessPresenceTransportEpoch("family-1", 1L)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        store.refreshMembers(serverId, force = true, reason = "initial")

        val refreshStartedTimes = mutableListOf<Long>()
        val refresher =
            MachineStatusAgentRefresher(
                scope = backgroundScope,
                storesForActiveServer = { id -> if (id == serverId.value) listOf(store) else emptyList() },
                windowMillis = 1_500L,
                minRefreshIntervalMillis = 15_000L,
                timeSource = testScheduler.timeSource,
                logger = { line ->
                    if (line.startsWith("machine_status_agents_refresh ")) {
                        refreshStartedTimes += testScheduler.currentTime
                    }
                },
            )

        // t=0: first machine:status after idle runs promptly after windowMillis (1.5s).
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, repository.agentCalls)
        assertEquals(listOf(1_500L), refreshStartedTimes)

        // Storm of machine:status events every 3s for 30s; agent flips online near the end of cooldown.
        for (step in 1..10) {
            advanceTimeBy(3_000L)
            if (step == 10) repository.agentsJson = onlineJson
            refresher.onMachineStatus(serverId.value)
            runCurrent()
        }
        // Let the final coalesced trailing refresh fire after cooldown elapses.
        advanceTimeBy(20_000L)
        runCurrent()

        assertTrue(repository.agentCalls <= 3, "30s storm must stay <= 3 /agents fetches, got ${repository.agentCalls}")
        refreshStartedTimes.zipWithNext().forEach { (prev, next) ->
            assertTrue(
                next - prev >= 15_000L,
                "refreshes must be spaced by at least minRefreshIntervalMillis: $refreshStartedTimes",
            )
        }
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, "agent-1")?.status)
    }

    @Test
    fun cancelAndCancelAllResetCooldownAndServersAreIsolated() = runTest {
        val onlineJson1 =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"online","serverId":"server-1"}]"""
        val onlineJson2 =
            """[{"id":"agent-2","name":"a2","status":"active","activity":"online","serverId":"server-2"}]"""
        val registry = ProcessMemberPresenceRegistry()
        val repository = ScriptedAgentsRepository(membersJson = onlineJson1, agentsJson = onlineJson1).apply {
            membersJsonByServerId[serverId.value] = onlineJson1
            agentsJsonByServerId[serverId.value] = onlineJson1
            membersJsonByServerId[otherServerId.value] = onlineJson2
            agentsJsonByServerId[otherServerId.value] = onlineJson2
        }
        val core =
            ProcessServerMembersCore(
                fetchMembers = repository::getMembers,
                fetchAgents = repository::getServerAgents,
                scope = backgroundScope,
                timeSource = testScheduler.timeSource,
            )
        val store1 = createStore(repository, registry, core)
        val store2 = createStore(repository, registry, core)
        store1.refreshMembers(serverId, force = true, reason = "initial")
        store2.refreshMembers(otherServerId, force = true, reason = "initial")

        val refresher =
            MachineStatusAgentRefresher(
                scope = backgroundScope,
                storesForActiveServer = { id ->
                    when (id) {
                        serverId.value -> listOf(store1)
                        otherServerId.value -> listOf(store2)
                        else -> emptyList()
                    }
                },
                windowMillis = 1_500L,
                minRefreshIntervalMillis = 15_000L,
                timeSource = testScheduler.timeSource,
            )

        // First refresh on server-1 starts cooldown at t=1500.
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, repository.agentCalls)

        // server-2 is NOT throttled by server-1's cooldown: runs promptly in 1.5s.
        advanceTimeBy(500L)
        refresher.onMachineStatus(otherServerId.value)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(2, repository.agentCalls)

        // server-1 is still in cooldown: a push does not run in 1.5s.
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, repository.agentCalls)

        // cancel(server-1) clears cooldown so the next push on server-1 runs promptly in 1.5s.
        refresher.cancel(serverId.value)
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(3, repository.agentCalls)

        // cancelAll() clears cooldown too.
        refresher.cancelAll()
        advanceTimeBy(100L)
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(4, repository.agentCalls)
    }
}
