package build.raft.app.data

import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationWorkerStateKind
import build.raft.app.model.ConversationWorkerStateUpdated
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationWorkerStateContractAndStoreTest {
    private val mapper = RealtimeEventMapper()
    private val server = "srv-1"

    private fun mapWorker(payload: String) =
        mapper.map(RealtimeSocketEvents.ConversationWorkerState, payload)

    @Test
    fun socketContractSubscribesToConversationWorkerState() {
        assertEquals("conversation:worker:state", RealtimeSocketEvents.ConversationWorkerState)
        assertEquals("conversation:worker:state", GeneratedRealtimeContract.SocketEvent.ConversationWorkerState)
        assertTrue(RealtimeSocketEvents.ConversationWorkerState in RealtimeSocketEvents.ServerWireEvents)
    }

    @Test
    fun realtimeMapperParsesAllValidConversationWorkerStatesStrictly() {
        val threadHandling =
            assertIs<ConversationWorkerStateUpdated>(
                mapWorker(
                    """
                    {
                      "channelId": "ch-1",
                      "parentMessageId": "msg-root",
                      "agentId": "agent-sage",
                      "worker": "w3",
                      "state": "handling",
                      "sinceAt": "2026-11-26T10:00:00.000Z",
                      "doing": "tool",
                      "tool": "Bash",
                      "quietSinceAt": "2026-11-26T10:05:00.000Z"
                    }
                    """.trimIndent(),
                ),
            )
        assertEquals("ch-1", threadHandling.channelId.value)
        assertEquals("msg-root", threadHandling.parentMessageId?.value)
        assertNull(threadHandling.taskId)
        assertEquals("agent-sage", threadHandling.agentId.value)
        assertEquals("w3", threadHandling.worker)
        assertEquals(ConversationWorkerStateKind.Handling, threadHandling.state)
        assertEquals("2026-11-26T10:00:00.000Z", threadHandling.sinceAt)
        assertEquals("tool", threadHandling.doing)
        assertEquals("Bash", threadHandling.tool)
        assertEquals("2026-11-26T10:05:00.000Z", threadHandling.quietSinceAt)

        val taskWaiting =
            assertIs<ConversationWorkerStateUpdated>(
                mapWorker(
                    """
                    {
                      "channelId": "ch-task",
                      "taskId": "task-9",
                      "agentId": "agent-kirby",
                      "state": "waiting",
                      "sinceAt": "2026-11-26T10:00:00.000Z",
                      "waitingSinceAt": "2026-11-26T10:03:00.000Z",
                      "waitingFrom": "alice"
                    }
                    """.trimIndent(),
                ),
            )
        assertEquals("task-9", taskWaiting.taskId)
        assertNull(taskWaiting.parentMessageId)
        assertEquals(ConversationWorkerStateKind.Waiting, taskWaiting.state)
        assertEquals("2026-11-26T10:03:00.000Z", taskWaiting.waitingSinceAt)
        assertEquals("alice", taskWaiting.waitingFrom)

        assertEquals(
            ConversationWorkerStateKind.Stalled,
            assertIs<ConversationWorkerStateUpdated>(
                mapWorker("""{"channelId":"ch-1","agentId":"a1","state":"stalled","sinceAt":"2026-11-26T10:00:00.000Z"}"""),
            ).state,
        )
        assertEquals(
            ConversationWorkerStateKind.HandedBack,
            assertIs<ConversationWorkerStateUpdated>(
                mapWorker("""{"channelId":"ch-1","agentId":"a1","state":"handed_back","sinceAt":"2026-11-26T10:00:00.000Z"}"""),
            ).state,
        )
        assertEquals(
            ConversationWorkerStateKind.None,
            assertIs<ConversationWorkerStateUpdated>(
                mapWorker("""{"channelId":"ch-1","agentId":"a1","state":"none","sinceAt":"2026-11-26T10:00:00.000Z"}"""),
            ).state,
        )
    }

    @Test
    fun realtimeMapperDropsMalformedOrNonStringConversationWorkerStatePayloads() {
        // Missing required fields or unknown state.
        assertNull(mapWorker("""{"agentId":"a1","state":"handling","sinceAt":"2026-11-26T10:00:00.000Z"}"""))
        assertNull(mapWorker("""{"channelId":"ch-1","state":"handling","sinceAt":"2026-11-26T10:00:00.000Z"}"""))
        assertNull(mapWorker("""{"channelId":"ch-1","agentId":"a1","state":"handling"}"""))
        assertNull(mapWorker("""{"channelId":"ch-1","agentId":"a1","state":"unknown","sinceAt":"2026-11-26T10:00:00.000Z"}"""))
        // Explicit null or non-string optional fields are rejected (matches web conversationWorkerStateOf).
        assertNull(mapWorker("""{"channelId":"ch-1","agentId":"a1","state":"handling","sinceAt":"2026-11-26T10:00:00.000Z","worker":null}"""))
        assertNull(mapWorker("""{"channelId":"ch-1","agentId":"a1","state":"handling","sinceAt":"2026-11-26T10:00:00.000Z","tool":123}"""))
        assertNull(mapWorker("""{"channelId":"ch-1","agentId":"a1","state":"handling","sinceAt":"2026-11-26T10:00:00.000Z","waitingFrom":false}"""))
    }

    @Test
    fun fetchConversationWorkerStateBuildsQueryParamsAndHandles404Safely() = runTest {
        val calls = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        val fakeClient =
            object : RaftNetworkClient {
                var nextResponse: HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """
                            {
                              "states": [
                                {
                                  "channelId": "ch-1",
                                  "parentMessageId": "msg-root",
                                  "agentId": "agent-sage",
                                  "worker": "w3",
                                  "state": "handling",
                                  "sinceAt": "2026-11-26T10:00:00.000Z"
                                },
                                {
                                  "channelId": "ch-1",
                                  "agentId": "bad-entry",
                                  "state": "bogus",
                                  "sinceAt": "2026-11-26T10:00:00.000Z"
                                }
                              ]
                            }
                            """.trimIndent(),
                        responseHeaders = mapOf("X-Trace-Id" to listOf("trace-worker-ok")),
                    )

                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse {
                    calls += path to query
                    return nextResponse
                }
            }

        val threadTarget = WorkerConversationTarget(channelId = "ch-1", parentMessageId = "msg-root")
        val threadResult = fetchConversationWorkerState(fakeClient, ServerId(server), threadTarget)
        assertEquals(1, threadResult.size)
        assertEquals("agent-sage", threadResult.single().agentId.value)
        assertEquals("conversations/worker-state" to listOf("channelId" to "ch-1", "parentMessageId" to "msg-root"), calls[0])

        val taskTarget = WorkerConversationTarget(channelId = "ch-task", taskId = "task-7")
        fakeClient.nextResponse =
            HomeNetworkResponse(
                code = 404,
                bodyText = """{"error":"worker_v1_off"}""",
                responseHeaders = mapOf("X-Trace-Id" to listOf("trace-worker-off")),
            )
        val taskResult = fetchConversationWorkerState(fakeClient, ServerId(server), taskTarget)
        assertTrue(taskResult.isEmpty())
        assertEquals("conversations/worker-state" to listOf("channelId" to "ch-task", "taskId" to "task-7"), calls[1])
    }

    @Test
    fun storeFlagOffPerformsZeroRequestsAndIgnoresRealtimeEvents() = runTest {
        var fetches = 0
        val store =
            SharedConversationWorkerStateStore(
                scope = backgroundScope,
                fetchStates = { _, _ ->
                    fetches += 1
                    emptyList()
                },
                isEnabledForServer = { false },
                now = { testScheduler.currentTime },
            )
        val target = WorkerConversationTarget(channelId = "ch-1", parentMessageId = "msg-root")
        store.acquire(server, target)
        store.refresh(server, target)
        store.consume(
            server,
            ConversationWorkerStateUpdated(
                channelId = ChannelId("ch-1"),
                parentMessageId = MessageId("msg-root"),
                agentId = MemberId("agent-sage"),
                worker = "w1",
                state = ConversationWorkerStateKind.Handling,
                sinceAt = "2026-11-26T10:00:00.000Z",
            ),
        )
        runCurrent()
        assertEquals(0, fetches)
        assertTrue(store.state.value.heldStatesFor(server, target).isEmpty())
    }

    @Test
    fun storeHandlesLivePushRacingInFlightReadTimestampsAndUnmountForget() = runTest {
        val target = WorkerConversationTarget(channelId = "ch-1", parentMessageId = "msg-root")
        val pendingRead = CompletableDeferred<List<ConversationWorkerStateUpdated>>()
        var fetchCalls = 0
        val store =
            SharedConversationWorkerStateStore(
                scope = backgroundScope,
                fetchStates = { _, _ ->
                    fetchCalls += 1
                    if (fetchCalls == 1) pendingRead.await() else emptyList()
                },
                isEnabledForServer = { true },
                now = { testScheduler.currentTime },
            )

        advanceTimeBy(1_000L)
        store.acquire(server, target)
        runCurrent()
        assertEquals(1, fetchCalls)

        // While initial read is in flight, agent-a transitions to None and agent-b updates via push.
        advanceTimeBy(500L)
        store.consume(
            server,
            ConversationWorkerStateUpdated(
                channelId = ChannelId("ch-1"),
                parentMessageId = MessageId("msg-root"),
                agentId = MemberId("agent-a"),
                state = ConversationWorkerStateKind.None,
                sinceAt = "2026-11-26T10:00:00.000Z",
            ),
        )
        store.consume(
            server,
            ConversationWorkerStateUpdated(
                channelId = ChannelId("ch-1"),
                parentMessageId = MessageId("msg-root"),
                agentId = MemberId("agent-b"),
                worker = "w2",
                state = ConversationWorkerStateKind.Handling,
                sinceAt = "2026-11-26T10:00:00.000Z",
                doing = "tool",
                tool = "Read",
            ),
        )

        // Older in-flight read returns stale state for agent-a and agent-b plus agent-c.
        advanceTimeBy(500L)
        pendingRead.complete(
            listOf(
                ConversationWorkerStateUpdated(
                    channelId = ChannelId("ch-1"),
                    parentMessageId = MessageId("msg-root"),
                    agentId = MemberId("agent-a"),
                    worker = "w1",
                    state = ConversationWorkerStateKind.Handling,
                    sinceAt = "2026-11-26T10:00:00.000Z",
                ),
                ConversationWorkerStateUpdated(
                    channelId = ChannelId("ch-1"),
                    parentMessageId = MessageId("msg-root"),
                    agentId = MemberId("agent-b"),
                    worker = "w2",
                    state = ConversationWorkerStateKind.Handling,
                    sinceAt = "2026-11-26T10:00:00.000Z",
                    doing = "model",
                ),
                ConversationWorkerStateUpdated(
                    channelId = ChannelId("ch-1"),
                    parentMessageId = MessageId("msg-root"),
                    agentId = MemberId("agent-c"),
                    worker = "w4",
                    state = ConversationWorkerStateKind.Handling,
                    sinceAt = "2026-11-26T10:00:00.000Z",
                ),
            ),
        )
        runCurrent()

        val held = store.state.value.heldStatesFor(server, target)
        // agent-a stayed removed (None push won), agent-b kept pushed "Read" tool, agent-c came from read.
        assertEquals(listOf("agent-b", "agent-c"), held.map { it.state.agentId.value })
        assertEquals("Read", held[0].state.tool)
        assertEquals(1_500L, held[0].stateSeenAtMs)
        assertEquals(1_500L, held[0].doingSeenAtMs)

        // Same state kind + same (doing, tool) preserves both seenAt timestamps; changing tool resets doingSeenAtMs only.
        advanceTimeBy(2_000L)
        store.consume(
            server,
            held[0].state.copy(tool = "Bash"),
        )
        val afterToolChange = store.state.value.heldStatesFor(server, target).first { it.state.agentId.value == "agent-b" }
        assertEquals(1_500L, afterToolChange.stateSeenAtMs)
        assertEquals(4_000L, afterToolChange.doingSeenAtMs)

        // Ref-counting: second acquire + first release keeps state; last release forgets it.
        store.acquire(server, target)
        store.release(server, target)
        assertTrue(store.state.value.heldStatesFor(server, target).isNotEmpty())
        store.release(server, target)
        assertTrue(store.state.value.heldStatesFor(server, target).isEmpty())

        // Server switch resets bound state.
        store.apply(server, held[0].state, nowMs = 5_000L)
        assertTrue(store.state.value.heldStatesFor(server, target).isNotEmpty())
        store.bindServer("srv-2")
        assertTrue(store.state.value.heldStatesFor(server, target).isEmpty())
    }

    @Test
    fun restAndRealtimeMessageParsersPlumbSystemSubtype() {
        val restBody =
            """
            {
              "messages": [
                {
                  "id": "m-worker-ended",
                  "channelId": "ch-task",
                  "seq": 12,
                  "senderType": "user",
                  "senderId": "system",
                  "senderName": "System",
                  "messageType": "system",
                  "systemSubtype": "worker.ended",
                  "content": "Worker w3 of @Sage finished (ran 12m): Done.",
                  "createdAt": "2026-11-26T10:12:00.000Z"
                },
                {
                  "id": "m-plain",
                  "channelId": "ch-task",
                  "seq": 13,
                  "senderType": "user",
                  "senderId": "system",
                  "senderName": "System",
                  "messageType": "system",
                  "systemSubtype": "   ",
                  "content": "Channel renamed",
                  "createdAt": "2026-11-26T10:13:00.000Z"
                }
              ]
            }
            """.trimIndent()
        val parsedRest = parseSharedChannelMessages(restBody, ThreadId("ch-task"))
        assertEquals(2, parsedRest.messages.size)
        assertEquals(WORKER_ENDED_RECORD_SUBTYPE, parsedRest.messages[0].systemSubtype)
        assertNull(parsedRest.messages[1].systemSubtype)

        val liveNew =
            assertIs<MessageNew>(
                mapper.map(
                    RealtimeSocketEvents.MessageNew,
                    """
                    {
                      "id": "m-live-ended",
                      "channelId": "ch-task",
                      "seq": 14,
                      "senderType": "user",
                      "senderId": "system",
                      "senderName": "System",
                      "messageType": "system",
                      "systemSubtype": "worker.ended",
                      "content": "Worker w3 of @Sage finished (ran 5m): All done.",
                      "createdAt": "2026-11-26T10:15:00.000Z"
                    }
                    """.trimIndent(),
                ),
            )
        assertEquals(WORKER_ENDED_RECORD_SUBTYPE, liveNew.message.systemSubtype)
        assertEquals(WORKER_ENDED_RECORD_SUBTYPE, assertNotNull(liveNew.systemProvenance).systemSubtype)

        val liveUpdated =
            assertIs<MessageUpdated>(
                mapper.map(
                    RealtimeSocketEvents.MessageUpdated,
                    """
                    {
                      "id": "m-live-ended",
                      "channelId": "ch-task",
                      "seq": 14,
                      "senderType": "user",
                      "senderId": "system",
                      "senderName": "System",
                      "messageType": "system",
                      "systemSubtype": "worker.ended",
                      "content": "Worker w3 of @Sage finished (ran 5m): All done.",
                      "createdAt": "2026-11-26T10:15:00.000Z"
                    }
                    """.trimIndent(),
                ),
            )
        assertEquals(WORKER_ENDED_RECORD_SUBTYPE, liveUpdated.message.systemSubtype)
    }
}
