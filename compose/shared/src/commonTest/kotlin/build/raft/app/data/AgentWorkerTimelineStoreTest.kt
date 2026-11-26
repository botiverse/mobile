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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

// Payloads verified against botiverse/slock `packages/shared/src/workerEvents.ts`,
// `packages/server/src/routes/agents.ts`, and
// `packages/web/tests/agentWorkersObservability.behavior.test.tsx`.
class AgentWorkerTimelineStoreTest {
    private val serverId = "server-1"
    private val agent1 = MemberId("agent-1")
    private val agent2 = MemberId("agent-2")

    private fun liveRow(
        worker: String = "w1",
        launchId: String = "worker-$worker-abc",
        objectRef: String = "#eng:#7",
        lastEventAt: String? = "2026-11-25T10:10:00.000Z",
    ): AgentWorkerRow =
        AgentWorkerRow(
            agentId = agent1,
            worker = worker,
            objectRef = objectRef,
            objectKind = AgentWorkerObjectKind.Task,
            model = "claude-opus-5-5",
            since = "2026-11-25T10:00:00.000Z",
            state = AgentWorkerState.Running,
            launchId = launchId,
            lastEventAt = lastEventAt,
        )

    private fun recentRow(
        launchId: String = "worker-w1-old",
        worker: String = "w1",
        objectRef: String = "#eng:#7",
        endedAt: String? = "2026-11-25T08:47:00.000Z",
        reason: AgentWorkerEndReason? = AgentWorkerEndReason.Done,
        handedBack: Int? = 1,
    ): AgentRecentWorkerRow =
        AgentRecentWorkerRow(
            launchId = launchId,
            worker = worker,
            objectRef = objectRef,
            objectKind = AgentWorkerObjectKind.Task,
            startedAt = "2026-11-25T08:00:00.000Z",
            endedAt = endedAt,
            reason = reason,
            handedBack = handedBack,
        )

    private fun knownSnapshot(
        workers: List<AgentWorkerRow>,
        digest: String,
    ): DataResult.Content<AgentWorkersReadSnapshot> =
        DataResult.Content(
            AgentWorkersReadSnapshot.Known(
                receivedAt = "2026-11-25T10:12:00.000Z",
                ageMs = 1_000L,
                stale = false,
                digest = digest,
                workers = workers,
            )
        )

    @Test
    fun parseSharedAgentRecentWorkersParsesEndedAndRunningRowsAndSkipsMalformed() {
        val body =
            """
            {
              "workers": [
                {
                  "launchId": "worker-w2-run",
                  "worker": "w2",
                  "object": "#eng:#9",
                  "objectKind": "task",
                  "startedAt": "2026-11-25T09:00:00.000Z",
                  "endedAt": null,
                  "reason": null,
                  "handedBack": null
                },
                {
                  "launchId": "worker-w1-old",
                  "worker": "w1",
                  "object": "#eng:#7",
                  "objectKind": "task",
                  "startedAt": "2026-11-25T08:00:00.000Z",
                  "endedAt": "2026-11-25T08:47:00.000Z",
                  "reason": "done",
                  "handedBack": 1,
                  "noteFirstLine": "PR opened, waiting on @warden."
                },
                {
                  "launchId": "worker-w6-old",
                  "worker": "w6",
                  "object": "#eng:#4",
                  "objectKind": "thread",
                  "startedAt": "2026-11-23T08:00:00.000Z",
                  "endedAt": "2026-11-23T08:31:00.000Z",
                  "reason": "waiting_on",
                  "handedBack": 0,
                  "stoppedBy": "@alice",
                  "waitingOn": "@warden"
                },
                {
                  "launchId": "",
                  "worker": "w9",
                  "object": "#bad",
                  "objectKind": "task",
                  "startedAt": "2026-11-23T08:00:00.000Z"
                }
              ]
            }
            """.trimIndent()
        val parsed = requireNotNull(parseSharedAgentRecentWorkers(body))
        assertEquals(3, parsed.size)
        assertNull(parsed[0].endedAt)
        assertNull(parsed[0].reason)
        assertEquals("worker-w1-old", parsed[1].launchId)
        assertEquals(AgentWorkerEndReason.Done, parsed[1].reason)
        assertEquals(1, parsed[1].handedBack)
        assertEquals("PR opened, waiting on @warden.", parsed[1].noteFirstLine)
        assertEquals(AgentWorkerObjectKind.Thread, parsed[2].objectKind)
        assertEquals(AgentWorkerEndReason.WaitingOn, parsed[2].reason)
        assertEquals("alice", parsed[2].stoppedBy)
        assertEquals("warden", parsed[2].waitingOn)

        // Running launch stays out of Recently ended, and duplicate launchIds collapse to one.
        val endedOnly = recentEndedAgentWorkers(parsed + parsed[1])
        assertEquals(listOf("worker-w1-old", "worker-w6-old"), endedOnly.map { it.launchId })

        assertNull(parseSharedAgentRecentWorkers("{}"))
        assertNull(parseSharedAgentRecentWorkers("not json"))
    }

