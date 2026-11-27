package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.emptyMessagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFakes.RecordingDurablePageExtender
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.ScreenError
import build.raft.app.matches
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.settings.matches
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadReplyEmptyDetail
import build.raft.app.thread.ThreadReplyEmptyTitle
import build.raft.app.thread.ThreadReplyPageTitle
import build.raft.app.network.sync.ThreadDurableEnterTicket
import build.raft.app.network.sync.ThreadDurableRequestStamp
import build.raft.app.network.sync.ThreadDurableEnteredPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

class SharedThreadStoreReplyOriginAndPagingTest {
    @Test
    fun createOrOpenReplyThreadFetchesReplyChannelAndPreservesOrigin() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("reply body", id = "reply-1", seq = 11, channelId = "thread-m1"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val result = dataSource.createOrOpenReplyThread(base, parent)

        val payload = (result as DataResult.Content).data
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals(ThreadReplyPageTitle, payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("reply body", payload.messages.single().body)
        assertEquals(listOf("channel-1" to "m1"), api.createdThreads)
        assertEquals(listOf("thread-m1"), api.channelIds.takeLast(1))
    }
    @Test
    fun createOrOpenReplyThreadKeepsEmptyPanelForFirstReply() = runTest {
        val api = FakeThreadApi(bodyText = emptyMessagesResponse())
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val result = dataSource.createOrOpenReplyThread(base, parent)

        val payload = (result as DataResult.Content).data
        assertEquals("empty", payload.status)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("loaded", payload.originStatus)
        assertEquals(emptyList(), payload.messages)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals("thread-m1", payload.resolveThreadComposer().streamChannelId)
        assertEquals("Reply in thread", payload.resolveThreadComposer().placeholder)
        assertEquals(listOf("channel-1" to "m1"), api.createdThreads)
        assertEquals(listOf("thread-m1"), api.channelIds.takeLast(1))
    }
    @Test
    fun fetchThreadPayloadPreservesReplyOriginWhenRepositoryReturnsParentAsMessage() = runTest {
        val parent = contentPayload("parent body").messages.single()
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(threadId = "thread-m1", id = "m1", seq = 10, body = "parent body"),
                                    message(threadId = "thread-m1", id = "reply-1", seq = 11, body = "reply body")
                                ),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originMessage = parent,
                originParentMessageId = "m1",
                originStatus = "loaded"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals("loaded", payload.originStatus)
    }
    @Test
    fun loadOlderMessagesMergesBeforeVisiblePayloadAndCachesInMemory() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10),
                olderBodyText = messagesResponse("older body", id = "m5", seq = 5, historyLimited = false)
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.LoadOlder)

        val payload = store.state.value.payload
        assertEquals(listOf("older body", "current body"), payload.messages.map { it.body })
        assertEquals(listOf(10L), api.beforeSeqs)
        assertEquals(false, payload.canLoadMoreBefore)
        assertEquals("idle", payload.olderMessagesStatus)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        assertEquals("older body", dataSource.cachedPayload("channel-1")?.messages?.first()?.body)
        scope.cancel()
    }

    @Test
    fun olderAndNewerPagingReadTheDurableHeadEpochBeforeTheirRequests() = runTest {
        // Message storage Phase 3b: the epoch handed to the extender is the one read BEFORE each page request. The
        // fake epoch moves with every paging request, so a read after the fetch would hand over 1 and 2.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository =
            FakeConversationRepository(
                olderResult =
                    DataResult.Content(
                        threadSnapshot(body = "older repo body", id = "m5", seq = 5).copy(rawMessagePage = durableRawPage("m5", limit = 30))
                    ),
                newerResult =
                    DataResult.Content(
                        threadSnapshot(body = "newer repo body", id = "m12", seq = 12).copy(rawMessagePage = durableRawPage("m12", limit = 30))
                    )
            )
        val extender = RecordingDurablePageExtender()
        extender.headEpochProvider = { (repository.olderRequests.size + repository.newerRequests.size).toLong() }
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                        durablePageExtender = extender
                    ),
                scope = scope
            )
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))

        store.accept(SharedThreadIntent.LoadOlder)
        store.accept(SharedThreadIntent.LoadNewer)

        assertEquals(2, extender.calls.size)
        assertEquals(listOf<Long?>(0L, 1L), extender.requestHeadEpochs)
        scope.cancel()
    }

    @Test
    fun aChannelEntryWritesItsOwnPageThroughUnderTheTicketTakenBeforeTheRequest() = runTest {
        // Message storage Phase 2b: the entry's own request is the durable write — ticket first, then the request, then
        // the page it got. Twin: the same entry whose request fails releases the ticket and writes nothing.
        suspend fun eventsFor(fail: Boolean): List<String> {
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + scopeJob)
            val events = mutableListOf<String>()
            val loader =
                object : ThreadEnteredScopeSnapshotLoader {
                    override suspend fun beginEnter(
                        partition: DurablePartitionKey,
                        channelId: String,
                        replyParent: ThreadDurableReplyParent?,
                        registerBaseline: Boolean,
                        stamp: ThreadDurableRequestStamp?
                    ): ThreadDurableEnterTicket {
                        events += "begin:$channelId:baseline=$registerBaseline"
                        return ThreadDurableEnterTicket(partition, channelId, replyParent, 0L, 0L, baseline = null)
                    }

                    override suspend fun persistEnteredPage(
                        ticket: ThreadDurableEnterTicket,
                        page: ThreadDurableEnteredPage,
                        stillCurrent: () -> Boolean
                    ) {
                        events += "persist:${page.channelId}:limit=${page.requestedLimit}:rows=${page.rawMessages.size}:current=${stillCurrent()}"
                    }

                    override suspend fun abandonEnter(ticket: ThreadDurableEnterTicket) {
                        events += "abandon:${ticket.channelId}"
                    }
                }
            val repository =
                FakeConversationRepository(
                    threadResultProvider = { _, _, limit ->
                        events += "fetch"
                        if (fail) {
                            DataResult.Error(ScreenError(code = "thread_network", retryable = true))
                        } else {
                            DataResult.Content(threadSnapshot().copy(rawMessagePage = durableRawPage("m5", limit = limit)))
                        }
                    }
                )
            val store =
                SharedThreadStore(
                    dataSource =
                        testSharedThreadDataSource(
                            storeScope = backgroundScope,
                            networkApi = FakeThreadApi(),
                            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                            conversationRepository = repository,
                            enteredScopeSnapshotLoader = loader
                        ),
                    scope = scope
                )
            store.accept(SharedThreadIntent.Start(basePayload()))
            withTimeout(5_000L) { while (events.none { it.startsWith("persist") || it.startsWith("abandon") }) delay(10) }
            repeat(20) { yield() }
            scopeJob.cancelAndJoin()
            return events.toList()
        }

        val written = eventsFor(fail = false)
        assertEquals("begin:channel-1:baseline=true", written.first(), written.toString())
        assertEquals("fetch", written[1], written.toString())
        assertTrue(written.drop(2).single().matches(Regex("persist:channel-1:limit=\\d+:rows=1:current=true")), written.toString())
        assertEquals(listOf("begin:channel-1:baseline=true", "fetch", "abandon:channel-1"), eventsFor(fail = true))
    }

    private fun durableRawPage(id: String, limit: Int): RawMessagePageWitness =
        RawMessagePageWitness(
            RawMessagePage(
                messages = Json.parseToJsonElement("""[{"id":"$id"}]""") as JsonArray,
                requestedLimit = limit
            )
        )
}
