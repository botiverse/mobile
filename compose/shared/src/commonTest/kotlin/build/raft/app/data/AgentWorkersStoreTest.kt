package build.raft.app.data

import build.raft.app.model.AgentWorkersChanged
import build.raft.app.model.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

// Payloads verified against botiverse/slock `packages/shared/src/workersSnapshot.ts`,
// `packages/server/src/routes/agents.ts`, and `packages/web/tests/agentWorkersHook.behavior.test.tsx`.
class AgentWorkersStoreTest {
    private val serverId = "server-1"
    private val agent1 = MemberId("agent-1")
    private val agent2 = MemberId("agent-2")

    private fun workerRow(
        agentId: MemberId = agent1,
        worker: String = "w1",
        objectRef: String = "#eng:#7",
        state: AgentWorkerState = AgentWorkerState.Running,
    ): AgentWorkerRow =
        AgentWorkerRow(
            agentId = agentId,
            worker = worker,
            objectRef = objectRef,
            objectKind = AgentWorkerObjectKind.Task,
            model = "claude-opus-5-5",
            since = "2026-11-23T10:00:00.000Z",
            state = state,
            launchId = "worker-$worker-abc",
        )

    private fun known(
        ageMs: Long = 2_000L,
        workers: List<AgentWorkerRow> = listOf(workerRow()),
        digest: String = "d0000001",
        stale: Boolean = ageMs > AGENT_WORKERS_SNAPSHOT_STALE_MS,
    ): DataResult.Content<AgentWorkersReadSnapshot> =
        DataResult.Content(
            AgentWorkersReadSnapshot.Known(
                receivedAt = "2026-11-23T10:05:00.000Z",
                ageMs = ageMs,
                stale = stale,
                digest = digest,
                workers = workers,
            )
        )

    @Test
    fun parseSharedAgentWorkersReadParsesUnknownKnownAndObservabilityFields() {
        assertEquals(AgentWorkersReadSnapshot.Unknown, parseSharedAgentWorkersRead("""{"status":"unknown"}"""))

        val body =
            """
            {
              "status": "known",
              "receivedAt": "2026-11-25T10:05:00.000Z",
              "ageMs": 1500,
              "stale": false,
              "digest": "d0000001",
              "workers": [
                {
                  "agentId": "agent-1",
                  "worker": "w1",
                  "object": "#eng:#7",
                  "objectKind": "task",
                  "taskTitle": "Ship Workers panel",
                  "runtime": "claude",
                  "model": "claude-opus-5-5",
                  "rule": "task conversations always go out",
                  "since": "2026-11-25T10:00:00.000Z",
                  "state": "running",
                  "progress": {"text": "Tests green.", "at": "2026-11-25T10:04:00.000Z"},
                  "launchId": "worker-w1-abc",
                  "turnStartedAt": "2026-11-25T10:03:00.000Z",
                  "lastEventAt": "2026-11-25T10:04:30.000Z",
                  "doing": "tool",
                  "tool": "Bash",
                  "queued": 2,
                  "oldestWaitingAt": "2026-11-25T10:02:00.000Z",
                  "stalled": false,
                  "inToolForMs": 720000,
                  "waitingFrom": "alice"
                },
                {"agentId": "agent-1", "worker": "", "object": "#bad", "objectKind": "task", "since": "2026-11-25T10:00:00.000Z", "state": "running"}
              ]
            }
            """.trimIndent()
        val parsed = assertIs<AgentWorkersReadSnapshot.Known>(parseSharedAgentWorkersRead(body))
        assertEquals("2026-11-25T10:05:00.000Z", parsed.receivedAt)
        assertEquals(1_500L, parsed.ageMs)
        assertFalse(parsed.stale)
        assertEquals("d0000001", parsed.digest)
        assertEquals(1, parsed.workers.size)
        val row = parsed.workers.single()
        assertEquals(agent1, row.agentId)
        assertEquals("w1", row.worker)
        assertEquals("#eng:#7", row.objectRef)
        assertEquals(AgentWorkerObjectKind.Task, row.objectKind)
        assertEquals("Ship Workers panel", row.taskTitle)
        assertEquals("claude", row.runtime)
        assertEquals("claude-opus-5-5", row.model)
        assertEquals("task conversations always go out", row.rule)
        assertEquals(AgentWorkerState.Running, row.state)
        assertEquals("Tests green.", row.progress?.text)
        assertEquals("worker-w1-abc", row.launchId)
        assertEquals(AgentWorkerDoing.Tool, row.doing)
        assertEquals("Bash", row.tool)
        assertEquals(2, row.queued)
        assertEquals(720_000L, row.inToolForMs)
        assertEquals("alice", row.waitingFrom)

        assertNull(parseSharedAgentWorkersRead("""{"status":"known"}"""))
        assertNull(parseSharedAgentWorkersRead("""{"status":"bogus"}"""))
        assertNull(parseSharedAgentWorkersRead("not json"))
    }

