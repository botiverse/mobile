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
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.replyResolutionSession
import build.raft.app.SharedThreadStoreTestFixtures.testReplyCacheIdentityAuthority
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.ThreadDurableEnterTicket
import build.raft.app.network.sync.ThreadDurableRequestStamp
import build.raft.app.network.sync.ThreadDurableEnteredPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    fun replyThreadSnapshotWithOnlyOriginUsesReplyEmptyState() = runTest {
        val parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = emptyList(),
                            parentMessage = parentMessage,
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    name = "thread-m1",
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
                conversationRepository = repository,
                cacheEnvironmentIdProvider = { "https://prod.example.test" }
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val result = dataSource.createOrOpenReplyThread(base, parent)

        val payload = (result as DataResult.Content).data
        assertEquals("empty", payload.status)
        assertEquals(emptyList(), payload.messages)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
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
    fun fetchThreadPayloadHydratesOriginWithoutBlockingReplyMessagesWhenRepliesExcludeParent() = runTest {
        val parent = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 11, body = "reply body")),
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
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(parent),
                            conversation = conversationSummary("channel-1")
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
                originParentMessageId = "m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals(listOf(MessageId("m1")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(ChannelId("channel-1")), repository.getMessageContextChannelIdRequests)
    }
    @Test
    fun fetchThreadPayloadUsesParentContextRepositoryForReplyOrigin() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        val localParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "local parent body")
        localStore.writeThread(
            threadSnapshot(
                threadId = "channel-1",
                messages = listOf(localParent),
                conversation = conversationSummary("channel-1")
            )
        )
        val networkParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "network parent body")
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 11, body = "reply body")),
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
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(networkParent),
                            conversation = conversationSummary("channel-1")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("network parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals(listOf(MessageId("m1")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(ChannelId("channel-1")), repository.getMessageContextChannelIdRequests)
    }
    @Test
    fun fetchThreadPayloadHydratesReplyOriginFromParentContextWhenNoReplyMessagesCanRender() = runTest {
        val parent = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = emptyList(),
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
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(parent),
                            conversation = conversationSummary("channel-1")
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
                originParentMessageId = "m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(emptyList(), payload.messages.map { it.body })
        assertEquals(listOf(MessageId("m1")), repository.getMessageContextRequests)
    }
    @Test
    fun fetchThreadPayloadHydratesReplyOriginInParallelWhenRepliesCanRender() = runTest {
        val sparseParent = message(threadId = "thread-m1", id = "m1", seq = 10, body = "")
        val canonicalParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    sparseParent,
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
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(canonicalParent),
                            conversation = conversationSummary("channel-1")
                        )
                    )
            )
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        dataSource.readSession()
        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals(listOf(MessageId("m1")), repository.getMessageContextRequests)
        assertEquals(
            "parent body",
            messageStore.getScoped(
                "https://prod.example.test\u001fu1\u001fserver-1\u001f1",
                ChannelId("channel-1"),
                MessageId("m1")
            )?.body
        )
    }
    @Test
    fun retiredOwnerReplyOriginMayWarmOldScopeButCannotReturnProjectableParent() = runTest {
        val ownerASession = replyResolutionSession("account-a")
        val ownerBSession = replyResolutionSession("account-b")
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "account-a",
                serverId = "server-a",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val parent = message(threadId = "channel-1", id = "m1", seq = 1, body = "owner-a parent")
        val repository =
            FakeConversationRepository(
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(parent),
                            parentMessage = parent,
                            conversation = conversationSummary("channel-1")
                        )
                    )
            )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.messageContextStartedById[MessageId("m1")] = started
        repository.releaseMessageContextById[MessageId("m1")] = release
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(ownerASession),
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = { identity }
            )
        val ownerA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val route =
            replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                .copy(
                    parentChannelId = "channel-1",
                    originMessage = null,
                    originStatus = "loading"
                )
        val resolution = async { dataSource.resolveReplyOrigin(route, expectedProvenance = ownerA) }
        started.await()

        identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "account-b",
                serverId = "server-a",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        dataSource.captureReplyCacheProducerContext()
        release.complete(Unit)

        val stale = assertIs<SharedThreadOriginResolution.Error>(resolution.await())
        assertEquals("thread_reply_cache_provenance_stale", stale.code)
        assertEquals(
            "owner-a parent",
            messageStore
                .getScoped(
                    requireNotNull(ownerA.messageStoreScopeId),
                    ChannelId("channel-1"),
                    MessageId("m1")
                )
                ?.body
        )
        val ownerB = requireNotNull(dataSource.currentReplyCacheProvenance())
        assertNull(
            messageStore.getScoped(
                requireNotNull(ownerB.messageStoreScopeId),
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
    }
    @Test
    fun fetchThreadPayloadReusesStoredReplyOriginMessageBeforeNetworkContext() = runTest {
        val canonicalParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "stored parent body")
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
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
                    ),
                messageContextResult =
                    DataResult.Error(ScreenError(code = "should_not_fetch_context", retryable = false))
            )
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        dataSource.readSession()
        messageStore.upsertScoped(
            "https://prod.example.test\u001fu1\u001fserver-1\u001f1",
            ChannelId("channel-1"),
            canonicalParent
        )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals("stored parent body", payload.originMessage?.body)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals(emptyList(), repository.getMessageContextRequests)
    }
    @Test
    fun createOrOpenReplyThreadUsesRepositoryMetadataForOriginAndMemoryCache() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        val repository = FakeConversationRepository(
            threadResult =
                DataResult.Content(
                    threadSnapshot(
                        threadId = "thread-m1",
                        body = "reply body",
                        id = "reply-1",
                        seq = 11,
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
                ),
            createThreadResult =
                DataResult.Content(
                    ThreadActionResult(
                        threadChannelId = ChannelId("thread-m1"),
                        changed = true,
                        parentMessageId = MessageId("m1"),
                        replyCount = 4
                    )
                )
        )
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                cacheEnvironmentIdProvider = { "https://prod.example.test" }
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val payload = (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data
        dataSource.cachePayloadForTest(payload)

        val cached = localStore.readThread(ThreadId("thread-m1"))
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.createOrOpenRequests)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("", payload.title)
        assertEquals("#general", payload.subtitle)
        assertEquals(4, payload.originMessage?.replyCount)
        assertEquals("thread-m1", payload.originMessage?.threadChannelId)
        assertNull(cached)
        val cachedPayload = dataSource.cachedPayload("thread-m1")
        assertEquals(4, cachedPayload?.originMessage?.replyCount)
        assertEquals("thread-m1", cachedPayload?.originMessage?.threadChannelId)
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
    fun threadPageSizeDefaultsToThirtyAndReevaluatesRemoteValuePerRequest() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10),
                olderBodyText = messagesResponse("older body", id = "m5", seq = 5, historyLimited = false)
            )
        var remotePageSize: Int? = null
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                threadPageSizeProvider = { remotePageSize }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        remotePageSize = 20
        store.accept(SharedThreadIntent.LoadOlder)

        assertEquals(listOf(30, 20), api.channelMessageLimits)
        scope.cancel()
    }
    @Test
    fun repositoryThreadPagingUsesTheSameResolvedRemotePageSize() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository = FakeConversationRepository()
        var remotePageSize: Int? = null
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                threadPageSizeProvider = { remotePageSize }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        remotePageSize = 50
        store.accept(
            SharedThreadIntent.SetPagingAvailability(
                canLoadMoreBefore = true,
                canLoadMoreAfter = false
            )
        )
        store.accept(SharedThreadIntent.LoadOlder)

        assertEquals(listOf(30), repository.getThreadLimits)
        assertEquals(listOf(50), repository.olderLimits)
        assertEquals(
            listOf(
                ThreadId("channel-1") to listOf(MessageId("m1")),
                ThreadId("channel-1") to listOf(MessageId("m5"), MessageId("m1"))
            ),
            repository.getThreadReplySummariesRequests
        )
        scope.cancel()
    }
    @Test
    fun repositoryOlderAndNewerPagesAreOfferedToTheDurableWindowExtender() = runTest {
        // Task #168: an applied paging page is handed to the extender with the route's channel, the
        // fetching identity's V2 partition, the paging anchor, and the raw server page.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val rawOlder = durableRawPage("m5", limit = 30)
        val rawNewer = durableRawPage("m12", limit = 30)
        val repository =
            FakeConversationRepository(
                olderResult =
                    DataResult.Content(
                        threadSnapshot(body = "older repo body", id = "m5", seq = 5).copy(rawMessagePage = rawOlder)
                    ),
                newerResult =
                    DataResult.Content(
                        threadSnapshot(body = "newer repo body", id = "m12", seq = 12).copy(rawMessagePage = rawNewer)
                    )
            )
        val extender = RecordingDurablePageExtender()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                durablePageExtender = extender
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val partition =
            assertNotNull(
                dataSource.currentReplyCacheProvenance()
                    ?.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
            )
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))

        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(listOf("older repo body", "repository body"), store.state.value.payload.messages.map { it.body })
        assertEquals(
            listOf(RecordingDurablePageExtender.Call(partition, "channel-1", ThreadDurablePageDirection.Older, 10L, listOf("m5"), 30, true)),
            extender.calls
        )

        store.accept(SharedThreadIntent.LoadNewer)
        assertEquals(
            RecordingDurablePageExtender.Call(partition, "channel-1", ThreadDurablePageDirection.Newer, 10L, listOf("m12"), 30, true),
            extender.calls.last()
        )
        assertEquals(2, extender.calls.size)
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
    fun pagesWithoutARawPageOrOnReplyRoutesAreNeverOfferedToTheExtender() = runTest {
        // Twin of the test above: identical paging, but the repository page carries no raw page.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val extender = RecordingDurablePageExtender()
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = FakeConversationRepository(),
                        durablePageExtender = extender
                    ),
                scope = scope
            )
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))
        store.accept(SharedThreadIntent.LoadOlder)
        store.accept(SharedThreadIntent.LoadNewer)
        assertEquals(listOf("older repo body", "repository body", "newer repo body"), store.state.value.payload.messages.map { it.body })
        assertTrue(extender.calls.isEmpty(), extender.calls.toString())
        scope.cancel()

        // Admission: a reply-thread route without its typed parent is not admitted even with a raw
        // page (with its parent it is offered as a thread scope: SharedThreadStoreReplyThreadDurableTest).
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                durablePageExtender = extender
            )
        val context = assertNotNull(dataSource.captureReplyCacheProducerContext())
        val replyRoute =
            basePayload().copy(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-parent-1",
                streamChannelId = "thread-parent-1",
                parentChannelId = "channel-1",
                parentMessageId = "parent-1",
                originParentMessageId = "parent-1"
            ).resolveThreadRouteIdentity()
        val raw = assertNotNull(durableRawPage("m5", limit = 30).page)
        assertFalse(
            dataSource.extendDurableWindowIfCurrent(replyRoute, context, ThreadDurablePageDirection.Older, 10L, raw)
        )
        assertTrue(
            dataSource.extendDurableWindowIfCurrent(
                basePayload().resolveThreadRouteIdentity(),
                context,
                ThreadDurablePageDirection.Older,
                10L,
                raw
            ),
            "positive control: the channel route with the same page is admitted"
        )
        assertEquals(1, extender.calls.size)
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

    @Test
    fun replyRouteOpenedBeforeItsStreamIsKnownWritesThePageItsRequestResolved() = runTest {
        // Task #168 step 3 / message storage Phase 2b: `start()` cannot name the thread scope of an unresolved reply
        // route, so its request takes no ticket (no realtime baseline); the page the request resolved is still written
        // through, under a ticket taken after it. A route with its stream known takes its ticket before the request.
        data class Run(val begins: List<Triple<String, ThreadDurableReplyParent?, Boolean>>, val persisted: List<String>)
        suspend fun runFor(route: RaftThreadScreenPayload): Run {
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + scopeJob)
            val begins = mutableListOf<Triple<String, ThreadDurableReplyParent?, Boolean>>()
            val persisted = mutableListOf<String>()
            val loader =
                object : ThreadEnteredScopeSnapshotLoader {
                    override suspend fun beginEnter(
                        partition: DurablePartitionKey,
                        channelId: String,
                        replyParent: ThreadDurableReplyParent?,
                        registerBaseline: Boolean,
                        stamp: ThreadDurableRequestStamp?
                    ): ThreadDurableEnterTicket {
                        begins += Triple(channelId, replyParent, registerBaseline)
                        return ThreadDurableEnterTicket(partition, channelId, replyParent, 0L, 0L, baseline = null)
                    }

                    override suspend fun persistEnteredPage(
                        ticket: ThreadDurableEnterTicket,
                        page: ThreadDurableEnteredPage,
                        stillCurrent: () -> Boolean
                    ) {
                        persisted += page.channelId
                    }
                }
            val snapshot =
                threadSnapshot(
                    threadId = "thread-m1",
                    conversation =
                        conversationSummary("thread-m1").copy(
                            kind = ConversationKind.Thread,
                            channelId = ChannelId("channel-1"),
                            thread =
                                ThreadConversation(
                                    parentChannelId = ChannelId("channel-1"),
                                    parentMessageId = MessageId("m1"),
                                    streamChannelId = ChannelId("thread-m1")
                                )
                        )
                ).copy(rawMessagePage = durableRawPage("r1", limit = 30))
            val repository = FakeConversationRepository(threadResult = DataResult.Content(snapshot))
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
            store.accept(SharedThreadIntent.Start(route))
            withTimeout(5_000L) { while (persisted.isEmpty()) delay(10) }
            repeat(20) { yield() }
            scopeJob.cancelAndJoin()
            return Run(begins.toList(), persisted.toList())
        }

        val parent = ThreadDurableReplyParent(parentChannelId = "channel-1", rootMessageId = "m1")
        val resolvedRoute = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val unresolvedRoute = resolvedRoute.copy(sourceThreadId = null, streamChannelId = null)
        assertEquals(null, unresolvedRoute.resolveThreadRouteIdentity().timelineChannelId)
        assertEquals(Run(listOf(Triple("thread-m1", parent, false)), listOf("thread-m1")), runFor(unresolvedRoute))
        // Twin: the identical route with its stream already known takes its ticket (with the baseline) before the
        // request, exactly once.
        assertEquals(Run(listOf(Triple("thread-m1", parent, true)), listOf("thread-m1")), runFor(resolvedRoute))
    }
    private fun durableRawPage(id: String, limit: Int): RawMessagePageWitness =
        RawMessagePageWitness(
            RawMessagePage(
                messages = Json.parseToJsonElement("""[{"id":"$id"}]""") as JsonArray,
                requestedLimit = limit
            )
        )
}
