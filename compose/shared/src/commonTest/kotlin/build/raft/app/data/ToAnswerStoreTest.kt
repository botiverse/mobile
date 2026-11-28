package build.raft.app.data

import build.raft.app.ConversationOpenResolution
import build.raft.app.ToAnswerPluralCategory
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ToAnswerChangeKind
import build.raft.app.model.ToAnswerChanged
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.toAnswerDaysLeft
import build.raft.app.toAnswerPluralCategory
import build.raft.app.toHomeMessageOpenRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for Activity's "To answer" API parser, [SharedToAnswerStore], deadline/plural helpers,
 * and conversation open routing (parity with web `packages/web/tests/toAnswerStore.test.ts`,
 * botiverse/slock #17252 / #17261).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToAnswerStoreTest {
    private val baseNowMs = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-11-27T10:05:00.000Z"))

    private class FakeToAnswerApi : RaftToAnswerApi {
        val calls = mutableListOf<ServerId>()
        private val queue = ArrayDeque<suspend () -> DataResult<ToAnswerListResponse>>()

        fun enqueue(response: ToAnswerListResponse) {
            queue.addLast { DataResult.Content(response) }
        }

        fun enqueueError(code: String = "network") {
            queue.addLast { DataResult.Error(ScreenError(code = code, retryable = true, traceId = null)) }
        }

        fun enqueueSuspend(handler: suspend () -> DataResult<ToAnswerListResponse>) {
            queue.addLast(handler)
        }

        override suspend fun listToAnswer(serverId: ServerId): DataResult<ToAnswerListResponse> {
            calls += serverId
            val next = queue.removeFirstOrNull() ?: return DataResult.Content(ToAnswerListResponse(emptyList(), 0))
            return next()
        }
    }

    private fun item(
        messageId: String,
        kind: ToAnswerCardKind = ToAnswerCardKind.Question,
        addressee: ToAnswerAddressee = ToAnswerAddressee.You,
        conversation: ToAnswerConversation = ToAnswerConversation(id = "chan-1", type = "channel", name = "proj-mobile"),
        requester: ToAnswerRequester? = ToAnswerRequester(ToAnswerRequesterType.Agent, "agent-1", "Lagoon"),
        text: String? = "Where should a push land?",
        moreQuestions: Int = 0,
        requestTitle: ActionCardSpecMsg? = null,
        createdAt: String = "2026-11-27T10:00:00.000Z",
        expiresAt: String? = null,
    ): ToAnswerItem =
        ToAnswerItem(
            messageId = messageId,
            kind = kind,
            addressee = addressee,
            conversation = conversation,
            requester = requester,
            text = text,
            moreQuestions = moreQuestions,
            requestTitle = requestTitle,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )

    @Test
    fun parseToAnswerListResponseParsesQuestionConfirmRequestAndDropsMalformedRows() {
        val body =
            """
            {
              "items": [
                {
                  "messageId": "m-q",
                  "kind": "question",
                  "addressee": "you",
                  "conversation": { "id": "ch-1", "type": "channel", "name": "proj-mobile" },
                  "requester": { "type": "agent", "id": "agent-1", "name": "Lagoon" },
                  "text": "Where should a push land?",
                  "moreQuestions": 2,
                  "createdAt": "2026-11-27T10:00:00.000Z",
                  "expiresAt": null
                },
                {
                  "messageId": "m-req",
                  "kind": "request",
                  "addressee": "anyone",
                  "conversation": {
                    "id": "th-1",
                    "type": "thread",
                    "name": "thread-1",
                    "parent": { "id": "ch-2", "type": "private", "name": "ops" },
                    "parentMessageId": "p-1"
                  },
                  "requester": null,
                  "text": "Need read access to repo",
                  "moreQuestions": 0,
                  "request": {
                    "title": {
                      "key": "actionCard.request.grantAccess",
                      "text": {
                        "en": "Approve {app} access",
                        "zh-cn": "批准 {app} 权限"
                      },
                      "params": {
                        "app": "GitHub"
                      }
                    }
                  },
                  "createdAt": "2026-11-27T09:30:00.000Z",
                  "expiresAt": "2026-12-02T10:05:00.000Z"
                },
                {
                  "messageId": "",
                  "kind": "confirm",
                  "addressee": "you",
                  "conversation": { "id": "ch-1", "type": "channel", "name": "bad" },
                  "createdAt": "2026-11-27T09:00:00.000Z"
                }
              ],
              "count": 250,
              "capped": true
            }
            """.trimIndent()

        val parsed = assertNotNull(parseToAnswerListResponse(body))
        assertEquals(250, parsed.count)
        assertTrue(parsed.capped)
        assertEquals(2, parsed.items.size)

        val q = parsed.items[0]
        assertEquals("m-q", q.messageId)
        assertEquals(ToAnswerCardKind.Question, q.kind)
        assertEquals(ToAnswerAddressee.You, q.addressee)
        assertEquals("Lagoon", q.requester?.name)
        assertEquals(ToAnswerRequesterType.Agent, q.requester?.type)
        assertEquals(2, q.moreQuestions)
        assertNull(q.requestTitle)

        val req = parsed.items[1]
        assertEquals("m-req", req.messageId)
        assertEquals(ToAnswerCardKind.Request, req.kind)
        assertEquals(ToAnswerAddressee.Anyone, req.addressee)
        assertNull(req.requester)
        assertEquals("ch-2", req.conversation.parent?.id)
        assertEquals("private", req.conversation.parent?.type)
        assertEquals("p-1", req.conversation.parentMessageId)
        assertEquals("Need read access to repo", req.text)
        assertEquals("2026-12-02T10:05:00.000Z", req.expiresAt)
        val titleMsg = assertNotNull(req.requestTitle)
        assertEquals("Approve GitHub access", renderActionCardMsgText(titleMsg, "en"))
        assertEquals("批准 GitHub 权限", renderActionCardMsgText(titleMsg, "zh-cn"))

        assertNull(parseToAnswerListResponse("not-json"))
        assertNull(parseToAnswerListResponse("""{"count":0}"""))
    }

    @Test
    fun flagOffNeverFetchesAndIgnoresRealtimeEvents() = runTest {
        val api = FakeToAnswerApi()
        val store =
            SharedToAnswerStore(
                api = api,
                scope = this,
                isFlagEnabled = { false },
                nowEpochMillis = { baseNowMs + testScheduler.currentTime },
            )

        store.load("srv-1")
        store.loadNow("srv-1")
        store.onToAnswerChanged(
            "srv-1",
            ToAnswerChanged(ServerId("srv-1"), MessageId("m1"), ToAnswerChangeKind.Opened),
        )
        store.onRealtimeReconnected("srv-1")
        advanceTimeBy(TO_ANSWER_REFETCH_DEBOUNCE_MS * 2)
        runCurrent()

        assertTrue(api.calls.isEmpty(), "flag off must never fetch GET /api/actions/to-answer")
        assertEquals(ToAnswerStatus.Idle, store.state.value.status)
    }

    @Test
    fun loadFillsListAndEventsForUnloadedOrOtherServerDoNothing() = runTest {
        val api = FakeToAnswerApi()
        val store =
            SharedToAnswerStore(
                api = api,
                scope = this,
                nowEpochMillis = { baseNowMs + testScheduler.currentTime },
            )

        store.onToAnswerChanged(
            "srv-1",
            ToAnswerChanged(ServerId("srv-1"), MessageId("m1"), ToAnswerChangeKind.Opened),
        )
        advanceTimeBy(TO_ANSWER_REFETCH_DEBOUNCE_MS * 2)
        runCurrent()
        assertTrue(api.calls.isEmpty(), "never loaded: no refetch")

        api.enqueue(ToAnswerListResponse(items = listOf(item("m2"), item("m1")), count = 2))
        store.loadNow("srv-1")
        assertEquals(listOf(ServerId("srv-1")), api.calls)
        assertEquals(listOf("m2", "m1"), store.state.value.items.map { it.messageId })
        assertEquals(2, store.state.value.count)
        assertEquals(ToAnswerStatus.Loaded, store.state.value.status)

        // Event for another server does nothing.
        store.onToAnswerChanged(
            "srv-2",
            ToAnswerChanged(ServerId("srv-2"), MessageId("m1"), ToAnswerChangeKind.Ended, state = "executed", byName = "Andy"),
        )
        assertTrue(store.state.value.ended.isEmpty())
        assertEquals(2, store.state.value.count)
    }

    @Test
    fun endedDropsCountAtOnceLingersForTwoSecondsThenRemovesRow() = runTest {
        val api = FakeToAnswerApi()
        api.enqueue(ToAnswerListResponse(items = listOf(item("m2"), item("m1")), count = 2))
        val store =
            SharedToAnswerStore(
                api = api,
                scope = this,
                nowEpochMillis = { baseNowMs + testScheduler.currentTime },
            )

        store.loadNow("srv-1")
        val endedEvent =
            ToAnswerChanged(
                serverId = ServerId("srv-1"),
                messageId = MessageId("m1"),
                change = ToAnswerChangeKind.Ended,
                state = "executed",
                byUserId = "u-andy",
                byName = "AndyLok",
            )
        store.onToAnswerChanged("srv-1", endedEvent)

        assertEquals(
            mapOf("m1" to ToAnswerEnded(state = "executed", byUserId = "u-andy", byName = "AndyLok")),
            store.state.value.ended,
        )
        assertEquals(1, store.state.value.count)
        assertEquals(2, store.state.value.items.size, "row stays visible during 2s linger")

        // Duplicate ended event is a no-op.
        store.onToAnswerChanged("srv-1", endedEvent)
        assertEquals(1, store.state.value.count)

        advanceTimeBy(TO_ANSWER_ENDED_LINGER_MS)
        runCurrent()

        assertEquals(listOf("m2"), store.state.value.items.map { it.messageId })
        assertTrue(store.state.value.ended.isEmpty())
        assertEquals(1, store.state.value.count)
    }

    @Test
    fun openedBurstAndReconnectCoalesceIntoOneDebouncedRefetch() = runTest {
        val api = FakeToAnswerApi()
        api.enqueue(ToAnswerListResponse(items = listOf(item("m1")), count = 1))
        api.enqueue(ToAnswerListResponse(items = listOf(item("m3"), item("m1")), count = 2))
        val store =
            SharedToAnswerStore(
                api = api,
                scope = this,
                nowEpochMillis = { baseNowMs + testScheduler.currentTime },
            )

        store.loadNow("srv-1")
        store.onToAnswerChanged("srv-1", ToAnswerChanged(ServerId("srv-1"), MessageId("m3"), ToAnswerChangeKind.Opened))
        store.onToAnswerChanged("srv-1", ToAnswerChanged(ServerId("srv-1"), MessageId("m4"), ToAnswerChangeKind.Opened))
        store.onRealtimeReconnected("srv-1")

        advanceTimeBy(TO_ANSWER_REFETCH_DEBOUNCE_MS)
        runCurrent()

        assertEquals(2, api.calls.size, "burst of opened + reconnect folds into one re-read")
        assertEquals(listOf("m3", "m1"), store.state.value.items.map { it.messageId })
        assertEquals(2, store.state.value.count)
    }

    @Test
    fun countUsesServerCountBeyondItemCapAndSubtractsEndedRows() = runTest {
        val api = FakeToAnswerApi()
        api.enqueue(ToAnswerListResponse(items = listOf(item("m2"), item("m1")), count = 250, capped = true))
        val store =
            SharedToAnswerStore(
                api = api,
                scope = this,
                nowEpochMillis = { baseNowMs + testScheduler.currentTime },
            )

        store.loadNow("srv-1")
        assertEquals(250, store.state.value.count)
        assertTrue(store.state.value.capped)

        store.onToAnswerChanged(
            "srv-1",
            ToAnswerChanged(ServerId("srv-1"), MessageId("m1"), ToAnswerChangeKind.Ended, state = "withdrawn"),
        )
        assertEquals(249, store.state.value.count)
        advanceTimeBy(TO_ANSWER_ENDED_LINGER_MS)
        runCurrent()
        assertEquals(249, store.state.value.count)
        assertEquals(listOf("m2"), store.state.value.items.map { it.messageId })
    }

    @Test
    fun inFlightReadStartedBeforeRowEndedDoesNotResurrectRowAfterLinger() = runTest {
        val api = FakeToAnswerApi()
        val slowRead = CompletableDeferred<DataResult<ToAnswerListResponse>>()
        api.enqueue(ToAnswerListResponse(items = listOf(item("m1"), item("m2")), count = 2))
        api.enqueueSuspend { slowRead.await() }
        api.enqueue(ToAnswerListResponse(items = listOf(item("m2")), count = 1))
        val store =
            SharedToAnswerStore(
                api = api,
                scope = this,
                nowEpochMillis = { baseNowMs + testScheduler.currentTime },
            )

        store.loadNow("srv-1")
        val pending = launch { store.loadNow("srv-1") }
        runCurrent()

        store.onToAnswerChanged(
            "srv-1",
            ToAnswerChanged(ServerId("srv-1"), MessageId("m1"), ToAnswerChangeKind.Ended, state = "executed", byName = "Andy"),
        )
        advanceTimeBy(TO_ANSWER_ENDED_LINGER_MS)
        runCurrent()
        assertEquals(listOf("m2"), store.state.value.items.map { it.messageId })

        slowRead.complete(DataResult.Content(ToAnswerListResponse(items = listOf(item("m1"), item("m2")), count = 2)))
        pending.join()

        assertEquals(listOf("m2"), store.state.value.items.map { it.messageId }, "stale read must not resurrect ended row")
        assertEquals(1, store.state.value.count)

        // A read started after the row ended accepts server truth again.
        store.loadNow("srv-1")
        assertEquals(listOf("m2"), store.state.value.items.map { it.messageId })
        assertEquals(1, store.state.value.count)
    }

    @Test
    fun deadlineExpiryEndsRowWithoutSocketEventAndFailedRereadKeepsLoadedRows() = runTest {
        val api = FakeToAnswerApi()
        api.enqueue(
            ToAnswerListResponse(
                items = listOf(item("m1", kind = ToAnswerCardKind.Request, expiresAt = "2026-11-27T10:06:00.000Z"), item("m2")),
                count = 2,
            )
        )
        api.enqueueError("network")
        api.enqueueError("network")
        val store =
            SharedToAnswerStore(
                api = api,
                scope = this,
                nowEpochMillis = { baseNowMs + testScheduler.currentTime },
            )

        store.loadNow("srv-1")
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(mapOf("m1" to ToAnswerEnded(state = "expired")), store.state.value.ended)
        assertEquals(1, store.state.value.count)

        // Failed re-read keeps loaded rows.
        store.loadNow("srv-1")
        assertEquals(ToAnswerStatus.Loaded, store.state.value.status)
        assertTrue(store.state.value.items.any { it.messageId == "m2" })

        advanceTimeBy(TO_ANSWER_ENDED_LINGER_MS)
        runCurrent()
        assertEquals(listOf("m2"), store.state.value.items.map { it.messageId })

        // Failed first read after reset reports Failed.
        store.clear()
        store.loadNow("srv-1")
        assertEquals(ToAnswerStatus.Failed, store.state.value.status)
        assertEquals("network", store.state.value.errorCode)
    }

    @Test
    fun reconnectDoesNotRefetchInactiveServer() = runTest {
        val api = FakeToAnswerApi()
        api.enqueue(ToAnswerListResponse(items = listOf(item("m1")), count = 1))
        var activeServer = "srv-1"
        val store =
            SharedToAnswerStore(
                api = api,
                scope = this,
                isServerActive = { it == activeServer },
                nowEpochMillis = { baseNowMs + testScheduler.currentTime },
            )

        store.loadNow("srv-1")
        activeServer = "srv-2"
        store.onRealtimeReconnected("srv-1")
        advanceTimeBy(TO_ANSWER_REFETCH_DEBOUNCE_MS * 2)
        runCurrent()

        assertEquals(1, api.calls.size, "server the user left must not be refetched on reconnect")
    }

    @Test
    fun runtimeFlagOffServerResetAndClearCancelAllPendingTimersCleanly() = runTest {
        val api = FakeToAnswerApi()
        var enabled = true
        api.enqueue(
            ToAnswerListResponse(
                items =
                    listOf(
                        item("m1", kind = ToAnswerCardKind.Request, expiresAt = "2026-11-27T10:15:00.000Z"),
                        item("m2"),
                    ),
                count = 2,
            )
        )
        val store =
            SharedToAnswerStore(
                api = api,
                scope = this,
                isFlagEnabled = { enabled },
                nowEpochMillis = { baseNowMs + testScheduler.currentTime },
            )

        store.loadNow("srv-1")
        // Arm all three timer families at once: expiry (10m), linger (2s), and debounced refetch (500ms).
        store.onToAnswerChanged(
            "srv-1",
            ToAnswerChanged(ServerId("srv-1"), MessageId("m2"), ToAnswerChangeKind.Ended, state = "withdrawn"),
        )
        store.onToAnswerChanged(
            "srv-1",
            ToAnswerChanged(ServerId("srv-1"), MessageId("m3"), ToAnswerChangeKind.Opened),
        )

        // Turning flag off at runtime clears state and cancels every pending timer cleanly
        // (returning from runTest without advancing time proves zero uncompleted coroutines).
        enabled = false
        store.onFlagStateChanged("srv-1", enabled = false)
        assertEquals(SharedToAnswerState(), store.state.value)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun daysLeftAndPluralCategoriesMatchLocaleContracts() {
        assertNull(toAnswerDaysLeft(null, baseNowMs))
        assertNull(toAnswerDaysLeft("2026-11-27T10:05:00.000Z", baseNowMs))
        assertEquals(1, toAnswerDaysLeft("2026-11-27T10:06:00.000Z", baseNowMs))
        assertEquals(5, toAnswerDaysLeft("2026-12-02T10:05:00.000Z", baseNowMs))

        assertEquals(ToAnswerPluralCategory.One, toAnswerPluralCategory(1, "en"))
        assertEquals(ToAnswerPluralCategory.Other, toAnswerPluralCategory(2, "en"))
        assertEquals(ToAnswerPluralCategory.Other, toAnswerPluralCategory(0, "en"))
        assertEquals(ToAnswerPluralCategory.One, toAnswerPluralCategory(1, "ru"))
        assertEquals(ToAnswerPluralCategory.One, toAnswerPluralCategory(21, "ru"))
        assertEquals(ToAnswerPluralCategory.Few, toAnswerPluralCategory(2, "ru"))
        assertEquals(ToAnswerPluralCategory.Few, toAnswerPluralCategory(24, "ru"))
        assertEquals(ToAnswerPluralCategory.Many, toAnswerPluralCategory(5, "ru"))
        assertEquals(ToAnswerPluralCategory.Many, toAnswerPluralCategory(11, "ru"))
        assertEquals(ToAnswerPluralCategory.Many, toAnswerPluralCategory(12, "ru"))
    }

    @Test
    fun toHomeMessageOpenRequestRoutesChannelDmThreadAndTaskAtCardMessageId() {
        val dir = SharedConversationDirectoryState()

        val channelResolution = item("m-chan").toHomeMessageOpenRequest("srv-1", dir)
        val channelReq = assertIs<ConversationOpenResolution.Resolved>(channelResolution).request
        assertEquals(ConversationKind.Channel, channelReq.conversationIdentity.kind)
        assertEquals(ChannelId("chan-1"), channelReq.conversationIdentity.channelId)
        assertEquals("m-chan", channelReq.anchorMessageId)

        val dmResolution =
            item("m-dm", conversation = ToAnswerConversation("dm-1", "dm", "alice"))
                .toHomeMessageOpenRequest("srv-1", dir)
        val dmReq = assertIs<ConversationOpenResolution.Resolved>(dmResolution).request
        assertEquals(ConversationKind.DirectMessage, dmReq.conversationIdentity.kind)
        assertEquals(ChannelId("dm-1"), dmReq.conversationIdentity.channelId)
        assertEquals("m-dm", dmReq.anchorMessageId)

        val threadResolution =
            item(
                "m-thread",
                conversation =
                    ToAnswerConversation(
                        id = "thread-1",
                        type = "thread",
                        name = "thread-1",
                        parent = ToAnswerConversationParent("chan-2", "channel", "wg-mobile-sync"),
                        parentMessageId = "p-1",
                    ),
            ).toHomeMessageOpenRequest("srv-1", dir)
        val threadReq = assertIs<ConversationOpenResolution.Resolved>(threadResolution).request
        assertEquals(ConversationKind.Thread, threadReq.conversationIdentity.kind)
        assertEquals(ChannelId("chan-2"), threadReq.conversationIdentity.thread?.parentChannelId)
        assertEquals(MessageId("p-1"), threadReq.conversationIdentity.thread?.parentMessageId)
        assertEquals(ChannelId("thread-1"), threadReq.conversationIdentity.thread?.streamChannelId)
        assertEquals("m-thread", threadReq.anchorMessageId)

        val taskResolution =
            item(
                "m-task",
                conversation =
                    ToAnswerConversation(
                        id = "task-conv-1",
                        type = "task",
                        name = "task-conv-1",
                        parent = ToAnswerConversationParent("chan-3", "channel", "triage"),
                    ),
            ).toHomeMessageOpenRequest("srv-1", dir)
        val taskReq = assertIs<ConversationOpenResolution.Resolved>(taskResolution).request
        assertEquals(ConversationKind.Task, taskReq.conversationIdentity.kind)
        assertEquals(ChannelId("task-conv-1"), taskReq.conversationIdentity.channelId)
        assertEquals("m-task", taskReq.anchorMessageId)
    }
}
