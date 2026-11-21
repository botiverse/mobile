package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RaftRecentServerRefreshCoalescerTest {
    private val serverId = ServerId("server-1")
    private val owner = ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = serverId.value)

    @Test
    fun coalescesInFlightAndRecentSuccessfulMachineRefreshesUntilWindowExpiresOrInvalidated() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = testScheduler.timeSource)

        val first =
            async {
                coalescer.runOrCoalesce(serverId.value, reason = "foreground_reconcile", coalesceFreshWithin = 25.seconds) {
                    calls += 1
                    started.complete(Unit)
                    release.await()
                    true
                }
            }
        started.await()
        val joined =
            async {
                coalescer.runOrCoalesce(serverId.value, reason = "presence_transport_connected", coalesceFreshWithin = 25.seconds) {
                    calls += 1
                    true
                }
            }
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, first.await())
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.JoinedInFlight, joined.await())

        // 17 s later (within 25 s coalesce window): delayed reconnect skips redundant /machines fetch.
        advanceTimeBy(17_000L)
        val coalesced =
            coalescer.runOrCoalesce(serverId.value, reason = "presence_transport_connected", coalesceFreshWithin = 25.seconds) {
                calls += 1
                true
            }
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.FreshCoalesced, coalesced)
        assertEquals(1, calls)

        // Explicit non-coalesced caller (e.g. ImmediateAfterSwitch) still executes.
        val forced =
            coalescer.runOrCoalesce(serverId.value, reason = "server_switch") {
                calls += 1
                true
            }
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, forced)
        assertEquals(2, calls)

        // After the 25 s window expires, a coalescing caller fetches again.
        advanceTimeBy(26_000L)
        val afterExpiry =
            coalescer.runOrCoalesce(serverId.value, reason = "presence_transport_connected", coalesceFreshWithin = 25.seconds) {
                calls += 1
                true
            }
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, afterExpiry)
        assertEquals(3, calls)

        // Invalidation clears freshness immediately.
        coalescer.invalidate(serverId.value)
        val afterInvalidate =
            coalescer.runOrCoalesce(serverId.value, reason = "foreground_reconcile", coalesceFreshWithin = 25.seconds) {
                calls += 1
                true
            }
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, afterInvalidate)
        assertEquals(4, calls)
    }

    @Test
    fun failedMachineRefreshDoesNotSuppressNextCaller() = runTest {
        var calls = 0
        val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = testScheduler.timeSource)

        val failed =
            runCatching {
                coalescer.runOrCoalesce(serverId.value, reason = "foreground_reconcile", coalesceFreshWithin = 25.seconds) {
                    calls += 1
                    error("transient failure")
                }
            }
        assertTrue(failed.isFailure)
        assertEquals(1, calls)

        val retry =
            coalescer.runOrCoalesce(serverId.value, reason = "presence_transport_connected", coalesceFreshWithin = 25.seconds) {
                calls += 1
                true
            }
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, retry)
        assertEquals(2, calls)
    }

    @Test
    fun nonThrowingUnsuccessfulMachineRefreshDoesNotCacheAsFresh() = runTest {
        var calls = 0
        val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = testScheduler.timeSource)

        // Simulates SharedComputersStore.reloadMachinesForSyncSucceeded returning false on HTTP error
        // while keeping its stale cached snapshot visible.
        val first =
            coalescer.runOrCoalesce(serverId.value, reason = "foreground_reconcile", coalesceFreshWithin = 25.seconds) {
                calls += 1
                false
            }
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, first)
        assertEquals(1, calls)

        // Subsequent reconnect refetch within 25 s must NOT be skipped as fresh_coalesced.
        advanceTimeBy(5_000L)
        val second =
            coalescer.runOrCoalesce(serverId.value, reason = "presence_transport_connected", coalesceFreshWithin = 25.seconds) {
                calls += 1
                true
            }
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, second)
        assertEquals(2, calls)

        // Once succeeded, a third caller within 25 s coalesces cleanly.
        advanceTimeBy(5_000L)
        val third =
            coalescer.runOrCoalesce(serverId.value, reason = "agent_status_reconcile", coalesceFreshWithin = 25.seconds) {
                calls += 1
                true
            }
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.FreshCoalesced, third)
        assertEquals(2, calls)
    }

    @Test
    fun foregroundReconcileAgentsAndMachinesCoalesceWithDelayedReconnectRefetch() = runTest {
        var memberCalls = 0
        var agentCalls = 0
        var machineCalls = 0
        val agent =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Agent One",
                handle = "agent-one",
                kind = MemberKind.Agent,
                presence = AgentStatus.Online.presence,
                agentStatus = AgentStatus.Online,
                serverId = serverId,
            )
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
                    memberCalls += 1
                    return DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Raft"), listOf(agent)))
                }

                override suspend fun getServerAgents(priority: DataRequestPriority, serverId: ServerId): DataResult<List<MemberProfile>> {
                    agentCalls += 1
                    return DataResult.Content(listOf(agent))
                }
            }
        val core =
            ProcessServerMembersCore(
                fetchMembers = repository::getMembers,
                fetchAgents = repository::getServerAgents,
                scope = backgroundScope,
                timeSource = testScheduler.timeSource,
            )
        val registry = ProcessMemberPresenceRegistry()
        val store =
            SharedServerMembersStore(
                memberRepository = repository,
                processCore = core,
                presenceRegistry = registry,
            )
        val machinesCoalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = testScheduler.timeSource)
        val jitter =
            RaftReconnectRefetchJitter(
                scope = backgroundScope,
                random = Random(3),
                maxMillis = RECONNECT_REFETCH_JITTER_MAX_MILLIS,
                timeSource = testScheduler.timeSource,
            )
        val refetch =
            PresenceTransportRosterRefetch(
                jitter = jitter,
                invalidate = core::invalidate,
                storesForServer = { listOf(store) },
                refreshMachinesWithCoalesce = { sid, window, notStartedBefore ->
                    machinesCoalescer.runOrCoalesce(
                        sid,
                        reason = PresenceTransportRosterRefetch.REASON,
                        coalesceFreshWithin = window,
                        notStartedBefore = notStartedBefore,
                    ) {
                        machineCalls += 1
                        true
                    }
                },
                retryScope = backgroundScope,
                timeSource = testScheduler.timeSource,
            )

        // Initial connect populates the roster once.
        val t1 = ProcessPresenceTransportEpoch("family-1", 1L)
        val r1 = registry.rebaselineTransport(owner, registry.writeGeneration, t1)
        assertEquals(RaftReconnectRefetchDecision.Immediate, refetch.onTransportRebaselined(owner, t1, r1, agentsOnlyOnReconnect = true))
        runCurrent()
        assertEquals(1, memberCalls)
        assertEquals(0, agentCalls)
        assertEquals(0, machineCalls)

        // Common foreground return: socket reconnects first and schedules the 0-20 s jittered
        // presence_transport_connected refetch; foreground reconcile then fetches /agents +
        // /machines AFTER the connect boundary.
        val t2 = ProcessPresenceTransportEpoch("family-1", 2L)
        val r2 = registry.rebaselineTransport(owner, registry.writeGeneration, t2)
        assertEquals(RaftReconnectRefetchDecision.Delayed, refetch.onTransportRebaselined(owner, t2, r2, agentsOnlyOnReconnect = true))

        advanceTimeBy(1_000L)
        store.refreshAgents(serverId, reason = "foreground_reconcile", coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW)
        machinesCoalescer.runOrCoalesce(
            serverId.value,
            reason = "foreground_reconcile",
            coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
        ) {
            machineCalls += 1
            true
        }
        assertEquals(1, agentCalls)
        assertEquals(1, machineCalls)

        // Jitter fires within 20 s (< 25 s coalesce window) after the post-connect reconcile: both
        // /agents and /machines coalesce with zero extra network calls.
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        runCurrent()
        assertEquals(1, agentCalls, "delayed reconnect must reuse post-connect foreground_reconcile /agents")
        assertEquals(1, machineCalls, "delayed reconnect must reuse post-connect foreground_reconcile /machines")
        assertEquals(AgentStatus.Online, registry.factFor(owner, "agent-1")?.agentStatus)

        // Explicit server switch back (ImmediateAfterSwitch) still refreshes immediately even within 25 s.
        jitter.onServerSelected(serverId.value, reason = "server_selected")
        val t3 = ProcessPresenceTransportEpoch("family-1", 3L)
        val r3 = registry.rebaselineTransport(owner, registry.writeGeneration, t3)
        assertEquals(RaftReconnectRefetchDecision.ImmediateAfterSwitch, refetch.onTransportRebaselined(owner, t3, r3, agentsOnlyOnReconnect = true))
        runCurrent()
        assertEquals(2, agentCalls)
        assertEquals(2, machineCalls)

        // Core invalidation clears recent /agents cache even within 25 s.
        core.invalidate(owner, reason = "sync_resnapshot")
        val freshAfterInvalidate =
            core.loadAgents(owner, DataRequestPriority.Background, reason = "presence_transport_connected", coalesceFreshWithin = 25.seconds)
        assertTrue(freshAfterInvalidate.startedByCaller)
        assertEquals(3, agentCalls)

        // Without invalidation and within window, a second coalesced read reuses cached result with startedByCaller=false.
        val reused =
            core.loadAgents(owner, DataRequestPriority.Background, reason = "presence_transport_connected", coalesceFreshWithin = 25.seconds)
        assertFalse(reused.startedByCaller)
        assertEquals(3, agentCalls)

        // Agent profile/avatar edits invalidate recentAgents so a coalesced refresh within 25 s fetches fresh data.
        core.invalidateRecentAgents(owner, reason = "agent_profile_updated")
        val afterAgentEdit =
            core.loadAgents(owner, DataRequestPriority.Background, reason = "presence_transport_connected", coalesceFreshWithin = 25.seconds)
        assertTrue(afterAgentEdit.startedByCaller)
        assertEquals(4, agentCalls)
    }

    @Test
    fun preConnectCompletedAndInFlightReadsNeverSatisfyReconnectRefetch() = runTest {
        var agentCalls = 0
        var machineCalls = 0
        val preConnectAgentsStarted = CompletableDeferred<Unit>()
        val releasePreConnectAgents = CompletableDeferred<Unit>()
        val preConnectMachinesStarted = CompletableDeferred<Unit>()
        val releasePreConnectMachines = CompletableDeferred<Unit>()
        var blockFirstInFlightAgents = false
        val agentOnline =
            MemberProfile(
                id = MemberId("agent-1"),
                displayName = "Agent One",
                handle = "agent-one",
                kind = MemberKind.Agent,
                presence = AgentStatus.Online.presence,
                agentStatus = AgentStatus.Online,
                serverId = serverId,
            )
        val agentOffline = agentOnline.copy(presence = AgentStatus.Offline.presence, agentStatus = AgentStatus.Offline)
        var currentAgent = agentOnline
        val repository =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> =
                    DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Raft"), listOf(currentAgent)))

                override suspend fun getServerAgents(priority: DataRequestPriority, serverId: ServerId): DataResult<List<MemberProfile>> {
                    val callIndex = ++agentCalls
                    if (blockFirstInFlightAgents) {
                        blockFirstInFlightAgents = false
                        preConnectAgentsStarted.complete(Unit)
                        releasePreConnectAgents.await()
                        return DataResult.Content(listOf(agentOnline))
                    }
                    return DataResult.Content(listOf(if (callIndex >= 2) agentOffline else currentAgent))
                }
            }
        val core =
            ProcessServerMembersCore(
                fetchMembers = repository::getMembers,
                fetchAgents = repository::getServerAgents,
                scope = backgroundScope,
                timeSource = testScheduler.timeSource,
            )
        val registry = ProcessMemberPresenceRegistry()
        val store =
            SharedServerMembersStore(
                memberRepository = repository,
                processCore = core,
                presenceRegistry = registry,
            )
        val machinesCoalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = testScheduler.timeSource)
        val jitter =
            RaftReconnectRefetchJitter(
                scope = backgroundScope,
                random = Random(3),
                maxMillis = RECONNECT_REFETCH_JITTER_MAX_MILLIS,
                timeSource = testScheduler.timeSource,
            )
        val refetch =
            PresenceTransportRosterRefetch(
                jitter = jitter,
                invalidate = core::invalidate,
                storesForServer = { listOf(store) },
                refreshMachinesWithCoalesce = { sid, window, notStartedBefore ->
                    machinesCoalescer.runOrCoalesce(
                        sid,
                        reason = PresenceTransportRosterRefetch.REASON,
                        coalesceFreshWithin = window,
                        notStartedBefore = notStartedBefore,
                    ) {
                        machineCalls += 1
                        true
                    }
                },
                retryScope = backgroundScope,
                timeSource = testScheduler.timeSource,
            )

        // Initial connect populates the roster once.
        val t1 = ProcessPresenceTransportEpoch("family-1", 1L)
        val r1 = registry.rebaselineTransport(owner, registry.writeGeneration, t1)
        assertEquals(RaftReconnectRefetchDecision.Immediate, refetch.onTransportRebaselined(owner, t1, r1, agentsOnlyOnReconnect = true))
        runCurrent()

        // t=0: foreground/periodic reconcile reads /agents + /machines while online.
        store.refreshAgents(serverId, reason = "foreground_reconcile", coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW)
        machinesCoalescer.runOrCoalesce(
            serverId.value,
            reason = "foreground_reconcile",
            coalesceFreshWithin = RECENT_LIVE_STATUS_REFRESH_COALESCE_WINDOW,
        ) {
            machineCalls += 1
            true
        }
        assertEquals(1, agentCalls)
        assertEquals(1, machineCalls)

        // t=8s: socket dropped at t=3s (agent went offline during outage), reconnects at t=8s.
        currentAgent = agentOffline
        advanceTimeBy(8_000L)
        val t2 = ProcessPresenceTransportEpoch("family-1", 2L)
        val r2 = registry.rebaselineTransport(owner, registry.writeGeneration, t2)
        assertEquals(RaftReconnectRefetchDecision.Delayed, refetch.onTransportRebaselined(owner, t2, r2, agentsOnlyOnReconnect = true))

        // Delayed refetch fires within 25 s of the t=0 read: MUST NOT coalesce with the pre-connect read.
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        runCurrent()
        assertEquals(2, agentCalls, "reconnect refill must not coalesce with a read started before connect")
        assertEquals(2, machineCalls, "reconnect refill must not coalesce with a /machines read started before connect")
        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-1")?.agentStatus)

        // Pre-connect IN-FLIGHT read on ImmediateAfterSwitch (coalesceFreshWithin = 0):
        // must NOT join a flight started before connect; must start a fresh post-connect read.
        blockFirstInFlightAgents = true
        val staleAgentsJob = async { store.refreshAgents(serverId, reason = "pre_connect_reconcile") }
        val staleMachinesJob =
            async {
                machinesCoalescer.runOrCoalesce(serverId.value, reason = "pre_connect_reconcile") {
                    machineCalls += 1
                    preConnectMachinesStarted.complete(Unit)
                    releasePreConnectMachines.await()
                    true
                }
            }
        preConnectAgentsStarted.await()
        preConnectMachinesStarted.await()

        advanceTimeBy(1_000L)
        jitter.onServerSelected(serverId.value, reason = "server_selected")
        val t3 = ProcessPresenceTransportEpoch("family-1", 3L)
        val r3 = registry.rebaselineTransport(owner, registry.writeGeneration, t3)
        assertEquals(RaftReconnectRefetchDecision.ImmediateAfterSwitch, refetch.onTransportRebaselined(owner, t3, r3, agentsOnlyOnReconnect = true))
        runCurrent()
        assertEquals(4, agentCalls, "ImmediateAfterSwitch must supersede pre-connect in-flight /agents read")
        assertEquals(4, machineCalls, "ImmediateAfterSwitch must supersede pre-connect in-flight /machines read")

        releasePreConnectAgents.complete(Unit)
        releasePreConnectMachines.complete(Unit)
        staleAgentsJob.await()
        staleMachinesJob.await()
        assertEquals(AgentStatus.Offline, registry.factFor(owner, "agent-1")?.agentStatus)
    }

    @Test
    fun doubleInvalidateKeepsServerGenerationMonotonicSoOrphanedFlightCannotCacheFresh() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val coalescer = RaftRecentServerRefreshCoalescer(scope = backgroundScope, timeSource = testScheduler.timeSource)

        val orphaned =
            async {
                coalescer.runOrCoalesce(serverId.value, reason = "first", coalesceFreshWithin = 25.seconds) {
                    calls += 1
                    started.complete(Unit)
                    release.await()
                    true
                }
            }
        started.await()

        // First invalidate detaches the active flight (generation 0 -> 1); second invalidate with no
        // active flight must bump to 2 instead of deleting the key back to 0.
        coalescer.invalidate(serverId.value)
        coalescer.invalidate(serverId.value)

        release.complete(Unit)
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, orphaned.await())
        assertEquals(1, calls)

        // Because the orphaned flight's generation (0) no longer matches (2), it must NOT have
        // populated the fresh cache: the next coalescing caller must execute.
        val next =
            coalescer.runOrCoalesce(serverId.value, reason = "after_double_invalidate", coalesceFreshWithin = 25.seconds) {
                calls += 1
                true
            }
        assertEquals(RaftRecentServerRefreshCoalescer.Outcome.Executed, next)
        assertEquals(2, calls)
    }
}
