package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.ConcurrentTestLogBuffer
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.threadPayloadOwnerScopeIdForTest
import build.raft.app.SharedThreadStoreTestFixtures.virtualForegroundLoadGate
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.threadChannelHeaderIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedThreadStoreForegroundGateTest {
    private fun foregroundGateReplyRepository(): FakeConversationRepository =
        FakeConversationRepository(
            threadResult =
                DataResult.Content(
                    threadSnapshot(
                        threadId = "thread-m1",
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
    private fun foregroundGateDataSource(
        repository: FakeConversationRepository,
        payloadStore: SharedThreadPayloadStore,
        scopeName: String,
        diagnosticsLogger: (String) -> Unit = projectionRejectingDiagnosticsLogger,
        storeScope: CoroutineScope,
    ): SharedThreadDataSource =
        testSharedThreadDataSource(
            storeScope = storeScope,
            networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            selectedServerStore = SelectedServerStore(RaftShellStoreScope.create(scopeName, "server-1")),
            conversationRepository = repository,
            payloadStore = payloadStore,
            diagnosticsLogger = { message ->
                projectionRejectingDiagnosticsLogger(message)
                diagnosticsLogger(message)
            }
        )
    @Test
    fun replyThreadStartOfDifferentThreadDoesItsOwnFetchWhilePrewarmIsInFlight() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = foregroundGateReplyRepository()
        val logs = ConcurrentTestLogBuffer()
        val dataSource = foregroundGateDataSource(repository, payloadStore, "thread-fg-join-other", logs::add, storeScope = backgroundScope)
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        val prewarm =
            async {
                payloadStore.prewarm("server-1", "thread-other", ownerScopeId) {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    DataResult.Empty(EmptyReason.NoMessages)
                }
            }
        fetchStarted.await()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        withTimeout(10_000L) {
            store.accept(SharedThreadIntent.Start(replyPayload(parentMessageId = "m1", threadId = "thread-m1")))
        }

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals("reply body", store.state.value.payload.messages.single().body)
        assertTrue(logs.any { it == "thread_refresh_reuse_prewarm threadId=thread-m1 outcome=own_fetch reason=no_prewarm" })
        releaseFetch.complete(Unit)
        prewarm.await()
        scope.cancel()
        appScope.cancel()
    }
    @Test
    fun replyThreadStartStopsWaitingOnStuckPrewarmAndFetchesItself() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore =
            SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate(), prewarmJoinTimeoutMillis = 100L)
        val repository = foregroundGateReplyRepository()
        val logs = ConcurrentTestLogBuffer()
        val dataSource = foregroundGateDataSource(repository, payloadStore, "thread-fg-join-stuck", logs::add, storeScope = backgroundScope)
        val fetchStarted = CompletableDeferred<Unit>()
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        val stuckPrewarm =
            launch {
                payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
                    fetchStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        fetchStarted.await()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        withTimeout(10_000L) {
            store.accept(SharedThreadIntent.Start(replyPayload(parentMessageId = "m1", threadId = "thread-m1")))
        }

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals("reply body", store.state.value.payload.messages.single().body)
        assertTrue(logs.any { it == "thread_refresh_reuse_prewarm threadId=thread-m1 outcome=own_fetch reason=join_timeout" })
        stuckPrewarm.cancel()
        scope.cancel()
        appScope.cancel()
    }
    @Test
    fun threadStartHoldsForegroundGateUntilLoadEndsAndReleasesOnFailure() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = foregroundGateReplyRepository()
        repository.getReplyThreadStarted = CompletableDeferred()
        repository.releaseGetReplyThread = CompletableDeferred()
        val dataSource = foregroundGateDataSource(repository, payloadStore, "thread-fg-gate-failure", storeScope = backgroundScope)
        val gate = payloadStore.foregroundLoadGate
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val logs = ConcurrentTestLogBuffer()
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        val opened = async { store.accept(SharedThreadIntent.Start(replyPayload(parentMessageId = "m1", threadId = "thread-m1"))) }
        withTimeout(10_000L) { repository.getReplyThreadStarted?.await() }
        assertEquals(1, gate.activeCount(), "the foreground open holds the gate while it loads")

        repository.threadResult = DataResult.Error(ScreenError(code = "thread_network", retryable = true))
        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(10_000L) { opened.await() }

        assertEquals("error", store.state.value.payload.status)
        assertEquals(0, gate.activeCount(), "a failed open must release the gate")
        assertTrue(logs.any { it.startsWith("thread_foreground_load_released reason=start_finished ") })
        scope.cancel()
        appScope.cancel()
    }
    @Test
    fun lateCompletionOfATimedOutPrewarmCannotOverwriteTheOpensOwnFetch() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore =
            SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate(), prewarmJoinTimeoutMillis = 100L)
        val repository = foregroundGateReplyRepository()
        val logs = ConcurrentTestLogBuffer()
        val dataSource = foregroundGateDataSource(repository, payloadStore, "thread-fg-join-late", logs::add, storeScope = backgroundScope)
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale = contentPayloadForRoute(base = route, body = "stale prewarm body", id = "reply-stale", channelId = "thread-m1")
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        val slowPrewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
                    fetchStarted.complete(Unit)
                    releaseStale.await()
                    DataResult.Content(stale)
                }
            }
        fetchStarted.await()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        withTimeout(10_000L) { store.accept(SharedThreadIntent.Start(route)) }
        assertEquals("reply body", store.state.value.payload.messages.single().body)
        assertTrue(logs.any { it == "thread_refresh_reuse_prewarm threadId=thread-m1 outcome=own_fetch reason=join_timeout" })

        // The detached prewarm now completes with OLDER content under the same generation.
        releaseStale.complete(Unit)
        slowPrewarm.await()

        assertEquals("reply body", dataSource.cachedPayload("thread-m1")?.messages?.single()?.body)
        assertNull(payloadStore.joinOrConsumePrewarm("server-1", "thread-m1", ownerScopeId))
        val nextScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val nextLogs = ConcurrentTestLogBuffer()
        val nextStore = SharedThreadStore(dataSource = dataSource, scope = nextScope, diagnosticsLogger = nextLogs::add)
        withTimeout(10_000L) { nextStore.accept(SharedThreadIntent.Start(route)) }
        assertEquals("reply body", nextStore.state.value.payload.messages.single().body)
        assertFalse(nextLogs.any { "outcome=consumed_ready" in it }, "the next open must not consume the stale prewarm")
        nextScope.cancel()
        scope.cancel()
        appScope.cancel()
    }
    @Test
    fun fetchThreadPayloadCarriesRepositoryJointChannelKind() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "joint-1",
                            body = "repository body",
                            conversation =
                                ConversationSummary(
                                    threadId = ThreadId("joint-1"),
                                    channelId = ChannelId("joint-1"),
                                    channelType = "joint",
                                    kind = ConversationKind.Joint,
                                    name = "partner"
                                )
                        )
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
        val base =
            basePayload().copy(
                sourceThreadId = "joint-1",
                streamChannelId = "joint-1",
                channelKind = "channel"
            )

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(listOf(ThreadId("joint-1")), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("joint", payload.channelKind)
        assertEquals(RaftLucideIconName.GitBranch, payload.channelKind.threadChannelHeaderIcon())
        assertEquals("repository body", payload.messages.single().body)
    }
    @Test
    fun fetchThreadPayloadUsesFocusedMessageContextWhenRouteCarriesFocusedMessage() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "latest body", id = "latest", seq = 30)),
            messageContextResult =
                DataResult.Content(
                    threadSnapshot(
                        messages =
                            listOf(
                                message(threadId = "channel-1", id = "older", seq = 18, body = "older body"),
                                message(threadId = "channel-1", id = "target", seq = 19, body = "target body"),
                                message(threadId = "channel-1", id = "newer", seq = 20, body = "newer body")
                            )
                    ).copy(
                        canLoadMoreBefore = true,
                        canLoadMoreAfter = true,
                        highlightedMessageId = MessageId("target")
                    )
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
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals(listOf("older", "target", "newer"), payload.messages.map { it.id })
        assertEquals("target", payload.focusedMessageId)
        assertTrue(payload.canLoadMoreBefore)
        assertTrue(payload.canLoadMoreAfter)
    }
}