    @Test
    fun parseSharedAgentWorkerTimelineParsesAllEventKindsStripsNonProgressReportTextAndSkipsUnknownKinds() {
        val body =
            """
            {
              "launchId": "worker-w1-old",
              "events": [
                {"seq": 1, "kind": "handed_out", "data": {"object": "#eng:#7", "objectKind": "task", "runtime": "claude", "model": "claude-opus-5-5"}, "occurredAt": "2026-11-25T08:00:00.000Z"},
                {"seq": 2, "kind": "delivered", "data": {"messageId": "msg-1", "sender": "human", "via": "new_turn"}, "occurredAt": "2026-11-25T08:00:01.000Z"},
                {"seq": 3, "kind": "turn_started", "data": {"turn": 1, "inputs": ["msg-1"]}, "occurredAt": "2026-11-25T08:00:02.000Z"},
                {"seq": 4, "kind": "turn_ended", "data": {"turn": 1, "outcome": "completed", "durationMs": 300000, "toolCalls": 7, "sent": ["msg-2"], "tokens": {"input": 1000, "output": 234}}, "occurredAt": "2026-11-25T08:05:00.000Z"},
                {"seq": 5, "kind": "reported", "data": {"reportKind": "progress", "text": "Tests green."}, "occurredAt": "2026-11-25T08:05:10.000Z"},
                {"seq": 6, "kind": "reported", "data": {"reportKind": "needs_decision", "text": "private parent text"}, "occurredAt": "2026-11-25T08:05:20.000Z"},
                {"seq": 7, "kind": "reported", "data": {"reportKind": "needs_parent", "text": "never shown"}, "occurredAt": "2026-11-25T08:05:30.000Z"},
                {"seq": 8, "kind": "quiet", "data": {"doing": "model", "quietSinceAt": "2026-11-25T08:10:00.000Z"}, "occurredAt": "2026-11-25T08:20:00.000Z"},
                {"seq": 9, "kind": "active", "data": {"doing": "model", "quietSinceAt": "2026-11-25T08:10:00.000Z"}, "occurredAt": "2026-11-25T08:22:00.000Z"},
                {"seq": 10, "kind": "human_waiting", "data": {"messageId": "msg-3", "sinceAt": "2026-11-25T08:23:00.000Z"}, "occurredAt": "2026-11-25T08:25:00.000Z"},
                {"seq": 11, "kind": "handed_to_parent", "data": {"messageId": "msg-3", "reason": "overdue", "waitedMs": 120000}, "occurredAt": "2026-11-25T08:25:01.000Z"},
                {"seq": 12, "kind": "human_settled", "data": {"messageId": "msg-3", "by": null, "how": "expired_24h", "waitedMs": 86400000}, "occurredAt": "2026-11-26T08:23:00.000Z"},
                {"seq": 13, "kind": "future_unknown_kind", "data": {"foo": "bar"}, "occurredAt": "2026-11-26T08:24:00.000Z"},
                {"seq": 14, "kind": "ended", "data": {"reason": "done", "handedBack": 1, "durationMs": 2820000, "note": "Line 1\nLine 2"}, "occurredAt": "2026-11-25T08:47:00.000Z"}
              ]
            }
            """.trimIndent()
        val snapshot = requireNotNull(parseSharedAgentWorkerTimeline(body))
        assertEquals("worker-w1-old", snapshot.launchId)
        assertEquals(13, snapshot.events.size)

        val turnEnded = assertIs<AgentWorkerTimelineEventData.TurnEnded>(snapshot.events[3].data)
        assertEquals(1_234L, turnEnded.tokens?.totalInputOutput)
        val progress = assertIs<AgentWorkerTimelineEventData.Reported>(snapshot.events[4].data)
        assertEquals("Tests green.", progress.text)
        val decision = assertIs<AgentWorkerTimelineEventData.Reported>(snapshot.events[5].data)
        assertNull(decision.text)
        val settled = assertIs<AgentWorkerTimelineEventData.HumanSettled>(snapshot.events[11].data)
        assertNull(settled.by)
        assertEquals(AgentWorkerSettledHow.Expired24h, settled.how)
        val ended = assertIs<AgentWorkerTimelineEventData.Ended>(snapshot.events[12].data)
        assertEquals("Line 1\nLine 2", ended.note)

        assertNull(parseSharedAgentWorkerTimeline("""{"launchId":"worker-w1-old"}"""))
        assertNull(parseSharedAgentWorkerTimeline("bad"))
    }

