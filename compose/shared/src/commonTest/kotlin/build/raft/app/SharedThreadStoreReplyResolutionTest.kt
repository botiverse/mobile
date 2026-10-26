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
import build.raft.app.SharedThreadStoreTestFixtures.unresolvedReplyPayload
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.toHomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    fun focusMessageUpdatesMessageIdAndIncrementsNonce() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val initial = basePayload().copy(focusedMessageId = null, focusedMessageRequestNonce = 0)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, initialPayload = initial)

        store.accept(SharedThreadIntent.FocusMessage(messageId = "msg-1", switchToTab = null))
        assertEquals("msg-1", store.state.value.payload.focusedMessageId)
        val firstNonce = store.state.value.payload.focusedMessageRequestNonce
        assertTrue(firstNonce > 0)

        store.accept(SharedThreadIntent.FocusMessage(messageId = "msg-1", switchToTab = null))
        assertEquals("msg-1", store.state.value.payload.focusedMessageId)
        val secondNonce = store.state.value.payload.focusedMessageRequestNonce
        assertTrue(secondNonce > firstNonce)

        scope.cancel()
    }
    @Test
    fun focusedOriginCompletionRejectsNewerSameRouteOriginState() {
        val loading = focusedReplyRoute()
        val newerOrigin = contentPayload("newer parent").messages.single().copy(id = "parent-1")

        assertTrue(
            loading.acceptsFocusedOriginHydrationCompletion(
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-1",
                originStatusAtStart = "loading",
                originMessageAtStart = null
            )
        )
        assertFalse(
            loading.copy(originStatus = "loaded", originMessage = newerOrigin)
                .acceptsFocusedOriginHydrationCompletion(
                    parentChannelId = "parent-channel-1",
                    parentMessageId = "parent-1",
                    originStatusAtStart = "loading",
                    originMessageAtStart = null
                )
        )
        assertFalse(
            loading.copy(originStatus = "missing")
                .acceptsFocusedOriginHydrationCompletion(
                    parentChannelId = "parent-channel-1",
                    parentMessageId = "parent-1",
                    originStatusAtStart = "loading",
                    originMessageAtStart = null
                )
        )
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
    fun fetchReplyThreadPayloadUsesParentPairRepositoryPath() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "reply body",
                            id = "reply-1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")),
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
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = RaftThreadSurfaceKind.Channel,
                originParentMessageId = "m1",
                originMessage = contentPayload("parent body").messages.single(),
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = "m1",
                        enabled = true,
                        mentionChannelId = "channel-1",
                        parentMessageId = "m1",
                        streamChannelId = "channel-1"
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

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, payload.parentChannelKind)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("reply body", payload.messages.single().body)
        assertEquals("parent body", payload.originMessage?.body)
    }
    @Test
    fun visibleReplyMetadataSeedsSharedStoreForDirectReplyThreadFetch() = runTest {
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val parent = contentPayload("parent body").messages.single()
        val seedDataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val provenance = requireNotNull(seedDataSource.captureReplyCacheProducerContext()).provenance
        seedDataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-1",
            parentChannelId = "channel-1",
            parentMessage = parent,
            threadChannelId = "thread-m1"
        )
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("thread-m1") to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "thread-m1",
                                    messages =
                                        listOf(
                                            message(
                                                threadId = "thread-m1",
                                                id = "reply-1",
                                                seq = 2,
                                                body = "reply body"
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
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                originMessage = null,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getMessageContextRequests)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("loaded", payload.originStatus)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
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
    fun replyParentCandidateRejectsStaleProducerAfterIdentityPublication() = runTest {
        val environmentId = "https://prod.example.test"
        val authStore = InMemoryRaftAuthSessionStore(replyResolutionSession("account-1"))
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    environmentProvider = { environmentId },
                )
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        authStore.saveSession(
            replyResolutionSession("account-2").copy(selectedServerId = "server-b")
        )
        dataSource.readSession()

        val accepted = dataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-a",
            parentChannelId = "channel-1",
            parentMessage = contentPayload("account one parent").messages.single(),
            threadChannelId = "thread-m1"
        )

        assertFalse(accepted)
        assertNull(
            messageStore.getScoped(
                "$environmentId\u001faccount-1\u001fserver-a\u001f1",
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
        assertNull(
            messageStore.getScoped(
                "$environmentId\u001faccount-2\u001fserver-b\u001f2",
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
        assertNull(
            messageStore.getScoped(
                "$environmentId\u001faccount-1\u001fserver-b\u001f1",
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
        assertNull(
            messageStore.getScoped(
                "$environmentId\u001faccount-2\u001fserver-a\u001f2",
                ChannelId("channel-1"),
                MessageId("m1")
            )
        )
    }
    @Test
    fun resolvedParentPairSeedsReplyThreadResolveCache() = runTest {
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "reply body",
                            id = "reply-1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")),
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
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                conversationRepository = repository,
                cacheEnvironmentIdProvider = { "https://prod.example.test" }
            )
        val parent = contentPayload("parent body").messages.single()
        val unresolvedBase =
            unresolvedReplyPayload(parentMessageId = "m1").copy(originMessage = parent)

        val firstPayload = (dataSource.fetchThreadPayload(unresolvedBase, persistResult = false) as DataResult.Content).data
        val openedPayload = (dataSource.createOrOpenReplyThread(contentPayload("parent body"), parent) as DataResult.Content).data

        assertEquals("thread-m1", firstPayload.streamChannelId)
        assertEquals("thread-m1", openedPayload.streamChannelId)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(
            listOf(ChannelId("channel-1") to MessageId("m1")),
            repository.getReplyThreadRequests
        )
        assertEquals(listOf(false), repository.getReplyThreadIncludeFollowedSummaries)
        assertEquals(listOf(ThreadId("thread-m1")), repository.getThreadRequests)
    }
    @Test
    fun replyThreadResolutionCacheIsScopedByEnvironmentPrincipalAndRouteServer() = runTest {
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val parent = contentPayload("parent body").messages.single()
        val base = contentPayload("parent body")
        var environmentId = " https://prod.example.test/ "
        val authStore = InMemoryRaftAuthSessionStore(replyResolutionSession("account-1"))
        val selectedServerStore =
            SelectedServerStore(
                RaftShellStoreScope.create(
                    shellInstanceId = "reply-resolution-namespace",
                    initialServerId = "server-a"
                )
            )
        val repository = FakeConversationRepository()
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    environmentProvider = { environmentId },
                    serverIdProvider = { _ -> selectedServerStore.selectedServerId.value },
                )
            )

        repository.configureReplyThread("thread-a", parent.id)
        assertEquals(
            "thread-a",
            (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        environmentId = ""
        repository.configureReplyThread("thread-missing-environment", parent.id)
        // A blank environment is not a "legacy" identity that may reuse the prod bucket: it owns
        // no reply cache at all (namespace and payload owner both absent), so the open fails
        // closed and nothing was cached.
        assertEquals(
            "thread_reply_cache_provenance_stale",
            assertIs<DataResult.Error>(dataSource.createOrOpenReplyThread(base, parent)).error.code
        )
        val blankEnvironmentProvenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        assertEquals("", blankEnvironmentProvenance.environmentId)
        assertNull(blankEnvironmentProvenance.namespaceId)
        assertNull(blankEnvironmentProvenance.threadPayloadOwnerScopeId)
        assertNull(dataSource.cachedPayload("thread-missing-environment"))

        environmentId = "https://prod.example.test"
        selectedServerStore.select("server-b", "server-b")
        repository.configureReplyThread("thread-b", parent.id)
        assertEquals(
            "thread-b",
            (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        authStore.saveSession(replyResolutionSession("account-2"))
        repository.configureReplyThread("thread-account-2", parent.id)
        val accountTwoDataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    environmentProvider = { environmentId },
                    serverIdProvider = { _ -> selectedServerStore.selectedServerId.value },
                )
            )
        assertEquals(
            "thread-account-2",
            (accountTwoDataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        environmentId = "https://staging.example.test/"
        repository.configureReplyThread("thread-staging", parent.id)
        val stagingDataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = repository,
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { environmentId },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    environmentProvider = { environmentId },
                    serverIdProvider = { _ -> selectedServerStore.selectedServerId.value },
                )
            )
        assertEquals(
            "thread-staging",
            (stagingDataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        assertEquals(
            List(5) { ChannelId("channel-1") to MessageId(parent.id) },
            repository.createOrOpenRequests
        )
    }
    @Test
    fun replyThreadResolutionRejectsOwnerChangedWhileCreateOrOpenSuspends() = runTest {
        val authStore = InMemoryRaftAuthSessionStore(replyResolutionSession("account-1"))
        val selectedServerStore =
            SelectedServerStore(
                RaftShellStoreScope.create(
                    shellInstanceId = "reply-resolution-in-flight-identity",
                    initialServerId = "server-a"
                )
            )
        val repository = FakeConversationRepository()
        val createStarted = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        repository.configureReplyThread("thread-a", "m1")
        repository.createOrOpenStarted = createStarted
        repository.releaseCreateOrOpen = releaseCreate
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = authStore,
                selectedServerStore = selectedServerStore,
                conversationRepository = repository,
                messageStore = SharedThreadMessageStore(scope = backgroundScope),
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = authStore::readSession,
                    serverIdProvider = { _ -> selectedServerStore.selectedServerId.value },
                )
            )
        val base = contentPayload("parent body")
        val parent = base.messages.single()

        val serverAResult = async { dataSource.createOrOpenReplyThread(base, parent) }
        createStarted.await()
        selectedServerStore.select("server-b", "server-b")
        dataSource.readSession()
        releaseCreate.complete(Unit)
        assertEquals(
            "thread_reply_access_authority_changed",
            assertIs<DataResult.Error>(serverAResult.await()).error.code
        )

        repository.createOrOpenStarted = null
        repository.releaseCreateOrOpen = null
        repository.configureReplyThread("thread-b", parent.id)
        assertEquals(
            "thread-b",
            (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )

        selectedServerStore.select("server-a", "server-a")
        dataSource.readSession()
        repository.configureReplyThread("thread-a-fresh", parent.id)
        assertEquals(
            "thread-a-fresh",
            (dataSource.createOrOpenReplyThread(base, parent) as DataResult.Content).data.streamChannelId
        )
        assertEquals(
            List(3) { ChannelId("channel-1") to MessageId(parent.id) },
            repository.createOrOpenRequests
        )
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
    @Test
    fun fetchReplyThreadPayloadUsesOriginParentMessageFallbackForParentPair() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "reply body",
                            id = "reply-1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")),
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
                parentMessageId = null,
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

        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("reply body", payload.messages.single().body)
        assertEquals("parent body", payload.originMessage?.body)
    }
    @Test
    fun fetchReplyThreadPayloadVerifiesStreamHintBeforeEmitting() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("wrong-thread") to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "wrong-thread",
                                    body = "wrong reply body",
                                    id = "wrong-reply",
                                    conversation =
                                        conversationSummary("wrong-thread").copy(
                                            kind = ConversationKind.Thread,
                                            channelId = ChannelId("wrong-thread"),
                                            name = "wrong",
                                            subtitle = "#wrong",
                                            thread =
                                                ThreadConversation(
                                                    parentChannelId = ChannelId("other-channel"),
                                                    parentMessageId = MessageId("other-message"),
                                                    streamChannelId = ChannelId("wrong-thread")
                                                )
                                        )
                                )
                            ),
                        ThreadId("thread-m1") to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "thread-m1",
                                    body = "reply body",
                                    id = "reply-1",
                                    messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")),
                                    parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body"),
                                    conversation =
                                        conversationSummary("thread-m1").copy(
                                            kind = ConversationKind.Thread,
                                            channelId = ChannelId("thread-m1"),
                                            name = "general",
                                            subtitle = "#general",
                                            thread =
                                                ThreadConversation(
                                                    parentChannelId = ChannelId("channel-1"),
                                                    parentMessageId = MessageId("m1"),
                                                    streamChannelId = ChannelId("thread-m1")
                                                )
                                        )
                                )
                            )
                    ),
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-m1"),
                            changed = false,
                            parentMessageId = MessageId("m1"),
                            replyCount = 2
                        )
                    )
            )
        val base =
            basePayload().copy(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
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

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals(ThreadReplyPageTitle, threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
    }
    @Test
    fun fetchReplyThreadPayloadKeepsResolvedEmptyThreadRenderable() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("wrong-thread") to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "wrong-thread",
                                    body = "wrong reply body",
                                    id = "wrong-reply",
                                    conversation =
                                        conversationSummary("wrong-thread").copy(
                                            kind = ConversationKind.Thread,
                                            channelId = ChannelId("wrong-thread"),
                                            name = "wrong",
                                            subtitle = "#wrong",
                                            thread =
                                                ThreadConversation(
                                                    parentChannelId = ChannelId("other-channel"),
                                                    parentMessageId = MessageId("other-message"),
                                                    streamChannelId = ChannelId("wrong-thread")
                                                )
                                        )
                                )
                            ),
                        ThreadId("thread-m1") to DataResult.Empty(EmptyReason.NoMessages)
                    ),
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-m1"),
                            changed = false,
                            parentMessageId = MessageId("m1"),
                            replyCount = 0
                        )
                    )
            )
        val base =
            basePayload().copy(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "wrong-thread",
                streamChannelId = "wrong-thread",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                originStatus = "loading"
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("empty", payload.status)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals(emptyList(), payload.messages)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(null, payload.errorCode)
    }
    @Test
    fun fetchReplyThreadPayloadNormalizesLoadedOriginWithNoRepliesToEmptyState() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = emptyList(),
                            parentMessage =
                                message(
                                    threadId = "channel-1",
                                    id = "m1",
                                    seq = 10,
                                    body = "parent body"
                                ),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("channel-1"),
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
        val base =
            basePayload().copy(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                originStatus = "loading"
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("empty", payload.status)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("loaded", payload.originStatus)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(emptyList(), payload.messages)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
    }
}