    @Test
    fun parseSharedAgentWorkerStopOutcomeAndApiRequestsMatchSlockRoutes() = runTest {
        assertEquals(AgentWorkerStopOutcome.Stopped, parseSharedAgentWorkerStopOutcome("""{"outcome":"stopped"}"""))
        assertEquals(AgentWorkerStopOutcome.NotFound, parseSharedAgentWorkerStopOutcome("""{"outcome":"not_found"}"""))
        assertEquals(AgentWorkerStopOutcome.NotFound, parseSharedAgentWorkerStopOutcome("""{}"""))
        assertNull(parseSharedAgentWorkerStopOutcome("bad"))

        val network = ScriptedAgentControlsNetworkClient()
        network.respond("GET", "agents/agent-1/workers", 200, """{"status":"unknown"}""")
        network.respond("POST", "agents/agent-1/workers/w1/stop", 200, """{"outcome":"stopped"}""")

        val api = RaftAgentControlsApi(network)
        assertEquals(DataResult.Content(AgentWorkersReadSnapshot.Unknown), api.listWorkers(agent1))
        assertEquals(DataResult.Content(AgentWorkerStopOutcome.Stopped), api.stopWorker(agent1, "w1"))
        assertEquals(listOf("GET agents/agent-1/workers", "POST agents/agent-1/workers/w1/stop"), network.requests.map { "${it.method} ${it.path}" })
    }

    @Test
    fun stopRefusalsMapToTypedFeedbackAndKeepServerError() = runTest {
        val cases =
            listOf(
                Triple(409, """{"error":"This agent's Computer is offline.","code":"computer_offline"}""", AgentWorkerStopFeedback.ComputerOffline::class),
                Triple(409, """{"error":"Stale snapshot","code":"workers_snapshot_stale"}""", AgentWorkerStopFeedback.SnapshotStale::class),
                Triple(409, """{"error":"Upgrade needed","code":"computer_upgrade_required"}""", AgentWorkerStopFeedback.UpgradeRequired::class),
                Triple(504, """{"error":"Timed out","code":"computer_timeout"}""", AgentWorkerStopFeedback.Failed::class),
                Triple(403, """{"error":"Forbidden"}""", AgentWorkerStopFeedback.Failed::class),
            )
        cases.forEach { (status, body, expected) ->
            val network = ScriptedAgentControlsNetworkClient()
            network.respond("POST", "agents/agent-1/workers/w1/stop", status, body)
            val feedback = RaftAgentControlsApi(network).stopWorker(agent1, "w1").toAgentWorkerStopFeedback("w1", "#eng:#7")
            assertEquals(expected, feedback::class, body)
        }
    }