    @Test
    fun projectAgentWorkerTimelineEventsFoldsCompletedTurnsDropsNeedsParentAndComputesWarnings() {
        val events =
            listOf(
                AgentWorkerTimelineEvent(
                    seq = 1,
                    kind = AgentWorkerEventKind.HandedOut,
                    occurredAt = "2026-11-25T08:00:00.000Z",
                    data = AgentWorkerTimelineEventData.HandedOut("#eng:#7", AgentWorkerObjectKind.Task),
                ),
                AgentWorkerTimelineEvent(
                    seq = 2,
                    kind = AgentWorkerEventKind.TurnStarted,
                    occurredAt = "2026-11-25T08:00:02.000Z",
                    data = AgentWorkerTimelineEventData.TurnStarted(turn = 1, inputs = listOf("msg-1")),
                ),
                AgentWorkerTimelineEvent(
                    seq = 3,
                    kind = AgentWorkerEventKind.TurnEnded,
                    occurredAt = "2026-11-25T08:05:00.000Z",
                    data = AgentWorkerTimelineEventData.TurnEnded(
                        turn = 1,
                        outcome = AgentWorkerTurnOutcome.Completed,
                        durationMs = 5 * 60_000L,
                        toolCalls = 7,
                        sent = listOf("msg-2"),
                    ),
                ),
                AgentWorkerTimelineEvent(
                    seq = 4,
                    kind = AgentWorkerEventKind.TurnStarted,
                    occurredAt = "2026-11-25T08:06:00.000Z",
                    data = AgentWorkerTimelineEventData.TurnStarted(turn = 2),
                ),
                AgentWorkerTimelineEvent(
                    seq = 5,
                    kind = AgentWorkerEventKind.Quiet,
                    occurredAt = "2026-11-25T08:20:00.000Z",
                    data = AgentWorkerTimelineEventData.Quiet(
                        doing = AgentWorkerQuietDoing.Model,
                        quietSinceAt = "2026-11-25T08:10:00.000Z",
                    ),
                ),
                AgentWorkerTimelineEvent(
                    seq = 6,
                    kind = AgentWorkerEventKind.Quiet,
                    occurredAt = "2026-11-25T08:35:00.000Z",
                    data = AgentWorkerTimelineEventData.Quiet(
                        doing = AgentWorkerQuietDoing.Tool,
                        tool = "Bash",
                        quietSinceAt = "2026-11-25T08:25:00.000Z",
                    ),
                ),
                AgentWorkerTimelineEvent(
                    seq = 7,
                    kind = AgentWorkerEventKind.Reported,
                    occurredAt = "2026-11-25T08:36:00.000Z",
                    data = AgentWorkerTimelineEventData.Reported(AgentWorkerReportKind.NeedsParent, text = "secret"),
                ),
                AgentWorkerTimelineEvent(
                    seq = 8,
                    kind = AgentWorkerEventKind.HumanWaiting,
                    occurredAt = "2026-11-25T08:40:00.000Z",
                    data = AgentWorkerTimelineEventData.HumanWaiting("msg-3", "2026-11-25T08:37:00.000Z"),
                ),
                AgentWorkerTimelineEvent(
                    seq = 9,
                    kind = AgentWorkerEventKind.HumanSettled,
                    occurredAt = "2026-11-25T08:41:00.000Z",
                    data = AgentWorkerTimelineEventData.HumanSettled(
                        messageId = "msg-3",
                        by = AgentWorkerSettledBy.Worker,
                        how = AgentWorkerSettledHow.Replied,
                        waitedMs = 4 * 60_000L,
                    ),
                ),
                AgentWorkerTimelineEvent(
                    seq = 10,
                    kind = AgentWorkerEventKind.HumanSettled,
                    occurredAt = "2026-11-26T08:40:00.000Z",
                    data = AgentWorkerTimelineEventData.HumanSettled(
                        messageId = "msg-4",
                        by = null,
                        how = AgentWorkerSettledHow.Expired24h,
                        waitedMs = 24 * 60 * 60_000L,
                    ),
                ),
            )

        val projected = projectAgentWorkerTimelineEvents(events)
        assertEquals(
            listOf(
                AgentWorkerEventKind.HandedOut,
                AgentWorkerEventKind.TurnEnded,
                AgentWorkerEventKind.TurnStarted,
                AgentWorkerEventKind.Quiet,
                AgentWorkerEventKind.Quiet,
                AgentWorkerEventKind.HumanWaiting,
                AgentWorkerEventKind.HumanSettled,
                AgentWorkerEventKind.HumanSettled,
            ),
            projected.map { it.kind },
        )
        // Turn 1 start folded into Turn 1 end.
        assertEquals("2026-11-25T08:00:02.000Z", projected[1].foldedTurnStartedAt)
        assertEquals(AgentWorkerTimelineVariant.Success, projected[1].variant)
        // Turn 2 still running kept as Information.
        assertEquals(AgentWorkerTimelineVariant.Information, projected[2].variant)
        // Quiet in model is warning (10m); quiet in tool is default (not warning).
        assertTrue(projected[3].warning)
        assertEquals(AgentWorkerTimelineVariant.Warning, projected[3].variant)
        assertEquals(10 * 60_000L, projected[3].elapsedMs)
        assertFalse(projected[4].warning)
        assertEquals(AgentWorkerTimelineVariant.Default, projected[4].variant)
        // human_waiting is warning (3m).
        assertTrue(projected[5].warning)
        assertEquals(3 * 60_000L, projected[5].elapsedMs)
        // human_settled replied -> Success; expired_24h -> Warning.
        assertFalse(projected[6].warning)
        assertEquals(AgentWorkerTimelineVariant.Success, projected[6].variant)
        assertTrue(projected[7].warning)
        assertEquals(AgentWorkerTimelineVariant.Warning, projected[7].variant)
    }

