package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFakes.ParkableReadSessionStore
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.localPendingMessage
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.replyThreadConversation
import build.raft.app.SharedThreadStoreTestFixtures.startLocalSendForCurrentOwner
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.WireMessage
import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedThreadStoreLocalSendTest {
    @Test
    fun appendSentPagePatchesTimelineAndPreservesStoreHeaderIdentity() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload().copy(title = "Readable channel")))
        store.accept(
            SharedThreadIntent.AppendSentPage(
                ThreadMessagesPage(
                    messages =
                        listOf(
                            WireMessage(
                                id = "sent-1",
                                channelId = "channel-1",
                                senderType = "user",
                                senderId = "u1",
                                senderName = "Artin",
                                messageType = "chat",
                                content = "sent body"
                            )
                        ),
                    canLoadMoreBefore = null,
                    canLoadMoreAfter = null
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals("general", payload.title)
        assertEquals(listOf("cached body", "sent body"), payload.messages.map { it.body })
        assertNull(localStore.readThread(ThreadId("channel-1")))
        assertEquals("sent body", dataSource.cachedPayload("channel-1")?.messages?.last()?.body)
        scope.cancel()
    }
    @Test
    fun localSendSuccessReplacesTempBubbleWithCanonicalMessage() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-1", "sent body"))
        store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "sent body"))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        assertEquals(ThreadMessageDeliverySent, messages.first { it.id == "server-1" }.deliveryState)
        scope.cancel()
    }
    @Test
    fun concurrentSendCompletionPreservesSiblingSendAndInterleavedAgentReply() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val sessionStore = ParkableReadSessionStore(sampleSession())
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-1", "first"))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-2", "second"))

        sessionStore.parkNextRead()
        val firstCompletion =
            async {
                store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "first"))
            }
        sessionStore.nextReadStarted.await()

        store.replaceLocalSendForCurrentOwner(dataSource, "local-2", sentPage("server-2", "second"))
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("agent-1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("agent-1", "Agent"),
                        timestampLabel = "2026-07-19T00:47:00Z",
                        body = "agent reply",
                        seq = 12,
                    )
                )
            )
        )

        sessionStore.releaseNextRead.complete(Unit)
        firstCompletion.await()

        val visibleIds = store.state.value.payload.messages.map { it.id }.toSet()
        assertEquals(setOf("m1", "server-1", "server-2", "agent-1"), visibleIds)
        assertFalse(store.state.value.payload.messages.any { it.id.startsWith("local-") })
        val cachedIds = dataSource.cachedPayload("channel-1")?.messages.orEmpty().map { it.id }.toSet()
        assertEquals(setOf("m1", "server-1", "server-2", "agent-1"), cachedIds)
        scope.cancel()
    }
    @Test
    fun staleRefreshCannotDeletePendingOrAcknowledgedSendsStartedAfterItsObservationFence() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        api.channelMessagesStarted = CompletableDeferred()
        api.releaseChannelMessages = CompletableDeferred()
        val refresh = async { store.accept(SharedThreadIntent.Refresh) }
        api.channelMessagesStarted?.await()

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-send-a", "first", randomId = "rid-a")
        )
        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-send-b", "second", randomId = "rid-b")
        )
        store.replaceLocalSendForCurrentOwner(
            dataSource,
            "local-send-a",
            sentPage("server-a", "first", randomId = "rid-a")
        )

        api.releaseChannelMessages?.complete(Unit)
        refresh.await()

        assertEquals(
            setOf("m1", "server-a", "local-send-b"),
            store.state.value.payload.messages.map { it.id }.toSet()
        )
        assertFalse(store.state.value.payload.messages.any { it.id == "local-send-a" })

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("server-b"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u1", "Artin"),
                        timestampLabel = "2026-05-19T16:02:00Z",
                        body = "second",
                        seq = 12,
                        randomId = "rid-b"
                    )
                )
            )
        )

        assertEquals(
            setOf("m1", "server-a", "server-b"),
            store.state.value.payload.messages.map { it.id }.toSet()
        )
        assertFalse(store.state.value.payload.messages.any { it.id.startsWith("local-send-") })
        scope.cancel()
    }
    @Test
    fun realtimeOwnMessageEchoReplacesTransientLocalSendingBubble() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult =
                            DataResult.Content(
                                threadSnapshot(
                                    body = "cached body",
                                    seq = 10,
                                    conversation = conversationSummary().copy(joined = true)
                                )
                            )
                    )
            )
        val cachedPayload =
            contentPayload("cached body").let { content ->
                content.copy(messages = content.messages.map { message -> message.copy(seq = 10L) })
            }
        dataSource.cachePayloadForTest(cachedPayload)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-send-channel-1-1", "sent body")
        )
        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("server-1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u1", "Artin"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "sent body",
                        seq = 11
                    )
                )
            )
        )

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-send-channel-1-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        assertEquals(listOf("cached body", "sent body"), messages.map { it.body })
        // task #562: the cache snapshot follows the live store — canonical
        // echo present, transient local-send bubble already replaced.
        val cachedMessages = dataSource.cachedPayload("channel-1")?.messages.orEmpty()
        assertFalse(cachedMessages.any { it.id == "local-send-channel-1-1" })
        assertEquals(1, cachedMessages.count { it.id == "server-1" })
        scope.cancel()
    }
    @Test
    fun appendSentPageDropsMatchingTransientLocalSendingBubble() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-send-channel-1-1", "sent body")
        )
        store.accept(SharedThreadIntent.AppendSentPage(sentPage("server-1", "sent body")))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-send-channel-1-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        scope.cancel()
    }
    @Test
    fun localSendSuccessCachesCanonicalMessageWithoutTempSendingBubble() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-1", "sent body"))
        store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "sent body"))

        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedMessages = dataSource.cachedPayload("channel-1")?.messages.orEmpty()
        assertFalse(cachedMessages.any { it.id == "local-1" })
        assertFalse(cachedMessages.any { it.deliveryState == ThreadMessageDeliverySending })
        assertEquals(ThreadMessageDeliverySent, cachedMessages.first { it.id == "server-1" }.deliveryState)
        scope.cancel()
    }
    @Test
    fun retiredOwnerLocalSendSuccessAndFailureCannotMutateCurrentOwnerStateOrCache() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        val ownerBSession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-b", email = "b@example.test", displayName = "Owner B"),
                accessToken = "access-b",
                refreshToken = "refresh-b"
            )
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                messageStore = SharedThreadMessageStore(scope = backgroundScope),
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                replyCacheIdentityAuthority = { identity },
                payloadStore = payloadStore
            )
        val ownerA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val route = contentPayload("owner-b body")
        val routeIdentity = route.resolveThreadRouteIdentity()
        identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "u-b",
                serverId = "server-1",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        val ownerB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        assertTrue(
            dataSource.cachePayload(
                payload = route,
                serverId = "server-1",
                ownerScopeId = ownerB.threadPayloadOwnerScopeId.orEmpty(),
                expectedProvenance = ownerB
            )
        )
        val pending = localPendingMessage("local-shared", "owner-b pending")
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                initialPayload = route.copy(messages = route.messages + pending)
            )

        store.accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = pending.id,
                page = sentPage("owner-a-server", "owner-a completion"),
                expectedProvenance = ownerA,
                expectedRoute = routeIdentity
            )
        )
        store.accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = pending.id,
                errorCode = "owner_a_failure",
                expectedProvenance = ownerA,
                expectedRoute = routeIdentity
            )
        )

        val visible = store.state.value.payload
        assertFalse(visible.messages.any { it.id == "owner-a-server" })
        assertEquals(ThreadMessageDeliverySending, visible.messages.first { it.id == pending.id }.deliveryState)
        assertNull(
            payloadStore.get(
                "server-1",
                "channel-1",
                environmentId = ownerA.environmentId,
                principalId = ownerA.principalId,
                ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty()
            )
        )
        val ownerBCache =
            payloadStore.get(
                "server-1",
                "channel-1",
                environmentId = ownerB.environmentId,
                principalId = ownerB.principalId,
                ownerScopeId = ownerB.threadPayloadOwnerScopeId.orEmpty()
            )
        assertEquals(listOf("owner-b body"), ownerBCache?.messages?.map { it.body })
        scope.cancel()
    }
    @Test
    fun retiredOwnerNullProvenanceGapRejectsLocalSendBeforeApiOrStateMutation() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val api = FakeThreadApi()
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        val ownerBSession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-b", email = "b@example.test", displayName = "Owner B"),
                accessToken = "access-b",
                refreshToken = "refresh-b"
            )
        val ownerAIdentity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L
            )
        val ownerBIdentity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "u-b",
                serverId = "server-1",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        var identity: ThreadReplyCacheIdentitySnapshot? = ownerAIdentity
        val transitionWaiterEntered = CompletableDeferred<Unit>()
        val releaseTransitionWaiter = CompletableDeferred<Unit>()
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                messageStore = SharedThreadMessageStore(scope = backgroundScope),
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = FakeConversationRepository(),
                replyCacheIdentityAuthority = { identity },
                replyCacheIdentityTransitionWaiter = {
                    transitionWaiterEntered.complete(Unit)
                    releaseTransitionWaiter.await()
                    true
                },
                payloadStore = payloadStore
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        withTimeout(10_000) {
            while (!store.state.value.writeActionsEnabled) {
                delay(10)
            }
        }
        val ownerA = requireNotNull(dataSource.currentReplyCacheProvenance())
        val ownerAAuthority =
            requireNotNull(
                dataSource.captureThreadLocalSendAuthority(
                    state = store.state.value,
                    mountedOwnerProvenance = ownerA
                )
            )
        val beforeGapState = store.state.value
        val beforeGapOwnerACache =
            payloadStore.get(
                serverId = "server-1",
                threadId = "channel-1",
                environmentId = ownerA.environmentId,
                principalId = ownerA.principalId,
                ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty()
            )

        identity = null
        val ownerTransition = async { dataSource.captureReplyCacheProducerContext() }
        transitionWaiterEntered.await()

        assertTrue(store.state.value.writeActionsEnabled)
        assertNull(dataSource.currentReplyCacheProvenance())
        assertNull(
            dataSource.captureThreadLocalSendAuthority(
                state = store.state.value,
                mountedOwnerProvenance = ownerA
            )
        )
        val admittedDuringGap =
            store.admitLocalSend(
                message = localPendingMessage("local-gap", "must not send"),
                expectedProvenance = ownerAAuthority.provenance,
                expectedRoute = ownerAAuthority.route
            )
        if (admittedDuringGap) {
            dataSource.sendMessage(
                channelId = "channel-1",
                body = "must not send",
                asTask = false,
                attachmentIds = emptyList(),
                randomId = "rid-gap"
            )
        }

        assertFalse(admittedDuringGap)
        assertEquals(0, api.sendMessageCalls)
        assertEquals(beforeGapState, store.state.value)

        identity = ownerBIdentity
        releaseTransitionWaiter.complete(Unit)
        val ownerB = requireNotNull(ownerTransition.await()).provenance
        assertEquals("u-b", ownerB.principalId)
        assertNull(
            dataSource.captureThreadLocalSendAuthority(
                state = store.state.value,
                mountedOwnerProvenance = ownerA
            )
        )
        assertFalse(
            store.admitLocalSend(
                message = localPendingMessage("local-gap", "must not send"),
                expectedProvenance = ownerAAuthority.provenance,
                expectedRoute = ownerAAuthority.route
            )
        )
        store.accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = "local-gap",
                page = sentPage("server-gap", "must not send"),
                expectedProvenance = ownerAAuthority.provenance,
                expectedRoute = ownerAAuthority.route
            )
        )
        store.accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = "local-gap",
                errorCode = "owner_a_failure",
                expectedProvenance = ownerAAuthority.provenance,
                expectedRoute = ownerAAuthority.route
            )
        )
        assertEquals(beforeGapState, store.state.value)
        assertEquals(
            beforeGapOwnerACache,
            payloadStore.get(
                serverId = "server-1",
                threadId = "channel-1",
                environmentId = ownerA.environmentId,
                principalId = ownerA.principalId,
                ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty()
            )
        )
        assertNull(
            payloadStore.get(
                serverId = "server-1",
                threadId = "channel-1",
                environmentId = ownerB.environmentId,
                principalId = ownerB.principalId,
                ownerScopeId = ownerB.threadPayloadOwnerScopeId.orEmpty()
            )
        )
        assertFalse(store.state.value.payload.messages.any { it.id == "local-gap" || it.id == "server-gap" })
        scope.cancel()
    }
    @Test
    fun replyThreadLocalSendSuccessKeepsCanonicalSentMessageWhenEchoUsesParentChannel() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
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
                                    conversation = replyThreadConversation(joined = true, canSend = true)
                                )
                            )
                    ),
                channelRepository = FakeChannelRepository { DataResult.Content(joinedHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val base =
            replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                .copy(
                    status = "empty",
                    parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                    parentChannelKind = "channel",
                    originStatus = "loaded",
                    originMessage =
                        RaftThreadMessageItem(
                            id = "m1",
                            authorId = "u2",
                            authorName = "Cindy",
                            body = "parent body",
                            threadChannelId = "channel-1"
                        )
                )

        store.accept(SharedThreadIntent.Start(base))
        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-1", "sent body", streamChannelId = "thread-m1")
        )
        store.replaceLocalSendForCurrentOwner(
            dataSource,
            "local-1",
            sentPage("server-1", "sent body", channelId = "channel-1", threadId = null)
        )

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals(listOf("server-1"), payload.messages.map { it.id })
        assertFalse(payload.messages.any { it.id == "local-1" })
        assertEquals(ThreadMessageDeliverySent, payload.messages.single().deliveryState)
        assertNull(localStore.readThread(ThreadId("thread-m1")))
        assertEquals("sent body", dataSource.cachedPayload("thread-m1")?.messages?.single()?.body)
        scope.cancel()
    }
    @Test
    fun cachePayloadDropsTransientLocalSendingBubble() = runTest {
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val payload =
            contentPayload("sent body", id = "server-1").copy(
                messages =
                    listOf(
                        localPendingMessage("local-send-channel-1-1", "sent body"),
                        contentPayload("sent body", id = "server-1").messages.single()
                    )
            )

        dataSource.cachePayloadForTest(payload)
        val cachedPayload = dataSource.cachedPayload("channel-1")

        assertNotNull(cachedPayload)
        assertFalse(cachedPayload.messages.any { it.id == "local-send-channel-1-1" })
        assertFalse(cachedPayload.messages.any { it.deliveryState == ThreadMessageDeliverySending })
        assertEquals(listOf("server-1"), cachedPayload.messages.map { it.id })
    }
    @Test
    fun cachedPayloadDoesNotPersistHeaderFallbackTitleAsConversationName() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val route =
            basePayload().copy(
                title = threadHeaderFallbackTitle(RaftThreadSurfaceKind.Channel),
                sourceThreadId = "joint-1",
                streamChannelId = "joint-1",
                channelKind = "joint"
            )
        val payload = contentPayloadForRoute(route, "body", channelId = "joint-1")

        dataSource.cachePayloadForTest(payload)

        val cached = localStore.readThread(ThreadId("joint-1"))
        assertNull(cached)
        assertEquals(listOf("body"), dataSource.cachedPayload("joint-1")?.messages?.map { it.body })
    }
    @Test
    fun canonicalPayloadCacheKeepsThreadTimelineInMemoryWithoutPersistentRewrite() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(threadSnapshot(body = "stale db body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val freshPayload = contentPayload("fresh memory body")

        val selected =
            dataSource.cacheCanonicalPayload(freshPayload)

        assertEquals(listOf("fresh memory body"), selected.messages.map { it.body })
        assertEquals(listOf("fresh memory body"), dataSource.cachedPayload("channel-1")?.messages?.map { it.body })
        assertEquals(listOf("stale db body"), localStore.readThread(ThreadId("channel-1"))?.messages?.map { it.body })
    }
    @Test
    fun shorterInitialWindowDoesNotShrinkExpandedVisibleTimeline() {
        val expandedMessages =
            (1..72).map { index ->
                RaftThreadMessageItem(
                    id = "m$index",
                    authorId = "u1",
                    authorName = "Artin",
                    body = "body $index",
                    seq = index.toLong()
                )
            }
        val previous =
            basePayload().copy(
                status = "content",
                messages = expandedMessages,
                canLoadMoreBefore = true
            )
        val latestWindow =
            previous.copy(
                messages =
                    expandedMessages
                        .takeLast(50)
                        .map { message ->
                            if (message.id == "m72") {
                                message.copy(body = "updated tail")
                            } else {
                                message.copy()
                            }
                        },
                canLoadMoreBefore = true
            )

        val stable = latestWindow.preserveStableTimelineFrom(previous)

        assertEquals(72, stable.messages.size)
        assertEquals("m1", stable.messages.first().id)
        assertEquals("updated tail", stable.messages.last().body)
    }
    @Test
    fun cachedReplyPayloadDoesNotPersistParentDisplaySubtitle() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val payload =
            replyPayload(parentMessageId = "parent-1", threadId = "thread-parent-1")
                .copy(
                    status = "content",
                    subtitle = "#general",
                    originMessage =
                        RaftThreadMessageItem(
                            id = "parent-1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "Parent message",
                            threadChannelId = "thread-parent-1"
                        ),
                    messages =
                        listOf(
                            RaftThreadMessageItem(
                                id = "reply-1",
                                authorId = "u2",
                                authorName = "Cindy",
                                body = "Reply body"
                            )
                        )
                )

        dataSource.cachePayloadForTest(payload)

        assertNull(localStore.readThread(ThreadId("thread-parent-1")))
        val cached = dataSource.cachedPayload("thread-parent-1")
        assertEquals(RaftThreadSurfaceKind.ReplyThread, cached?.surfaceKind)
        assertEquals("#general", cached?.subtitle)
    }
    @Test
    fun localSendFailurePersistsFailedBubbleWithRetryContext() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage(
                id = localMessageId,
                body = "retry body",
                asTask = true,
                attachmentIds = listOf("att-1")
            )
        )
        store.failLocalSendForCurrentOwner(dataSource, localMessageId, "network_error")

        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedPayload = dataSource.cachedPayload("channel-1")
        assertNotNull(cachedPayload)
        val cachedFailed = cachedPayload.messages.first { it.id == localMessageId }
        assertEquals(ThreadMessageDeliveryFailed, cachedFailed.deliveryState)
        assertEquals("network_error", cachedFailed.pendingSendErrorCode)
        assertEquals(true, cachedFailed.pendingSendContext?.asTask)
        assertEquals(listOf("att-1"), cachedFailed.pendingSendContext?.attachmentIds)
        scope.cancel()
    }
    @Test
    fun localSendFailurePersistsReadableErrorMessage() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage(localMessageId, "retry body"))

        store.failLocalSendForCurrentOwner(
            dataSource = dataSource,
            messageId = localMessageId,
            errorCode = "body_too_large",
            errorMessage = "Body is too large"
        )

        val failed = store.state.value.payload.messages.first { it.id == localMessageId }
        assertEquals("body_too_large", failed.pendingSendErrorCode)
        assertEquals("Body is too large", failed.pendingSendErrorMessage)
        val cachedPayload = dataSource.cachedPayload("channel-1")
        assertNotNull(cachedPayload)
        val cachedFailed = cachedPayload.messages.first { it.id == localMessageId }
        assertEquals("body_too_large", cachedFailed.pendingSendErrorCode)
        assertEquals("Body is too large", cachedFailed.pendingSendErrorMessage)
        scope.cancel()
    }
    @Test
    fun dataSourceCachesLocalSendFailureAfterPageScopeEnds() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val basePayload =
            basePayload().copy(
                status = "content",
                messages = listOf(RaftThreadMessageItem(id = "m1", authorId = "u1", authorName = "Artin", body = "existing"))
            )
        val localMessage = localPendingMessage("local-send-channel-1-1", "retry body")

        dataSource.cacheLocalSendFailure(
            basePayload = basePayload,
            localMessage = localMessage,
            errorCode = "thread_send_network",
            errorMessage = "offline"
        )

        val cached = dataSource.cachedPayload("channel-1")
        assertNotNull(cached)
        val failed = cached.messages.first { it.id == localMessage.id }
        assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)
        assertEquals("thread_send_network", failed.pendingSendErrorCode)
        assertEquals("offline", failed.pendingSendErrorMessage)
    }
    @Test
    fun dataSourceCachesLocalSendSuccessAfterPageScopeEnds() = runTest {
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val basePayload =
            basePayload().copy(
                status = "content",
                messages = listOf(RaftThreadMessageItem(id = "m1", authorId = "u1", authorName = "Artin", body = "existing"))
            )
        val localMessage = localPendingMessage("local-send-channel-1-1", "sent body")

        dataSource.cacheLocalSendSuccess(
            basePayload = basePayload,
            localMessage = localMessage,
            sentPage = sentPage("server-1", "sent body")
        )

        val cached = dataSource.cachedPayload("channel-1")
        assertNotNull(cached)
        assertFalse(cached.messages.any { it.id == localMessage.id })
        assertEquals(1, cached.messages.count { it.id == "server-1" })
    }
    @Test
    fun dismissLocalFailedSendRemovesFailedBubbleFromStateAndLocalCache() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage(localMessageId, "delete me"))
        store.failLocalSendForCurrentOwner(dataSource, localMessageId, "network_error")

        store.accept(SharedThreadIntent.DismissLocalFailedSend(localMessageId))

        assertEquals(null, store.state.value.payload.messages.firstOrNull { it.id == localMessageId })
        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedPayload = dataSource.cachedPayload("channel-1")
        assertEquals(null, cachedPayload?.messages?.firstOrNull { it.id == localMessageId })
        scope.cancel()
    }
    @Test
    fun cancelLocalSendRemovesSendingBubbleFromStateAndLocalCache() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage(localMessageId, "cancel me"))

        store.accept(SharedThreadIntent.CancelLocalSend(localMessageId))

        assertEquals(null, store.state.value.payload.messages.firstOrNull { it.id == localMessageId })
        assertNull(localStore.readThread(ThreadId("channel-1")))
        val cachedPayload = dataSource.cachedPayload("channel-1")
        assertEquals(null, cachedPayload?.messages?.firstOrNull { it.id == localMessageId })
        scope.cancel()
    }
    @Test
    fun refreshRetainsLocalFailedBubbleMissingFromServerPage() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val localMessageId = "local-send-channel-1-1"
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage(localMessageId, "retry body"))
        store.failLocalSendForCurrentOwner(dataSource, localMessageId, "network_error")

        store.accept(SharedThreadIntent.Refresh)

        val messages = store.state.value.payload.messages
        assertEquals(ThreadMessageDeliveryFailed, messages.first { it.id == localMessageId }.deliveryState)
        assertEquals("retry body", messages.first { it.id == localMessageId }.body)
        assertEquals("network body", messages.first { it.id == "m1" }.body)
        assertEquals(1, messages.count { it.id == localMessageId })
        scope.cancel()
    }
    @Test
    fun localSendFailureKeepsFailedBubbleWithRetryContext() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage(
                id = "local-1",
                body = "retry body",
                asTask = true,
                attachmentIds = listOf("att-1")
            )
        )

        store.failLocalSendForCurrentOwner(dataSource, "local-1", "network_error")

        val failed = store.state.value.payload.messages.first { it.id == "local-1" }
        assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)
        assertEquals("retry body", failed.body)
        assertEquals("network_error", failed.pendingSendErrorCode)
        assertEquals(true, failed.pendingSendContext?.asTask)
        assertEquals(listOf("att-1"), failed.pendingSendContext?.attachmentIds)
        scope.cancel()
    }
    @Test
    fun retryFailureReusesSameTempBubbleAndRetrySuccessReplacesIt() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val local = localPendingMessage("local-1", "retry body")
        store.startLocalSendForCurrentOwner(dataSource, local)
        store.failLocalSendForCurrentOwner(dataSource, "local-1", "offline")

        store.startLocalSendForCurrentOwner(
            dataSource,
            local.copy(deliveryState = ThreadMessageDeliverySending)
        )
        store.failLocalSendForCurrentOwner(dataSource, "local-1", "still_offline")

        val failedMessages = store.state.value.payload.messages.filter { it.id == "local-1" }
        assertEquals(1, failedMessages.size)
        assertEquals(ThreadMessageDeliveryFailed, failedMessages.single().deliveryState)
        assertEquals("still_offline", failedMessages.single().pendingSendErrorCode)

        store.startLocalSendForCurrentOwner(
            dataSource,
            failedMessages.single().copy(deliveryState = ThreadMessageDeliverySending)
        )
        store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "retry body"))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        scope.cancel()
    }
    @Test
    fun localSendReplacementDoesNotDuplicateServerMessageAlreadyInFlow() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.startLocalSendForCurrentOwner(dataSource, localPendingMessage("local-1", "sent body"))
        store.accept(SharedThreadIntent.AppendSentPage(sentPage("server-1", "sent body")))

        store.replaceLocalSendForCurrentOwner(dataSource, "local-1", sentPage("server-1", "sent body"))

        val messages = store.state.value.payload.messages
        assertFalse(messages.any { it.id == "local-1" })
        assertEquals(1, messages.count { it.id == "server-1" })
        scope.cancel()
    }
    @Test
    fun retryPreservesAttachmentIdsAcrossFailureAndRetryCycle() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val local =
            localPendingMessage(
                id = "local-1",
                body = "with attachments",
                attachmentIds = listOf("att-1", "att-2")
            )
        store.startLocalSendForCurrentOwner(dataSource, local)
        store.failLocalSendForCurrentOwner(dataSource, "local-1", "network_error")

        val failed = store.state.value.payload.messages.first { it.id == "local-1" }
        assertEquals(ThreadMessageDeliveryFailed, failed.deliveryState)
        assertEquals(listOf("att-1", "att-2"), failed.pendingSendContext?.attachmentIds)
        assertEquals(2, failed.attachments.size)

        store.startLocalSendForCurrentOwner(
            dataSource,
            failed.copy(deliveryState = ThreadMessageDeliverySending)
        )

        val retried = store.state.value.payload.messages.first { it.id == "local-1" }
        assertEquals(ThreadMessageDeliverySending, retried.deliveryState)
        assertNull(retried.pendingSendErrorCode)
        assertEquals(listOf("att-1", "att-2"), retried.pendingSendContext?.attachmentIds)
        assertEquals(2, retried.attachments.size)
        scope.cancel()
    }
    private suspend fun SharedThreadStore.failLocalSendForCurrentOwner(
        dataSource: SharedThreadDataSource,
        messageId: String,
        errorCode: String?,
        errorMessage: String? = null
    ) {
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = messageId,
                errorCode = errorCode,
                errorMessage = errorMessage,
                expectedProvenance = provenance,
                expectedRoute = state.value.payload.resolveThreadRouteIdentity()
            )
        )
    }
    private suspend fun SharedThreadStore.replaceLocalSendForCurrentOwner(
        dataSource: SharedThreadDataSource,
        messageId: String,
        page: ThreadMessagesPage
    ) {
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = messageId,
                page = page,
                expectedProvenance = provenance,
                expectedRoute = state.value.payload.resolveThreadRouteIdentity()
            )
        )
    }
    private fun sentPage(
        id: String,
        body: String,
        channelId: String = "channel-1",
        threadId: String? = null,
        randomId: String? = null
    ): ThreadMessagesPage =
        ThreadMessagesPage(
            messages =
                listOf(
                    WireMessage(
                        id = id,
                        channelId = channelId,
                        threadId = threadId,
                        senderType = "user",
                        senderId = "u1",
                        senderName = "Artin",
                        messageType = "chat",
                        content = body,
                        createdAt = "2026-05-19T16:01:00Z",
                        seq = 11,
                        randomId = randomId
                    )
                ),
            canLoadMoreBefore = null,
            canLoadMoreAfter = null
        )
}