    @Test
    fun canStopAgentWorkersMatchesServerAuthorityMatrix() {
        assertTrue(canStopAgentWorkers(serverRole = "owner", isCreator = false, isExternalAgent = false, deleted = false))
        assertTrue(canStopAgentWorkers(serverRole = "admin", isCreator = false, isExternalAgent = false, deleted = false))
        assertTrue(canStopAgentWorkers(serverRole = "member", isCreator = false, isExternalAgent = false, deleted = false))
        assertFalse(canStopAgentWorkers(serverRole = "guest", isCreator = false, isExternalAgent = false, deleted = false))
        assertTrue(canStopAgentWorkers(serverRole = "guest", isCreator = true, isExternalAgent = false, deleted = false))
        assertFalse(canStopAgentWorkers(serverRole = "owner", isCreator = true, isExternalAgent = true, deleted = false))
        assertFalse(canStopAgentWorkers(serverRole = "owner", isCreator = true, isExternalAgent = false, deleted = true))
        // Personal assistant: owner only.
        assertTrue(
            canStopAgentWorkers(
                serverRole = "member",
                isCreator = false,
                isExternalAgent = false,
                deleted = false,
                assistantOwnerUserId = "u-1",
                currentUserId = "u-1",
            )
        )
        assertFalse(
            canStopAgentWorkers(
                serverRole = "owner",
                isCreator = false,
                isExternalAgent = false,
                deleted = false,
                assistantOwnerUserId = "u-1",
                currentUserId = "u-2",
            )
        )
    }

    @Test
    fun flagOffReadsNothingAndIgnoresRealtimeNudges() = runTest {
        var reads = 0
        val signals = RaftAgentWorkersChangedSignals()
        val store =
            AgentWorkersStore(
                listWorkers = { _, _ -> reads += 1; known() },
                stopWorker = { _, _ -> DataResult.Content(AgentWorkerStopOutcome.Stopped) },
                scope = backgroundScope,
                workersChangedSignals = signals.events,
                freshness = MutableSharedFlow(),
            )
        store.dispatch(AgentWorkersIntent.Bind(serverId, agent1, enabled = false))
        signals.publish(serverId, AgentWorkersChanged(agent1, "d0000002", "2026-11-23T10:05:30.000Z"))
        runCurrent()
        assertEquals(0, reads)
        assertFalse(store.state.value.enabled)
    }

    @Test
    fun liveNudgeDedupsByDigestRefreshesOnChangeAndCatchesUpOnReconnect() = runTest {
        var reads = 0
        var nextAnswer: DataResult<AgentWorkersReadSnapshot> = known(ageMs = 2_000L, workers = listOf(workerRow()), digest = "d0000001")
        val signals = RaftAgentWorkersChangedSignals()
        val freshness = MutableSharedFlow<RaftFreshnessEvent>(extraBufferCapacity = 8)
        var now = 1_000_000L
        val store =
            AgentWorkersStore(
                listWorkers = { _, _ -> reads += 1; nextAnswer },
                stopWorker = { _, _ -> DataResult.Content(AgentWorkerStopOutcome.Stopped) },
                scope = backgroundScope,
                workersChangedSignals = signals.events,
                freshness = freshness,
                wallNowMillis = { now },
                monotonicNowMillis = { now },
                liveConnectionToken = { 1L },
            )
        store.dispatch(AgentWorkersIntent.Bind(serverId, agent1, enabled = true))
        runCurrent()
        assertEquals(1, reads)
        assertEquals(AgentWorkersLoadStatus.Known, store.state.value.status)
        assertEquals(1, store.state.value.workers.size)
        assertFalse(store.state.value.stale)

        // Matching digest or other agent or other server -> skipped.
        signals.publish(serverId, AgentWorkersChanged(agent1, "d0000001", "2026-11-23T10:05:15.000Z"))
        signals.publish(serverId, AgentWorkersChanged(agent2, "d0000009", "2026-11-23T10:05:15.000Z"))
        signals.publish("other-server", AgentWorkersChanged(agent1, "d0000009", "2026-11-23T10:05:15.000Z"))
        runCurrent()
        assertEquals(1, reads)

        // New digest -> re-reads and stores the new digest.
        nextAnswer = known(ageMs = 0L, workers = emptyList(), digest = "d0000002")
        signals.publish(serverId, AgentWorkersChanged(agent1, "d0000002", "2026-11-23T10:05:30.000Z"))
        runCurrent()
        assertEquals(2, reads)
        assertEquals(0, store.state.value.workers.size)

        // Repeat of new digest -> skipped.
        signals.publish(serverId, AgentWorkersChanged(agent1, "d0000002", "2026-11-23T10:05:31.000Z"))
        runCurrent()
        assertEquals(2, reads)

        // Socket reconnect -> always re-reads (nudges are not replayed).
        freshness.tryEmit(RaftFreshnessEvent(RaftFreshnessTrigger.Reconnect, serverId = serverId, transportEpoch = 2L))
        runCurrent()
        assertEquals(3, reads)
    }