    @Test
    fun apiRoutesQueryParamsErrorCodesTraceIdAndMessageContextLookupMatchSlock() = runTest {
        val traceId = "0123456789abcdef0123456789abcdef"
        val network = ScriptedAgentControlsNetworkClient()
        network.respond(
            "GET",
            "agents/agent-1/workers/recent",
            200,
            """{"workers":[{"launchId":"worker-w1-old","worker":"w1","object":"#eng:#7","objectKind":"task","startedAt":"2026-11-25T08:00:00.000Z","endedAt":"2026-11-25T08:47:00.000Z","reason":"done","handedBack":0}]}""",
        )
        network.respond(
            "GET",
            "agents/agent-1/workers/worker-w1-old/timeline",
            200,
            """{"launchId":"worker-w1-old","events":[]}""",
        )
        network.respond(
            "GET",
            "agents/agent-1/workers/worker-w9-gone/timeline",
            404,
            """{"code":"worker_timeline_not_found","error":"Worker not found"}""",
            headers = mapOf("X-Trace-Id" to listOf(traceId)),
        )
        network.respond(
            "GET",
            "agents/agent-1/workers/worker-w5-boom/timeline",
            500,
            """{"error":"Failed to load the Worker's timeline"}""",
            headers = mapOf("X-Trace-Id" to listOf(traceId)),
        )
        network.respond(
            "GET",
            "messages/context/msg-111",
            200,
            """{"channelId":"chan-9","targetMessageId":"msg-111","messages":[{"id":"msg-111","seq":1,"channelId":"chan-9","senderType":"user","senderId":"user-1","senderName":"Alice","messageType":"chat","content":"hi","createdAt":"2026-11-25T08:00:01.000Z"}]}""",
        )
        network.respond(
            "GET",
            "messages/context/msg-404",
            404,
            """{"error":"Not found"}""",
        )

        val api = RaftAgentControlsApi(network)
        val recent = assertIs<DataResult.Content<List<AgentRecentWorkerRow>>>(api.listRecentWorkers(agent1))
        assertEquals(1, recent.data.size)
        assertEquals(listOf("days" to "7"), network.requests[0].query)

        val timeline = assertIs<DataResult.Content<AgentWorkerTimelineSnapshot>>(api.getWorkerTimeline(agent1, "worker-w1-old"))
        assertEquals("worker-w1-old", timeline.data.launchId)

        val missing = assertIs<DataResult.Error>(api.getWorkerTimeline(agent1, "worker-w9-gone"))
        assertEquals(AgentControlErrorCodes.WorkerTimelineNotFound, missing.error.serverCode)
        assertEquals(traceId, missing.error.traceId)

        val failed = assertIs<DataResult.Error>(api.getWorkerTimeline(agent1, "worker-w5-boom"))
        assertEquals("http_500", failed.error.code)
        assertEquals(traceId, failed.error.traceId)

        val target = assertIs<DataResult.Content<AgentWorkerMessageTarget>>(api.resolveMessageTarget("msg-111"))
        assertEquals(AgentWorkerMessageTarget(channelId = "chan-9", messageId = "msg-111"), target.data)
        assertEquals(listOf("before" to "0", "after" to "0"), network.requests[4].query)

        assertIs<DataResult.Empty>(api.resolveMessageTarget("msg-404"))
    }

