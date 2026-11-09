package build.raft.app.data

import build.raft.app.model.ActivityChanged
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftWireTransferProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * botiverse/slock #13126 (web parity): a `realtime_fallback` read of `GET /channels/inbox` names
 * the realtime event behind it (`X-Raft-Fetch-Trigger`, shared `INBOX_REFRESH_TRIGGERS`) and how
 * many asks were folded into it (`X-Raft-Fetch-Coalesced`, >= 1, the read itself included). A
 * throttled or joined read keeps the FIRST trigger and counts the rest. Any other reason carries
 * neither header. Observability only: which reads go out, and when, is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxFetchTriggerHeaderTest {

    // --- The wire contract. ---

    @Test
    fun wireValuesAreTheSharedTriggersThenOther() {
        assertEquals(
            listOf(
                "message_new", "message_echo", "thread_reply", "thread_reply_rebaseline",
                "activity_changed", "dm_new", "read_persisted", "catch_up_retry", "other"
            ),
            InboxRefreshTrigger.entries.map { it.wireValue }
        )
        assertEquals("X-Raft-Fetch-Trigger", RaftFetchTriggerHeader)
        assertEquals("X-Raft-Fetch-Coalesced", RaftFetchCoalescedHeader)
    }

    @Test
    fun onlyARealtimeFallbackReadCarriesTheTrigger() {
        val trigger = InboxRefreshTriggerCount(InboxRefreshTrigger.DmNew, coalesced = 3)
        assertEquals(
            fallback("dm_new", 3),
            inboxFetchReasonHeaders(InboxFetchReasonContext(InboxFetchReason.RealtimeFallback, trigger))
        )
        InboxFetchReason.entries.filter { it != InboxFetchReason.RealtimeFallback }.forEach { reason ->
            assertEquals(
                mapOf("X-Raft-Fetch-Reason" to reason.wireValue),
                inboxFetchReasonHeaders(InboxFetchReasonContext(reason, trigger)),
                reason.name
            )
        }
        assertEquals(
            mapOf("X-Raft-Fetch-Reason" to "realtime_fallback"),
            inboxFetchReasonHeaders(InboxFetchReasonContext(InboxFetchReason.RealtimeFallback))
        )
        assertEquals(
            fallback("message_new", RaftFetchCoalescedCap),
            inboxFetchReasonHeaders(
                InboxFetchReasonContext(
                    InboxFetchReason.RealtimeFallback,
                    InboxRefreshTriggerCount(InboxRefreshTrigger.MessageNew, coalesced = 5_000)
                )
            ),
            "the count never exceeds the server's cap"
        )
    }

    @Test
    fun foldingKeepsTheFirstTriggerAndAddsTheCounts() {
        val first = InboxRefreshTriggerCount(InboxRefreshTrigger.ThreadReply)
        val folded =
            first.foldWith(InboxRefreshTrigger.MessageNew)
                .foldWith(InboxRefreshTriggerCount(InboxRefreshTrigger.DmNew, coalesced = 2))
        assertEquals(InboxRefreshTriggerCount(InboxRefreshTrigger.ThreadReply, coalesced = 4), folded)
        val none: InboxRefreshTriggerCount? = null
        assertEquals(first, first.foldWith(none))
        assertEquals(first, none.foldWith(first))
        assertNull(none.foldWith(none))
    }

    @Test
    fun realtimeEventsMapToTheSharedTriggers() {
        val msg = message("m-1", channelId = "general")
        mapOf<RealtimeEvent, InboxRefreshTrigger>(
            MessageNew(msg) to InboxRefreshTrigger.MessageNew,
            threadUpdated() to InboxRefreshTrigger.ThreadReply,
            ActivityChanged(ServerId("server-1"), "object") to InboxRefreshTrigger.ActivityChanged,
            DirectMessageCreated(ChannelId("dm-1")) to InboxRefreshTrigger.DmNew,
            // App events no shared value names; the sync stream's Activity refetch is not `activity:changed`.
            ActivityChanged(ServerId("server-1"), "sync_changes") to InboxRefreshTrigger.Other,
            MessageUpdated(msg) to InboxRefreshTrigger.Other,
            PerReceiverReadStateChanged(serverId = ServerId("server-1"), scopes = emptyList()) to InboxRefreshTrigger.Other,
        ).forEach { (event, trigger) -> assertEquals(trigger, event.inboxRefreshTrigger(), event::class.simpleName) }

        assertEquals(
            InboxRefreshTrigger.DmNew,
            SharedActivityIntent.ApplyRealtimeEvent(DirectMessageCreated(ChannelId("dm-1"))).summaryInvalidationTrigger()
        )
        assertEquals(
            InboxRefreshTrigger.MessageNew,
            SharedActivityIntent.ApplyRealtimeSummaryEvent(MessageNew(msg), activeConversationId = null).summaryInvalidationTrigger()
        )
        assertNull(SharedActivityIntent.MarkAllRead.summaryInvalidationTrigger(), "the person's own action")
    }

    @Test
    fun theTriggerHeadersNeverSplitASharedInFlightGet() = runTest {
        val executor = HeldExecutor()
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrlProvider = { SharedApiEnvironmentStore.Production.apiBaseUrl },
                executor = executor,
                inFlightGets = RaftInFlightGetDedupe(nowMillis = { testScheduler.currentTime }),
            )
        fun read(trigger: String, coalesced: Int) =
            async {
                client.requestWithHeaders(
                    DataRequestPriority.Foreground,
                    "channels/inbox",
                    headers = fallback(trigger, coalesced),
                    query = listOf("limit" to "1")
                )
            }

        val first = read("message_new", 1)
        runCurrent()
        val second = read("dm_new", 4)
        runCurrent()
        assertEquals(listOf(fallback("message_new", 1)), executor.headers, "joined, as without the label")

        executor.release.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, executor.headers.size)
    }

    // --- The Activity list (SharedActivityStore): each realtime path tags its re-read. ---

    @Test
    fun eachActivityRealtimePathTagsItsReRead() = runTest {
        listOf<Pair<RealtimeEvent, String>>(
            // No seq: no catch-up target, so only the reconcile read.
            MessageNew(message("m-new", channelId = "general")) to "message_new",
            // The row already shows m-41: a repeat delivery (web `message_echo`).
            MessageNew(message("m-41", channelId = "general")) to "message_echo",
            threadUpdated() to "thread_reply",
            DirectMessageCreated(ChannelId("dm-1")) to "dm_new",
            ActivityChanged(ServerId("server-1"), "object") to "activity_changed",
        ).forEach { (event, trigger) ->
            val network = RecordingInboxNetwork(this)
            val store = loadedActivityStore(network)

            store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(event))
            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(listOf(fallback(trigger, 1)), network.headers.drop(1), event::class.simpleName)
        }
    }

    @Test
    fun theActivityCatchUpReReadIsTaggedCatchUpRetry() = runTest {
        val network = RecordingInboxNetwork(this)
        val store = loadedActivityStore(network)

        // Newer than the served row (seq 41): the answer is still behind, so the catch-up re-reads.
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("m-42", channelId = "general", seq = 42L))))
        runCurrent()
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(
            listOf(fallback("message_new", 1), fallback("catch_up_retry", 1)),
            network.headers.drop(1).take(2)
        )
    }

    @Test
    fun activityAsksInsideTheWindowFoldIntoOneReadNamedByTheFirst() = runTest {
        val network = RecordingInboxNetwork(this)
        val store = loadedActivityStore(network, settle = false)

        // 1 s after the initial read: inside its 2.5 s window, so one trailing read answers all three.
        advanceTimeBy(1_000L)
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(DirectMessageCreated(ChannelId("dm-1"))))
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("m-new", channelId = "general"))))
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(ActivityChanged(ServerId("server-1"), "object")))
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(
            listOf(mapOf("X-Raft-Fetch-Reason" to "initial"), fallback("dm_new", 3)),
            network.headers,
            "the initial read carries no trigger; the one trailing read names the first ask and counts all"
        )
    }

    @Test
    fun activityAsksMadeWhileAReadIsInFlightCountForTheNextRead() = runTest {
        val network = RecordingInboxNetwork(this, delayMillis = 1_000L)
        val store = loadedActivityStore(network)

        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(MessageNew(message("m-new", channelId = "general"))))
        runCurrent()
        advanceTimeBy(200L)
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(threadUpdated()))
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(DirectMessageCreated(ChannelId("dm-1"))))
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(listOf(fallback("message_new", 1), fallback("thread_reply", 2)), network.headers.drop(1))
    }

    @Test
    fun theReturnReadAfterBackgroundAsksIsAVisibilityReadWithoutATrigger() = runTest {
        val network = RecordingInboxNetwork(this)
        val foreground = MutableStateFlow(true)
        val store = loadedActivityStore(network, appForeground = foreground)

        foreground.value = false
        runCurrent()
        store.dispatch(SharedActivityIntent.ApplyRealtimeEvent(DirectMessageCreated(ChannelId("dm-1"))))
        advanceTimeBy(5_000L)
        foreground.value = true
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals(listOf(mapOf("X-Raft-Fetch-Reason" to "visibility")), network.headers.drop(1))
    }

    // --- The unread summary (ProcessActivityUnreadStore, the badge's `limit=1` read). ---

    @Test
    fun summaryAsksInsideTheWindowFoldIntoOneReadNamedByTheFirst() = runTest {
        val owner = ProcessActivityUnreadOwner("https://api.raft.build/api", "user-1", "server-1")
        val reads = mutableListOf<Map<String, String>>()
        val store = summaryStore(owner, reads)

        store.invalidate(owner, "realtime:ThreadUpdated", eventMarker = "a", realtimeOnly = true, trigger = InboxRefreshTrigger.ThreadReply)
        runCurrent()
        advanceTimeBy(500L)
        val repeated = Any()
        store.invalidate(owner, "realtime:MessageNew", eventMarker = repeated, realtimeOnly = true, trigger = InboxRefreshTrigger.MessageNew)
        // The same frame from a second subscriber: the same ask, dropped, not counted.
        store.invalidate(owner, "realtime:MessageNew", eventMarker = repeated, realtimeOnly = true, trigger = InboxRefreshTrigger.MessageNew)
        store.invalidate(owner, "realtime:DirectMessageCreated", eventMarker = "c", realtimeOnly = true, trigger = InboxRefreshTrigger.DmNew)
        // A summary invalidation from Activity without an event (the person's Done): `other`.
        store.submitInvalidation(ActivityUnreadSummaryInvalidation(accountId = "user-1", serverId = "server-1", source = "realtime"))
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(listOf(fallback("thread_reply", 1), fallback("message_new", 3)), reads)
    }

    @Test
    fun summaryRealtimeSinkTagsTheEvent() = runTest {
        val owner = ProcessActivityUnreadOwner("https://api.raft.build/api", "user-1", "server-1")
        val reads = mutableListOf<Map<String, String>>()
        val store = summaryStore(owner, reads)
        val sink =
            processActivityUnreadRealtimeSink(
                currentAuthContext = {
                    ProcessPresenceAuthContext(
                        environmentId = owner.environmentId,
                        accountId = owner.accountId,
                        authorityGeneration = 1L,
                        writeGeneration = 1L
                    )
                },
                store = store
            )

        sink(ProcessActivityUnreadAdmission(serverId = "server-1", principalId = "user-1", authGeneration = 1L), ActivityChanged(ServerId("server-1"), "object"))
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(listOf(fallback("activity_changed", 1)), reads)
    }

    @Test
    fun summaryReadsForAnotherReasonCarryNoTrigger() = runTest {
        val owner = ProcessActivityUnreadOwner("https://api.raft.build/api", "user-1", "server-1")
        val reads = mutableListOf<Map<String, String>>()
        val store = summaryStore(owner, reads)

        store.refresh(ServerId("server-1"), reason = "manual")
        runCurrent()
        advanceTimeBy(500L)
        // A realtime ask, then the person's read, folded into one round: its final reason is `manual`.
        store.invalidate(owner, "realtime:MessageNew", eventMarker = "a", realtimeOnly = true, trigger = InboxRefreshTrigger.MessageNew)
        store.invalidate(owner, "read_result:7")
        advanceTimeBy(20_000L)
        runCurrent()

        assertEquals(List(2) { mapOf("X-Raft-Fetch-Reason" to "manual") }, reads)
    }

    // --- Fixtures. ---

    private fun fallback(trigger: String, coalesced: Int): Map<String, String> =
        mapOf(
            "X-Raft-Fetch-Reason" to "realtime_fallback",
            "X-Raft-Fetch-Trigger" to trigger,
            "X-Raft-Fetch-Coalesced" to coalesced.toString(),
        )

    private fun threadUpdated(): ThreadUpdated =
        ThreadUpdated(
            parentMessageId = MessageId("parent-1"),
            threadChannelId = ChannelId("thread-1"),
            replyCount = 2,
            lastReplyAt = "2026-10-25T10:00:00Z",
            participantIds = emptyList()
        )

    private fun TestScope.loadedActivityStore(
        network: RecordingInboxNetwork,
        settle: Boolean = true,
        appForeground: MutableStateFlow<Boolean> = MutableStateFlow(true)
    ): SharedActivityStore {
        val session = InMemoryRaftAuthSessionStore(sampleSession())
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = RealInboxRepository(session, network),
                authStore = session,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                appForeground = appForeground
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        if (settle) {
            // Past the initial read and its throttle window, so the event's read goes out at once.
            advanceTimeBy(10_000L)
            runCurrent()
            check(network.headers == listOf(mapOf("X-Raft-Fetch-Reason" to "initial"))) { "headers=${network.headers}" }
        }
        return store
    }

    private fun TestScope.summaryStore(
        owner: ProcessActivityUnreadOwner,
        reads: MutableList<Map<String, String>>
    ): ProcessActivityUnreadStore =
        ProcessActivityUnreadStore(
            fetchSummary = { _, _ ->
                // The repository turns exactly this context into the request's headers.
                reads += currentInboxFetchReasonHeaders()
                DataResult.Content(ActivityUnreadSummarySnapshot(totalUnreadCount = 1, totalCount = 2))
            },
            ownerResolver = { serverId -> owner.takeIf { it.serverId == serverId } },
            scope = backgroundScope,
            throttleMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
            realtimeMinIntervalMillis = ActivityUnreadRealtimeMinIntervalMillis,
            nowMillis = { testScheduler.currentTime },
            appForeground = MutableStateFlow(true),
        )

    /** `GET channels/inbox` answering one channel row (`general`, latest `m-41`, seq 41); records each request's headers. */
    private class RecordingInboxNetwork(
        private val testScope: TestScope,
        private val delayMillis: Long = 0L
    ) : RaftNetworkClient {
        val headers = mutableListOf<Map<String, String>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse = requestWithHeaders(priority, path, emptyMap(), method, query, serverIdOverride, bodyText)

        override suspend fun requestWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            check(path == "channels/inbox" && method == "GET") { "unexpected $method $path" }
            this.headers += headers
            if (delayMillis > 0L) delay(delayMillis)
            return HomeNetworkResponse(
                200,
                """
                {"items":[{"kind":"channel","channelId":"general","channelName":"general","channelType":"channel",
                 "lastMessageId":"m-41","latestActivitySeq":"41","doneFrontierSeq":"41",
                 "firstUnreadMessageId":null,"firstMentionMessageId":null,
                 "lastMessageAt":"2026-10-25T09:50:00Z","lastMessagePreview":"hi",
                 "lastMessageSenderType":"user","lastMessageSenderId":"u-9","lastMessageSenderName":"Server",
                 "unreadCount":0,"hasMention":false}],"hasMore":false,"totalCount":1,"totalUnreadCount":0}
                """.trimIndent()
            )
        }
    }

    /** Holds every request until [release]; records the headers that reached the wire. */
    private class HeldExecutor : RaftHttpExecutor {
        val headers = mutableListOf<Map<String, String>>()
        val release = CompletableDeferred<Unit>()

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): RaftHttpExecutorResponse {
            this.headers += headers.filterKeys { it.startsWith("X-Raft-Fetch-") }
            release.await()
            return RaftHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((RaftWireTransferProgress) -> Unit)?
        ): RaftHttpExecutorResponse = error("unused")
    }
}