    @Test
    fun switchingAgentClearsPreviousRowsAndDigest() = runTest {
        val gate = CompletableDeferred<DataResult<AgentWorkersReadSnapshot>>()
        val requestedAgents = mutableListOf<MemberId>()
        val signals = RaftAgentWorkersChangedSignals()
        val store =
            AgentWorkersStore(
                listWorkers = { id, _ ->
                    requestedAgents += id
                    if (id == agent1) known(digest = "d0000001") else gate.await()
                },
                stopWorker = { _, _ -> DataResult.Content(AgentWorkerStopOutcome.Stopped) },
                scope = backgroundScope,
                workersChangedSignals = signals.events,
                freshness = MutableSharedFlow(),
            )
        store.dispatch(AgentWorkersIntent.Bind(serverId, agent1, enabled = true))
        runCurrent()
        assertEquals(1, store.state.value.workers.size)

        store.dispatch(AgentWorkersIntent.Bind(serverId, agent2, enabled = true))
        runCurrent()
        assertEquals(AgentWorkersLoadStatus.Loading, store.state.value.status)
        assertEquals(emptyList(), store.state.value.workers)

        gate.complete(known(workers = emptyList(), digest = "d0000003"))
        runCurrent()
        assertEquals(AgentWorkersLoadStatus.Known, store.state.value.status)

        // agent1's old digest must not suppress a nudge for agent2.
        signals.publish(serverId, AgentWorkersChanged(agent2, "d0000001", "2026-11-23T10:06:00.000Z"))
        runCurrent()
        assertEquals(listOf(agent1, agent2, agent2), requestedAgents)
    }

    @Test
    fun staleFollowsServerVerdictAndAgesLocallyPastSixtySeconds() = runTest {
        var now = 100_000L
        var answer: DataResult<AgentWorkersReadSnapshot> = known(ageMs = 31_000L, stale = true)
        val foreground = MutableStateFlow(true)
        val store =
            AgentWorkersStore(
                listWorkers = { _, _ -> answer },
                stopWorker = { _, _ -> DataResult.Content(AgentWorkerStopOutcome.Stopped) },
                scope = backgroundScope,
                freshness = MutableSharedFlow(),
                foreground = foreground,
                wallNowMillis = { now },
                monotonicNowMillis = { now },
                rereadIntervalMillis = 600_000L,
                freshnessTickIntervalMillis = 5_000L,
            )
        store.dispatch(AgentWorkersIntent.Bind(serverId, agent1, enabled = true))
        store.dispatch(AgentWorkersIntent.SetPanelOpen(true))
        runCurrent()
        assertTrue(store.state.value.stale)

        // Newer snapshot clears staleness.
        answer = known(ageMs = 1_000L, stale = false)
        store.dispatch(AgentWorkersIntent.Refresh)
        runCurrent()
        assertFalse(store.state.value.stale)

        // 40 s later (under 30s + 30s): still live.
        now += 40_000L
        advanceTimeBy(5_000L)
        runCurrent()
        assertFalse(store.state.value.stale)

        // Past 60 s without a newer read: stale locally too.
        now += 25_000L
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(store.state.value.stale)
    }