    @Test
    fun workersStoreLoadsRecentWhenPanelOpensRefreshesWhenLiveEndsAndReadsNothingWhenFlagOff() = runTest {
        var liveAnswer: DataResult<AgentWorkersReadSnapshot> = knownSnapshot(listOf(liveRow()), digest = "d1")
        var recentAnswer: DataResult<List<AgentRecentWorkerRow>> = DataResult.Content(listOf(recentRow(launchId = "worker-w0-prev")))
        val recentCalls = mutableListOf<Pair<MemberId, Int>>()
        val signals = RaftAgentWorkersChangedSignals()

        val store =
            AgentWorkersStore(
                listWorkers = { _, _ -> liveAnswer },
                stopWorker = { _, _ -> DataResult.Content(AgentWorkerStopOutcome.Stopped) },
                scope = backgroundScope,
                listRecentWorkers = { id, days, _ ->
                    recentCalls += id to days
                    recentAnswer
                },
                workersChangedSignals = signals.events,
                freshness = MutableSharedFlow(),
            )

        // Flag OFF reads nothing even if panel opens.
        store.dispatch(AgentWorkersIntent.Bind(serverId, agent1, enabled = false))
        store.dispatch(AgentWorkersIntent.SetPanelOpen(true))
        runCurrent()
        assertEquals(emptyList(), recentCalls)

        // Enable flag -> panel already open -> loads recent (days=7).
        store.dispatch(AgentWorkersIntent.Bind(serverId, agent1, enabled = true))
        runCurrent()
        assertEquals(listOf(agent1 to 7), recentCalls)
        assertTrue(store.state.value.recentLoaded)
        assertEquals(listOf("worker-w0-prev"), store.state.value.recentWorkers.map { it.launchId })

        // Live worker disappears via realtime nudge -> immediate recent re-read + 3s delayed re-read.
        liveAnswer = knownSnapshot(emptyList(), digest = "d2")
        recentAnswer = DataResult.Content(listOf(recentRow(launchId = "worker-w1-abc"), recentRow(launchId = "worker-w0-prev")))
        signals.publish(serverId, AgentWorkersChanged(agent1, "d2", "2026-11-25T10:15:00.000Z"))
        runCurrent()
        assertEquals(2, recentCalls.size)
        assertEquals(listOf("worker-w1-abc", "worker-w0-prev"), store.state.value.recentWorkers.map { it.launchId })

        advanceTimeBy(AGENT_WORKERS_AFTER_STOP_READ_MS)
        runCurrent()
        assertEquals(3, recentCalls.size)

        // Manual refresh refreshes recent too.
        store.dispatch(AgentWorkersIntent.Refresh)
        runCurrent()
        assertEquals(4, recentCalls.size)

        // Switching agent clears previous agent's recent rows before new read completes.
        val gate = CompletableDeferred<DataResult<List<AgentRecentWorkerRow>>>()
        val switchingStore =
            AgentWorkersStore(
                listWorkers = { _, _ -> knownSnapshot(emptyList(), digest = "d3") },
                stopWorker = { _, _ -> DataResult.Content(AgentWorkerStopOutcome.Stopped) },
                scope = backgroundScope,
                listRecentWorkers = { id, _, _ ->
                    if (id == agent1) DataResult.Content(listOf(recentRow())) else gate.await()
                },
                freshness = MutableSharedFlow(),
            )
        switchingStore.dispatch(AgentWorkersIntent.Bind(serverId, agent1, enabled = true))
        switchingStore.dispatch(AgentWorkersIntent.SetPanelOpen(true))
        runCurrent()
        assertEquals(1, switchingStore.state.value.recentWorkers.size)

        switchingStore.dispatch(AgentWorkersIntent.Bind(serverId, agent2, enabled = true))
        runCurrent()
        assertEquals(emptyList(), switchingStore.state.value.recentWorkers)
        assertTrue(switchingStore.state.value.recentLoading)
        gate.complete(DataResult.Error(ScreenError("http_500")))
        runCurrent()
        assertFalse(switchingStore.state.value.recentLoading)
        assertTrue(switchingStore.state.value.recentLoaded)
        assertEquals(emptyList(), switchingStore.state.value.recentWorkers)
    }

