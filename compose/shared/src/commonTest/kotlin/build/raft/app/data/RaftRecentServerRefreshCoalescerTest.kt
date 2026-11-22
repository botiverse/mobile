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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RaftRecentServerRefreshCoalescerTest {
    private val serverId = ServerId("server-1")
    private val owner = ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = serverId.value)

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
}
