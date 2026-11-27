package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.replySummaries
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.directMessagePayload
import build.raft.app.SharedThreadStoreTestFixtures.replyHintRepository
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.unresolvedReplyPayload
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.ThreadRepliesSyncHost
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadNewerPageLoadOutcome
import build.raft.app.thread.key
import build.raft.app.thread.threadHeaderDisplayModelFromDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class SharedThreadStoreFetchAndSummaryTest {
    @Test
    fun focusedReplyContextRestoresOriginFromCachedParentMessage() = runTest {
        val repository =
            replyHintRepository().also { fake ->
                fake.messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = emptyList(),
                            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
                        )
                    )
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
        )
        val firstOpen = dataSource.fetchThreadPayload(unresolvedReplyPayload(parentMessageId = "m1"))
        val firstPayload = (firstOpen as? DataResult.Content)?.data

        assertNotNull(firstPayload)
        assertEquals("parent body", firstPayload.originMessage?.body)

        repository.messageContextResult =
            DataResult.Content(
                threadSnapshot(
                    threadId = "thread-m1",
                    messages =
                        listOf(
                            message(
                                threadId = "thread-m1",
                                channelId = "thread-m1",
                                id = "reply-1",
                                seq = 2,
                                body = "focused reply"
                            )
                        ),
                    parentMessage = null,
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

        val secondOpen =
            dataSource.fetchThreadPayload(
                replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                    .copy(focusedMessageId = "reply-1")
            )
        val secondPayload = (secondOpen as? DataResult.Content)?.data

        assertNotNull(secondPayload)
        assertEquals("loaded", secondPayload.originStatus)
        assertEquals("m1", secondPayload.originMessage?.id)
        assertEquals("parent body", secondPayload.originMessage?.body)
        assertEquals(listOf("focused reply"), secondPayload.messages.map { it.body })
    }
    @Test
    fun fetchThreadPayloadWithoutSessionReturnsAuthRequiredNoPermission() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        val result = dataSource.fetchThreadPayload(basePayload())
        val blocker = (result as DataResult.NoPermission).blocker

        assertEquals("auth_required", blocker.code)
        assertEquals("auth_session", blocker.requiredCapability)
        assertEquals(0, api.channelMessageCalls)
    }
    @Test
    fun openMessageContextKeepsExplicitDirectMessageParentSurfaceWhenSnapshotLooksThread() = runTest {
        val timelineMessage =
            message(
                threadId = "dm-1",
                id = "parent-1",
                seq = 12,
                body = "Parent DM message"
            )
        val parentMessage =
            timelineMessage.copy(
                threadChannelId = ChannelId("reply-thread-1"),
                replyCount = 3,
                unreadReplyCount = 1
            )
        val repository =
            FakeConversationRepository(
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "reply-thread-1",
                            messages = listOf(timelineMessage),
                            parentMessage = parentMessage,
                            conversation =
                                ConversationSummary(
                                    threadId = ThreadId("reply-thread-1"),
                                    channelId = ChannelId("dm-1"),
                                    kind = ConversationKind.Thread,
                                    name = "Thread",
                                    subtitle = "#Cindy",
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("dm-1"),
                                            parentMessageId = MessageId("parent-1"),
                                            streamChannelId = ChannelId("reply-thread-1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val parentRoute =
            directMessagePayload().copy(
                title = "Cindy",
                dmPeerName = "Cindy",
                dmPeerAvatarUrl = "pixel:cindy",
                dmPeerAvatarLabel = "CI",
                focusedMessageId = "parent-1"
            )

        val payload = (dataSource.openMessageContext("parent-1", parentRoute) as DataResult.Content).data

        assertEquals(listOf(MessageId("parent-1")), repository.getMessageContextRequests)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        val headerDisplay =
            payload.threadHeaderDisplayModelFromDirectory(
                ConversationSummary(
                    threadId = ThreadId("dm-1"),
                    channelId = ChannelId("dm-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "Cindy"
                ).toConversationHeaderProjection()
            )

        assertEquals("Thread", payload.title)
        assertEquals("Cindy", headerDisplay.title)
        assertEquals(null, headerDisplay.dmPeerAvatarWireValue)
        assertEquals(null, payload.subtitle)
        assertEquals("Thread", payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals("TH", payload.dmPeerAvatarLabel)
        assertEquals("reply-thread-1", payload.messages.single().threadChannelId)
        assertEquals(3, payload.messages.single().replyCount)
        assertEquals(1, payload.messages.single().unreadReplyCount)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.resolveThreadComposer().surfaceKind)
    }
    @Test
    fun secondaryRefreshBackfillsFollowedSummariesWithoutBlockingInitialFetch() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "initial body",
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m1",
                                        seq = 10,
                                        body = "initial body",
                                        threadChannelId = "thread-m1",
                                        replyCount = 0
                                    )
                                )
                        )
                    ),
                threadReplySummariesResult =
                    DataResult.Content(
                        ThreadReplySummariesSnapshot(
                            threads =
                                listOf(
                                    InboxItem(
                                        key = "thread:m1",
                                        kind = InboxItemKind.Thread,
                                        channelId = ChannelId("thread-m1"),
                                        channelName = "reply thread",
                                        threadChannelId = ChannelId("thread-m1"),
                                        parentChannelId = ChannelId("channel-1"),
                                        parentMessageId = MessageId("m1"),
                                        replyCount = 7,
                                        unreadCount = 2,
                                        firstUnreadMessageId = MessageId("reply-6"),
                                        latestReplies =
                                            listOf(
                                                ReplyPreview(
                                                    messageId = "reply-6",
                                                    seq = 6,
                                                    content = "first unread",
                                                    senderId = "u2",
                                                    senderType = "human",
                                                    senderName = "cindy",
                                                    senderAvatarUrl = null,
                                                    createdAt = "2026-05-19T16:06:00Z",
                                                    senderDisplayName = "Cindy",
                                                )
                                            )
                                    )
                                ),
                            followedThreadKeys = setOf("thread:m1")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        delay(1L)

        assertEquals(listOf(false), repository.getThreadIncludeFollowedSummaries)
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        val message = store.state.value.payload.messages.single()
        assertEquals("initial body", message.body)
        assertEquals(7, message.replyCount)
        assertEquals(2, message.unreadReplyCount)
        assertEquals("reply-6", message.firstUnreadReplyMessageId)
        assertEquals(listOf("reply-6"), message.inlineReplies?.replies?.map { it.messageId })
        assertEquals("Cindy", message.inlineReplies?.replies?.single()?.senderDisplayName)
        assertTrue(message.isThreadFollowed)
        assertEquals(0, api.channelMessageCalls)
        scope.cancel()
    }
    @Test
    fun delayedSecondaryProjectionDoesNotReplaceCommittedNewerPage() = runTest {
        val initialMessages =
            (1L..16L).map { seq ->
                message(
                    id = "m$seq",
                    seq = seq,
                    body = "message $seq",
                    threadChannelId = if (seq == 1L) "thread-m1" else null,
                )
            }
        val newerMessages =
            (17L..46L).map { seq ->
                message(id = "m$seq", seq = seq, body = "message $seq")
            }
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(messages = initialMessages)),
                threadReplySummariesResult = replySummaries(replyCount = 7),
                newerResult = DataResult.Content(threadSnapshot(messages = newerMessages)),
            )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        lateinit var store: SharedThreadStore
        var projectionCommitCount = 0
        var maxSeqAfterNewerCommit: Long? = null
        val repliesSyncHost =
            ThreadRepliesSyncHost(
                beforeProjectionCommit = {
                    projectionCommitCount += 1
                    if (projectionCommitCount == 2) {
                        // Not a wait: the hook is synchronous by contract, and this re-enters the store
                        // INSIDE the projection commit. The store runs on the unconfined test
                        // dispatcher, so the nested load completes on this thread without a clock.
                        runBlocking {
                            assertTrue(store.loadNewer() is ThreadNewerPageLoadOutcome.Applied)
                            maxSeqAfterNewerCommit = store.state.value.payload.messages.mapNotNull { it.seq }.maxOrNull()
                            store.accept(SharedThreadIntent.ApplyMessageSaved("m1", saved = true))
                        }
                    }
                },
            )
        store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
                threadRepliesSyncHost = repliesSyncHost,
            )

        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))
        store.accept(
            SharedThreadIntent.SetPagingAvailability(
                canLoadMoreBefore = false,
                canLoadMoreAfter = true,
            ),
        )
        store.accept(SharedThreadIntent.FirstContentReady)

        assertEquals(46L, maxSeqAfterNewerCommit)
        assertEquals(2, projectionCommitCount)
        assertEquals(46L, store.state.value.payload.messages.mapNotNull { it.seq }.maxOrNull())
        assertEquals((1L..46L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals(listOf(ThreadId("channel-1") to 16L), repository.newerRequests)
        assertEquals(7, store.state.value.payload.messages.first { it.id == "m1" }.replyCount)
        assertTrue(store.state.value.payload.messages.first { it.id == "m1" }.isSaved)
        scope.cancel()
    }
    @Test
    fun lateSummaryFromPreviousRouteCannotMutateCurrentCanonicalMessages() = runTest {
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("channel-1") to
                            DataResult.Content(threadSnapshot(threadId = "channel-1", body = "route one")),
                        ThreadId("channel-2") to
                            DataResult.Content(threadSnapshot(threadId = "channel-2", body = "route two")),
                    ),
                threadReplySummariesResult = replySummaries(replyCount = 7),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        repository.threadReplySummariesStarted?.await()
        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    instanceId = "shared-shell-thread-channel-2",
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                ),
            ),
        )
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()

        val current = store.state.value.payload
        assertEquals("channel-2", current.sourceThreadId)
        assertEquals(listOf("route two"), current.messages.map { it.body })
        assertEquals(0, current.messages.single().replyCount)
        scope.cancel()
    }
    @Test
    fun earlySummaryResultStashesUntilFirstContentReady() = runTest {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult = replySummaries(replyCount = 7),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        repository.threadReplySummariesStarted?.await()
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()
        assertEquals(0, store.state.value.payload.messages.single().replyCount)

        store.accept(SharedThreadIntent.FirstContentReady)
        yield()
        delay(1L)
        assertEquals(7, store.state.value.payload.messages.single().replyCount)
        scope.cancel()
    }
    @Test
    fun summarySnapshotDoesNotOverwriteNewerRealtimeFact() = runTest {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult = replySummaries(replyCount = 15),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        repository.threadReplySummariesStarted?.await()
        // A realtime reply-count fact lands while the summaries request is in
        // flight; its newer observedAt must survive the older server snapshot.
        dataSource.recordReplyCountRealtimeFact(
            threadId = "channel-1",
            parentMessageId = "m1",
            fact =
                SharedThreadReplyCountFact(
                    threadChannelId = "thread-m1",
                    replyCount = 17,
                    observedAt = TimeSource.Monotonic.markNow(),
                ),
        )
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()
        delay(1L)
        assertEquals(17, store.state.value.payload.messages.single().replyCount)
        scope.cancel()
    }
}