    @Test
    fun timelineStoreLoadsRefreshesOnKeyChangePreservesEventsOnRefreshBlipAndHandles404And500() = runTest {
        val traceId = "abcdef0123456789abcdef0123456789"
        var calls = 0
        var nextAnswer: DataResult<AgentWorkerTimelineSnapshot> =
            DataResult.Content(
                AgentWorkerTimelineSnapshot(
                    launchId = "worker-w1-abc",
                    events = listOf(
                        AgentWorkerTimelineEvent(
                            seq = 1,
                            kind = AgentWorkerEventKind.HandedOut,
                            occurredAt = "2026-11-25T10:00:00.000Z",
                            data = AgentWorkerTimelineEventData.HandedOut("#eng:#7", AgentWorkerObjectKind.Task),
                        )
                    ),
                )
            )
        val store =
            AgentWorkerTimelineStore(
                getTimeline = { _, _, _ -> calls += 1; nextAnswer },
                scope = backgroundScope,
            )

        // Malformed launchId -> immediate Unavailable, zero network calls.
        store.dispatch(
            AgentWorkerTimelineIntent.Open(
                agent1,
                AgentWorkerOpenTimeline(launchId = "not valid!", worker = "w1", objectRef = "#eng:#7"),
            )
        )
        runCurrent()
        assertEquals(0, calls)
        assertEquals(AgentWorkerTimelineLoadStatus.Unavailable, store.state.value.status)

        // Valid launchId -> Loaded.
        val open = AgentWorkerOpenTimeline(launchId = "worker-w1-abc", worker = "w1", objectRef = "#eng:#7")
        store.dispatch(AgentWorkerTimelineIntent.Open(agent1, open))
        runCurrent()
        assertEquals(1, calls)
        assertEquals(AgentWorkerTimelineLoadStatus.Loaded, store.state.value.status)
        assertEquals(1, store.state.value.events.size)

        // First SyncRefreshKey seeds baseline without duplicate read; unchanged key is no-op.
        store.dispatch(AgentWorkerTimelineIntent.SyncRefreshKey("running|t1"))
        store.dispatch(AgentWorkerTimelineIntent.SyncRefreshKey("running|t1"))
        runCurrent()
        assertEquals(1, calls)

        // Changed refreshKey re-reads; transient error keeps existing loaded events visible.
        nextAnswer = DataResult.Error(ScreenError("http_500", traceId = traceId))
        store.dispatch(AgentWorkerTimelineIntent.SyncRefreshKey("running|t2"))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(AgentWorkerTimelineLoadStatus.Loaded, store.state.value.status)
        assertEquals(1, store.state.value.events.size)

        // Close resets state.
        store.dispatch(AgentWorkerTimelineIntent.Close)
        assertEquals(AgentWorkerTimelineLoadStatus.Idle, store.state.value.status)
        assertNull(store.state.value.open)

        // Initial 404 worker_timeline_not_found -> Unavailable.
        nextAnswer = DataResult.Error(
            ScreenError(
                code = AgentControlErrorCodes.WorkerTimelineNotFound,
                retryable = false,
                serverCode = AgentControlErrorCodes.WorkerTimelineNotFound,
                traceId = traceId,
            )
        )
        store.dispatch(AgentWorkerTimelineIntent.Open(agent1, open.copy(launchId = "worker-w2-gone")))
        runCurrent()
        assertEquals(AgentWorkerTimelineLoadStatus.Unavailable, store.state.value.status)

        // Initial 500 -> Error preserving traceId, and Refresh recovers into Loaded.
        val serverErr = ScreenError(code = "http_500", retryable = true, traceId = traceId)
        nextAnswer = DataResult.Error(serverErr)
        store.dispatch(AgentWorkerTimelineIntent.Open(agent1, open.copy(launchId = "worker-w3-err")))
        runCurrent()
        assertEquals(AgentWorkerTimelineLoadStatus.Error, store.state.value.status)
        assertEquals(traceId, store.state.value.error?.traceId)

        nextAnswer = DataResult.Content(AgentWorkerTimelineSnapshot("worker-w3-err", emptyList()))
        store.dispatch(AgentWorkerTimelineIntent.Refresh)
        runCurrent()
        assertEquals(AgentWorkerTimelineLoadStatus.Loaded, store.state.value.status)
        assertNull(store.state.value.error)
    }

