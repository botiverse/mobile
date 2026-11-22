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
        var nextAgentsGate: CompletableDeferred<Unit>? = null
        var nextAgentsStarted: CompletableDeferred<Unit>? = null

        override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
            memberCalls += 1
            val members = assertNotNull(parseSharedSettingsAgentsAsMembers(membersJson))
            return DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Raft"), members))
        }

        override suspend fun getServerAgents(priority: DataRequestPriority, serverId: ServerId): DataResult<List<MemberProfile>> {
            agentCalls += 1
            val snapshot = agentsJson
            nextAgentsStarted?.also { nextAgentsStarted = null }?.complete(Unit)
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
    fun machineStatusBurstCoalescesAcrossMultipleStoresAndSchedulesFollowUpOnLaterEvent() = runTest {
        val onlineJson =
            """[{"id":"agent-1","name":"a1","status":"active","activity":"online","serverId":"server-1"}]"""
        val registry = ProcessMemberPresenceRegistry()
        val repository = ScriptedAgentsRepository(membersJson = onlineJson, agentsJson = onlineJson)
        val sharedCore =
            ProcessServerMembersCore(
                fetchMembers = repository::getMembers,
                fetchAgents = repository::getServerAgents,
                scope = backgroundScope,
                timeSource = testScheduler.timeSource,
            )
        val shellStoreA = createStore(repository, registry, sharedCore)
        val shellStoreB = createStore(repository, registry, sharedCore)
        shellStoreA.refreshMembers(serverId, force = true, reason = "initial_a")
        shellStoreB.refreshMembers(serverId, force = true, reason = "initial_b")
        assertTrue(shellStoreA.hasLoadedNonDurableRoster(serverId))
        assertTrue(shellStoreB.hasLoadedNonDurableRoster(serverId))

        val refresher =
            MachineStatusAgentRefresher(
                scope = backgroundScope,
                storesForActiveServer = { id -> if (id == serverId.value) listOf(shellStoreA, shellStoreB) else emptyList() },
                windowMillis = 1_500L,
                timeSource = testScheduler.timeSource,
            )

        // Burst of 10 Computers reconnecting folds into one /agents fetch shared by both shell stores.
        repeat(10) { refresher.onMachineStatus(serverId.value) }
        advanceTimeBy(1_000L)
        refresher.onMachineStatus(serverId.value)
        runCurrent()
        assertEquals(0, repository.agentCalls)

        advanceTimeBy(501L)
        runCurrent()
        assertEquals(1, repository.agentCalls)

        // Another Computer reconnects later: because its timestamp is newer than the first fetch's
        // start, the refresher issues a fresh /agents read instead of reusing the earlier cached answer.
        advanceTimeBy(100L)
        refresher.onMachineStatus(serverId.value)
        advanceTimeBy(1_501L)
        runCurrent()
        assertEquals(2, repository.agentCalls)
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
    fun realtimeOwnerDispatchesOnlyMachineStatusToMachineStatusSink() = runTest {
        val (authority, grant) = createRealtimeAuthority(serverId.value)
        val received = mutableListOf<Pair<String, String>>()
        val domainOwner =
            SharedRealtimeDomainEventOwner(runIfCurrentGrant = authority::runIfCurrentGrant).also { owner ->
                owner.installMachineStatusSink { targetServerId, event ->
                    received += targetServerId to event.reason
                }
            }
        val mapper = RealtimeEventMapper()

        val machineOnline =
            assertNotNull(
                mapper.map(
                    RealtimeSocketEvents.MachineStatus,
                    """{"machineId":"m-1","status":"online","statusVersion":1}""",
                )
            )
        val machineOffline =
            assertNotNull(
                mapper.map(
                    RealtimeSocketEvents.MachineStatus,
                    """{"machineId":"m-1","status":"offline","statusVersion":2}""",
                )
            )
        val machineUpdated =
            assertNotNull(mapper.map(RealtimeSocketEvents.MachineUpdated, """{"machineId":"m-1"}"""))
        val machineCapabilities =
            assertNotNull(mapper.map(RealtimeSocketEvents.MachineCapabilities, """{"machineId":"m-1"}"""))
        val agentUpdated =
            assertNotNull(mapper.map(RealtimeSocketEvents.AgentUpdated, """{"agentId":"a-1"}"""))

        assertTrue(machineOnline.isMachineStatusUpdate())
        assertTrue(machineOffline.isMachineStatusUpdate())
        assertFalse(machineUpdated.isMachineStatusUpdate())
        assertFalse(machineCapabilities.isMachineStatusUpdate())
        assertFalse(agentUpdated.isMachineStatusUpdate())

        domainOwner.acceptProcessMutation(grant, machineOnline, source = "process_normal_realtime")
        domainOwner.acceptProcessMutation(grant, machineUpdated, source = "process_normal_realtime")
        domainOwner.acceptProcessMutation(grant, machineCapabilities, source = "process_normal_realtime")
        domainOwner.acceptProcessMutation(grant, agentUpdated, source = "process_normal_realtime")
        domainOwner.acceptProcessMutation(grant, machineOffline, source = "process_normal_realtime")

        assertEquals(
            listOf(
                serverId.value to RealtimeSocketEvents.MachineStatus,
                serverId.value to RealtimeSocketEvents.MachineStatus,
            ),
            received,
        )
    }
}
