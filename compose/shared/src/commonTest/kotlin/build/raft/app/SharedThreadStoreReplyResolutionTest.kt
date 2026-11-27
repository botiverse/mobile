package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.focusedReplySnapshot
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.activityReplyPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.focusedReplyRoute
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyResolutionSession
import build.raft.app.SharedThreadStoreTestFixtures.replyThreadConversation
import build.raft.app.SharedThreadStoreTestFixtures.testReplyCacheIdentityAuthority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.data.toHomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadMessageStore
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.threadHeaderDisplayTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class SharedThreadStoreReplyResolutionTest {
    @Test
    fun focusedReplyParentAbsenceAndFailureStayTypedWithoutReplacingTimeline() = runTest {
        val parentMessageId = MessageId("parent-1")
        val focusedMessageId = MessageId("reply-focus-1")
        val repository =
            FakeConversationRepository(
                messageContextResult = DataResult.Empty(EmptyReason.NoMessages),
                messageContextResultsById =
                    mapOf(
                        focusedMessageId to
                            DataResult.Content(
                                focusedReplySnapshot(
                                    threadId = "thread-parent-1",
                                    parentChannelId = "parent-channel-1",
                                    parentMessageId = parentMessageId.value,
                                    focusedMessageId = focusedMessageId.value
                                )
                            )
                    )
            )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedThreadIntent.Start(focusedReplyRoute()))
        val missing = withTimeout(10_000L) {
            store.state.map { it.payload }.first { it.originStatus == "missing" }
        }
        assertEquals("content", missing.status)
        assertEquals(listOf("reply-focus-1"), missing.messages.map { it.id })
        assertNull(missing.originErrorCode)

        repository.messageContextResult = DataResult.Error(ScreenError("thread_parent_timeout", retryable = true))
        store.accept(SharedThreadIntent.Start(focusedReplyRoute().copy(focusedMessageRequestNonce = 2)))
        val error = withTimeout(10_000L) {
            store.state.map { it.payload }.first { it.originStatus == "error" }
        }
        assertEquals("thread_parent_timeout", error.originErrorCode)
        assertNull(error.errorCode)
        scope.cancel()
    }
    @Test
    fun focusedReplyParentCompletionCannotMutateLaterRoute() = runTest {
        val parentMessageId = MessageId("parent-1")
        val focusedMessageId = MessageId("reply-focus-1")
        val laterFocusedMessageId = MessageId("later-target")
        val releaseParent = CompletableDeferred<Unit>()
        val parentStarted = CompletableDeferred<Unit>()
        val repository =
            FakeConversationRepository(
                messageContextResultsById =
                    mapOf(
                        focusedMessageId to
                            DataResult.Content(
                                focusedReplySnapshot(
                                    threadId = "thread-parent-1",
                                    parentChannelId = "parent-channel-1",
                                    parentMessageId = parentMessageId.value,
                                    focusedMessageId = focusedMessageId.value
                                )
                            ),
                        parentMessageId to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "parent-channel-1",
                                    messages =
                                        listOf(
                                            message(
                                                threadId = "parent-channel-1",
                                                channelId = "parent-channel-1",
                                                id = parentMessageId.value,
                                                seq = 1,
                                                body = "stale parent"
                                            )
                                        )
                                )
                            ),
                        laterFocusedMessageId to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "channel-2",
                                    messages =
                                        listOf(
                                            message(
                                                threadId = "channel-2",
                                                channelId = "channel-2",
                                                id = laterFocusedMessageId.value,
                                                seq = 3,
                                                body = "later route"
                                            )
                                        ),
                                    conversation = conversationSummary("channel-2")
                                ).copy(highlightedMessageId = laterFocusedMessageId)
                            )
                    )
            ).also { fake ->
                fake.messageContextStartedById[parentMessageId] = parentStarted
                fake.releaseMessageContextById[parentMessageId] = releaseParent
            }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository
                    ),
                scope = scope
            )

        store.accept(SharedThreadIntent.Start(focusedReplyRoute()))
        withTimeout(10_000L) { parentStarted.await() }
        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    instanceId = "shared-shell-thread-channel-2",
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                    focusedMessageId = laterFocusedMessageId.value
                )
            )
        )
        releaseParent.complete(Unit)
        yield()

        val payload = store.state.value.payload
        assertEquals("channel-2", payload.sourceThreadId)
        assertEquals(listOf("later route"), payload.messages.map { it.body })
        assertEquals("none", payload.originStatus)
        assertNull(payload.originMessage)
        scope.cancel()
    }
    @Test
    fun fetchThreadPayloadFallsBackWhenFocusedMessageContextDoesNotMatchRouteThread() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "repository body")),
            messageContextResult =
                DataResult.Content(
                    threadSnapshot(
                        threadId = "channel-2",
                        messages =
                            listOf(
                                message(threadId = "channel-2", id = "target", seq = 19, body = "wrong channel body")
                            ),
                        conversation = conversationSummary("channel-2")
                    ).copy(highlightedMessageId = MessageId("target"))
                )
        )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload =
            (dataSource.fetchThreadPayload(basePayload().copy(focusedMessageId = "target")) as DataResult.Content).data

        assertEquals(listOf(MessageId("target")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(null), repository.getMessageContextChannelIdRequests)
        assertEquals(listOf(ThreadId("channel-1")), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals(listOf("repository body"), payload.messages.map { it.body })
        assertEquals("target", payload.focusedMessageId)
    }
    @Test
    fun fetchThreadPayloadLeavesHeaderBlankWhenRepositorySnapshotTitleIsIdentity() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                currentMember = member("u1", "Artin"),
                channels =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            channelType = "public",
                            kind = ConversationKind.Channel,
                            name = "all",
                            subtitle = "General updates",
                            memberCount = 4
                        ).toHomeChannelRow()
                    ),
                directMessages = emptyList(),
                unreadTotal = 0
            )
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "repository body",
                            conversation = conversationSummary("channel-1").copy(name = "channel-1")
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

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        // fb845a19 removed the durable HomeSnapshot cache: identity titles can
        // no longer be replaced from the local home lookup, so the payload
        // header stays blank and display resolution happens in
        // threadHeaderDisplayTitle. Home-derived subtitle/membersCount
        // enrichment is gone with its source.
        assertEquals("", payload.title)
        assertEquals("Channel", threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals("repository body", payload.messages.single().body)
    }
    @Test
    fun replyParentContextStoreDoesNotReuseSameUuidAcrossPrincipals() = runTest {
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val environmentId = "https://prod.example.test"
        val accountOneDataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(replyResolutionSession("account-1")),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { replyResolutionSession("account-1") },
                    environmentProvider = { environmentId },
                )
            )
        val provenance = requireNotNull(accountOneDataSource.captureReplyCacheProducerContext()).provenance
        accountOneDataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-a",
            parentChannelId = "channel-1",
            parentMessage = contentPayload("account one parent").messages.single(),
            threadChannelId = "thread-m1"
        )

        val accountTwoParent = message(threadId = "channel-1", id = "m1", seq = 1, body = "account two parent")
        val accountTwoRepository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(
                                        threadId = "thread-m1",
                                        id = "reply-1",
                                        seq = 2,
                                        body = "account two reply"
                                    )
                                ),
                            parentMessage = null,
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = listOf(accountTwoParent),
                            parentMessage = accountTwoParent
                        )
                    )
            )
        val accountTwoDataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(replyResolutionSession("account-2")),
                conversationRepository = accountTwoRepository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { replyResolutionSession("account-2") },
                    environmentProvider = { environmentId },
                )
            )
        val route =
            activityReplyPayload(
                parentKind = ConversationKind.Channel,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                threadId = "thread-m1"
            )

        val payload =
            (accountTwoDataSource.fetchThreadPayload(route, persistResult = false) as DataResult.Content).data

        assertTrue(MessageId("m1") in accountTwoRepository.getMessageContextRequests)
        assertEquals("account two parent", payload.originMessage?.body)
        assertEquals(listOf("account two reply"), payload.messages.map { it.body })
    }
    @Test
    fun fetchReplyThreadPayloadFiltersParentChannelMessagesFromSnapshot() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(threadId = "channel-1", id = "outer-before", seq = 1, body = "outer before"),
                                    message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body"),
                                    message(threadId = "channel-1", id = "outer-after", seq = 3, body = "outer after")
                                ),
                            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body"),
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
        val base =
            basePayload().copy(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1"
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals("parent body", payload.originMessage?.body)
    }
}