    @Test
    fun chipStateAndRefreshKeyMatchWebContract() {
        assertEquals(AgentWorkerTimelineChipState.Working, agentWorkerTimelineChipState(isLive = true, reason = null))
        assertEquals(AgentWorkerTimelineChipState.Ended, agentWorkerTimelineChipState(isLive = false, reason = null))
        assertEquals(AgentWorkerTimelineChipState.Finished, agentWorkerTimelineChipState(isLive = false, reason = AgentWorkerEndReason.Done))
        assertEquals(AgentWorkerTimelineChipState.Error, agentWorkerTimelineChipState(isLive = false, reason = AgentWorkerEndReason.TurnFailed))
        assertEquals(AgentWorkerTimelineChipState.Error, agentWorkerTimelineChipState(isLive = false, reason = AgentWorkerEndReason.StartFailed))
        assertEquals(AgentWorkerTimelineChipState.Error, agentWorkerTimelineChipState(isLive = false, reason = AgentWorkerEndReason.Exited))
        assertEquals(AgentWorkerTimelineChipState.Stopped, agentWorkerTimelineChipState(isLive = false, reason = AgentWorkerEndReason.StoppedByPerson))
        assertEquals(AgentWorkerTimelineChipState.Stopped, agentWorkerTimelineChipState(isLive = false, reason = AgentWorkerEndReason.WaitingOn))

        assertEquals("ended:pending", agentWorkerTimelineRefreshKey(live = null, ended = null))
        assertEquals(
            "ended:2026-11-25T08:47:00.000Z",
            agentWorkerTimelineRefreshKey(live = null, ended = recentRow()),
        )
        assertTrue(agentWorkerTimelineRefreshKey(live = liveRow(), ended = null).startsWith("running|2026-11-25T10:10:00.000Z"))
    }
}