    @Test
    fun stopIsSingleFlightOptimisticallyHidesRowAndRereadsAfterThreeSeconds() = runTest {
        val stopGate = CompletableDeferred<DataResult<AgentWorkerStopOutcome>>()
        var reads = 0
        val stopCalls = mutableListOf<Pair<MemberId, String>>()
        val store =
            AgentWorkersStore(
                listWorkers = { _, _ -> reads += 1; known(workers = listOf(workerRow(worker = "w1"))) },
                stopWorker = { id, worker -> stopCalls += id to worker; stopGate.await() },
                scope = backgroundScope,
                freshness = MutableSharedFlow(),
            )
        store.dispatch(AgentWorkersIntent.Bind(serverId, agent1, enabled = true))
        runCurrent()
        assertEquals(1, reads)
        assertEquals(1, store.state.value.workers.size)

        // Invalid worker name is ignored.
        store.dispatch(AgentWorkersIntent.Stop(agent1, "not-a-worker", "#eng:#7"))
        runCurrent()
        assertEquals(emptyList(), stopCalls)

        // Single-flight: second Stop while in flight is ignored.
        store.dispatch(AgentWorkersIntent.Stop(agent1, "w1", "#eng:#7"))
        store.dispatch(AgentWorkersIntent.Stop(agent1, "w2", "#eng:#8"))
        runCurrent()
        assertEquals("w1", store.state.value.stoppingWorker)
        assertEquals(listOf(agent1 to "w1"), stopCalls)

        stopGate.complete(DataResult.Content(AgentWorkerStopOutcome.Stopped))
        runCurrent()
        assertNull(store.state.value.stoppingWorker)
        assertEquals(emptyList(), store.state.value.workers)
        assertEquals(AgentWorkerStopFeedback.Stopped("w1", "#eng:#7"), store.state.value.feedback)
        assertEquals(agent1, store.state.value.feedbackAgentId)

        // After-stop re-read runs 3 s later.
        advanceTimeBy(AGENT_WORKERS_AFTER_STOP_READ_MS)
        runCurrent()
        assertEquals(2, reads)

        store.dispatch(AgentWorkersIntent.ConsumeFeedback)
        assertNull(store.state.value.feedback)
    }

    @Test
    fun failedStopKeepsRowVisibleAndReportsRefusalFeedback() = runTest {
        val staleError = ScreenError(code = AgentControlErrorCodes.WorkersSnapshotStale, serverCode = AgentControlErrorCodes.WorkersSnapshotStale, traceId = "trace-1")
        val store =
            AgentWorkersStore(
                listWorkers = { _, _ -> known(workers = listOf(workerRow(worker = "w1"))) },
                stopWorker = { _, _ -> DataResult.Error(staleError) },
                scope = backgroundScope,
                freshness = MutableSharedFlow(),
            )
        store.dispatch(AgentWorkersIntent.Bind(serverId, agent1, enabled = true))
        runCurrent()

        store.dispatch(AgentWorkersIntent.Stop(agent1, "w1", "#eng:#7"))
        runCurrent()
        assertEquals(1, store.state.value.workers.size)
        assertEquals(AgentWorkerStopFeedback.SnapshotStale(staleError), store.state.value.feedback)
    }

    @Test
    fun realtimeDomainOwnerRoutesAgentWorkersChangedToInstalledSink() = runTest {
        val authority =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
                processInstanceId = "workers-test-process",
            )
        authority.initialize()
        authority.advanceAuth(
            RealtimeAuthRevision(
                generation = 1L,
                session = RealtimeAuthoritySession("account-1", listOf(serverId), environmentId = "env-test"),
            )
        )
        val lease = authority.register("process-realtime-host")
        val grant = requireNotNull(authority.currentGrant(lease.id, serverId))
        val signals = RaftAgentWorkersChangedSignals()
        val received = mutableListOf<RaftAgentWorkersChangedSignal>()
        val owner =
            SharedRealtimeDomainEventOwner(runIfCurrentGrant = authority::runIfCurrentGrant).also {
                it.installAgentWorkersChangedSink { srv, event ->
                    signals.publish(srv, event)
                    received += RaftAgentWorkersChangedSignal(srv, event.agentId, event.digest, event.receivedAt)
                }
            }
        val event = AgentWorkersChanged(agent1, "d0000042", "2026-11-25T10:15:00.000Z")
        assertTrue(owner.acceptProcessMutation(grant, event, source = "process_normal_realtime"))
        assertEquals(listOf(RaftAgentWorkersChangedSignal(serverId, agent1, "d0000042", "2026-11-25T10:15:00.000Z")), received)
    }
}
