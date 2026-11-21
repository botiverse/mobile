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
                refreshMachinesWithCoalesce = { sid, window ->
                    machinesCoalescer.runOrCoalesce(sid, reason = PresenceTransportRosterRefetch.REASON, coalesceFreshWithin = window) {
                        machineCalls += 1
                        true
                    }
                },
                retryScope = backgroundScope,
            )

        // Initial connect populates the roster once.
        val t1 = ProcessPresenceTransportEpoch("family-1", 1L)
        val r1 = registry.rebaselineTransport(owner, registry.writeGeneration, t1)
        assertEquals(RaftReconnectRefetchDecision.Immediate, refetch.onTransportRebaselined(owner, t1, r1, agentsOnlyOnReconnect = true))
        runCurrent()
        assertEquals(1, memberCalls)
        assertEquals(0, agentCalls)
        assertEquals(0, machineCalls)

        // Foreground return: reconcile fetches /agents + /machines immediately, then socket reconnects
        // and schedules the 0-20 s jittered presence_transport_connected refetch.
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

        val t2 = ProcessPresenceTransportEpoch("family-1", 2L)
        val r2 = registry.rebaselineTransport(owner, registry.writeGeneration, t2)
        assertEquals(RaftReconnectRefetchDecision.Delayed, refetch.onTransportRebaselined(owner, t2, r2, agentsOnlyOnReconnect = true))

        // Jitter fires within 20 s (< 25 s coalesce window): both /agents and /machines coalesce with
        // zero extra network calls, while presence refill still completes cleanly.
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        runCurrent()
        assertEquals(1, agentCalls, "delayed reconnect must reuse recent foreground_reconcile /agents")
        assertEquals(1, machineCalls, "delayed reconnect must reuse recent foreground_reconcile /machines")
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
}
