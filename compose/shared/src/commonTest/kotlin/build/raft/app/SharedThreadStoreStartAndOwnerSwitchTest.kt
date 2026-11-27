package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.emptyMessagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.ConcurrentTestLogBuffer
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.directMessagePayload
import build.raft.app.SharedThreadStoreTestFixtures.emptyHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.unresolvedReplyPayload
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.RaftThreadSettingsAction
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadMessageStore
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.SharedThreadState
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.ThreadRouteLoadingPlaceholderTitle
import build.raft.app.thread.ThreadRouteMountPlan
import build.raft.app.thread.dispatch
import build.raft.app.thread.hasRenderableContentForComposer
import build.raft.app.thread.threadRouteMountPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedThreadStoreStartAndOwnerSwitchTest {
    @Test
    fun unresolvedReplyStartDoesNotRenderPreviousResolvedReplyTimeline() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val resolvedRoute = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(
                base = resolvedRoute,
                body = "old resolved reply body",
                id = "reply-old",
                channelId = "thread-m1"
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(resolvedRoute))
        assertEquals("old resolved reply body", store.state.value.payload.messages.single().body)

        store.accept(SharedThreadIntent.Start(unresolvedReplyPayload(parentMessageId = "m1")))

        val payload = store.state.value.payload
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertTrue(payload.messages.isEmpty())
        scope.cancel()
    }
    @Test
    fun cachedThreadPayloadSurvivesVisibleFetchFailure() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("cached body", payload.messages.single().body)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }
    @Test
    fun sameServerAccountSwitchShowsLoadingShellInsteadOfPreviousOwnersCachedTimeline() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
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
        val loadingStateApplied = CompletableDeferred<RaftThreadScreenPayload>()
        val releaseLoadingState = CompletableDeferred<Unit>()
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
        assertNotNull(dataSource.readSession())
        assertTrue(dataSource.cachePayload(contentPayload("owner-a cached body"), serverId = "server-1"))
        identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "u-b",
                serverId = "server-1",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                afterRefreshLoadingStateApplied = { payload ->
                    loadingStateApplied.complete(payload)
                    releaseLoadingState.await()
                }
            )

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val loadingPayload = loadingStateApplied.await()
            val firstState = store.state.value

            assertEquals("loading", loadingPayload.status)
            assertTrue(loadingPayload.messages.isEmpty())
            assertEquals(ThreadAccessResolution.Loading, firstState.accessResolution)
            assertEquals("loading", firstState.timelineSlice.status)
            assertTrue(firstState.timelineSlice.messages.isEmpty())
            assertEquals(
                ThreadRouteMountPlan.LoadingShell,
                firstState.payload.threadRouteMountPlan(
                    timelineSlice = firstState.timelineSlice,
                    accessResolution = firstState.accessResolution
                )
            )
            assertFalse(
                firstState.payload.hasRenderableContentForComposer(
                    timelineSlice = firstState.timelineSlice,
                    accessResolution = firstState.accessResolution
                )
            )
        } finally {
            releaseLoadingState.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }
    @Test
    fun authGenerationAdvanceDuringFetchStillDeliversContent() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(scope, prewarmStartIntervalMillis = 0L)
        val ownerASession =
            sampleSession().copy(
                user = StoredAuthUser(id = "u-a", email = "a@example.test", displayName = "Owner A"),
                accessToken = "access-a",
                refreshToken = "refresh-a"
            )
        var identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerASession,
                environmentId = "https://prod.example.test",
                principalId = "u-a",
                serverId = "server-1",
                authGeneration = 1L,
                selectionGeneration = 1L,
                bindingGeneration = 1L
            )
        val api = FakeThreadApi()
        api.channelMessagesStarted = CompletableDeferred()
        api.releaseChannelMessages = CompletableDeferred()
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                messageStore = SharedThreadMessageStore(scope = backgroundScope),
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                replyCacheIdentityAuthority = { identity },
                payloadStore = payloadStore
            )
        val ownerA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            withTimeout(5_000) { api.channelMessagesStarted!!.await() }
            // Same principal/server, only the token rotated -> authGeneration advanced.
            identity =
                ThreadReplyCacheIdentitySnapshot(
                    session =
                        ownerASession.copy(
                            accessToken = "access-a2",
                            refreshToken = "refresh-a2"
                        ),
                    environmentId = "https://prod.example.test",
                    principalId = "u-a",
                    serverId = "server-1",
                    authGeneration = 2L,
                    selectionGeneration = 1L,
                    bindingGeneration = 1L
                )
            val ownerARotated =
                requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
            api.releaseChannelMessages!!.complete(Unit)

            withTimeout(5_000) {
                while (store.state.value.payload.status != "content") {
                    delay(10)
                }
            }
            assertEquals(
                "network body",
                store.state.value.payload.messages.single().body
            )
            // Data-layer plan step 2: a token rotation keeps the same timeline-cache key.
            assertEquals(ownerA.threadPayloadOwnerScopeId, ownerARotated.threadPayloadOwnerScopeId)
            val cached =
                payloadStore.get(
                    "server-1",
                    "channel-1",
                    environmentId = ownerARotated.environmentId,
                    principalId = ownerARotated.principalId,
                    ownerScopeId = ownerARotated.threadPayloadOwnerScopeId.orEmpty()
                )
            assertEquals("network body", cached?.messages?.single()?.body)
        } finally {
            api.releaseChannelMessages?.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }
    @Test
    fun ownerSwitchDuringFetchDropsResult() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
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
                selectionGeneration = 1L,
                bindingGeneration = 1L
            )
        val api = FakeThreadApi()
        api.channelMessagesStarted = CompletableDeferred()
        api.releaseChannelMessages = CompletableDeferred()
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                messageStore = SharedThreadMessageStore(scope = backgroundScope),
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(ownerASession),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                replyCacheIdentityAuthority = { identity },
                payloadStore = payloadStore
            )
        val ownerA = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val diagnostics = ConcurrentTestLogBuffer()
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = diagnostics::add
            )

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            withTimeout(5_000) { api.channelMessagesStarted!!.await() }
            identity =
                ThreadReplyCacheIdentitySnapshot(
                    session = ownerBSession,
                    environmentId = "https://prod.example.test",
                    principalId = "u-b",
                    serverId = "server-1",
                    authGeneration = 2L,
                    selectionGeneration = 1L,
                    bindingGeneration = 2L
                )
            val ownerB = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
            api.releaseChannelMessages!!.complete(Unit)

            // The fetch result must be dropped post-transport: payload stays on the
            // loading shell and nothing is persisted for either owner scope.
            // Deterministic completion signal: dropForOwnerChange emits the
            // `reason=owner_changed` diagnostic synchronously inside the refresh
            // coroutine, so observing that line proves adjudication has run.
            withTimeout(5_000) {
                while (!diagnostics.any { it.contains("reason=owner_changed") }) {
                    delay(5)
                }
            }
            assertEquals("loading", store.state.value.payload.status)
            assertTrue(store.state.value.payload.messages.isEmpty())
            assertNull(
                payloadStore.get(
                    "server-1",
                    "channel-1",
                    environmentId = ownerA.environmentId,
                    principalId = ownerA.principalId,
                    ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty()
                )
            )
            assertNull(
                payloadStore.get(
                    "server-1",
                    "channel-1",
                    environmentId = ownerB.environmentId,
                    principalId = ownerB.principalId,
                    ownerScopeId = ownerB.threadPayloadOwnerScopeId.orEmpty()
                )
            )
        } finally {
            api.releaseChannelMessages?.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }
    @Test
    fun startDoesNotRenderOrCacheContentFromRoutePayload() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val routePayload =
            contentPayload("stale route body").copy(
                status = "content",
                members = listOf(RaftThreadMemberItem(id = "route-member", displayName = "Route member")),
                settingsActions = listOf(RaftThreadSettingsAction(id = "route-action"))
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val observedPayloads = mutableListOf<RaftThreadScreenPayload>()
        val observer = scope.launch {
            store.state.collect { state -> observedPayloads += state.payload }
        }

        store.accept(SharedThreadIntent.Start(routePayload))

        val payload = store.state.value.payload
        assertFalse(observedPayloads.any { state -> state.messages.any { it.body == "stale route body" } })
        assertFalse(observedPayloads.any { state -> state.members.any { it.id == "route-member" } })
        assertFalse(observedPayloads.any { state -> state.settingsActions.any { it.id == "route-action" } })
        assertEquals("network body", payload.messages.single().body)
        api.failChannelMessages = true
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals("network body", store.state.value.payload.messages.single().body)
        observer.cancel()
        scope.cancel()
    }
    @Test
    fun repeatedActivityReplyOpenUpdatesFocusedMessageForSameThread() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("reply body", id = "reply-2", channelId = "thread-m1")
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val firstRoute = replyPayload(parentMessageId = "m1", threadId = "thread-m1").copy(focusedMessageId = "reply-1")
        val secondRoute = firstRoute.copy(focusedMessageId = "reply-2")

        store.accept(SharedThreadIntent.Start(firstRoute))
        store.accept(SharedThreadIntent.Start(secondRoute))

        assertEquals("reply-2", store.state.value.payload.focusedMessageId)
        assertEquals(listOf("thread-m1", "thread-m1"), api.channelIds)
        scope.cancel()
    }
    @Test
    fun emptyDirectMessageFetchRendersEmptyStateInsteadOfError() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(bodyText = emptyMessagesResponse())
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        val payload = store.state.value.payload
        assertEquals("empty", payload.status)
        assertEquals(null, payload.errorCode)
        assertEquals(emptyList(), payload.messages)
        assertEquals("No messages yet", payload.emptyTitle)
        assertEquals("This conversation has no messages yet.", payload.emptyDetail)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
        assertTrue(store.state.value.canonicalAccessResolved)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }
    @Test
    fun emptyChannelFromLaunchPlaceholderShowsNoMessagesNotLoading() = runTest {
        // task #326: a channel that resolves to Content with zero messages (in the
        // joined home directory) must not keep the route placeholder's "Loading..."
        // as its empty-state title — otherwise the empty channel shows 「加载中」 forever.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
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
                                threadSnapshot(messages = emptyList(), conversation = conversationSummary())
                            )
                    ),
                channelRepository = FakeChannelRepository { DataResult.Content(joinedHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(
            SharedThreadIntent.Start(
                basePayload().copy(
                    status = "loading",
                    emptyTitle = ThreadRouteLoadingPlaceholderTitle,
                    emptyDetail = ""
                )
            )
        )
        withTimeout(10_000) {
            while (store.state.value.payload.status == "loading") {
                delay(10)
            }
        }

        val resolved = store.state.value.payload
        assertEquals("empty", resolved.status)
        assertEquals("No messages yet", resolved.emptyTitle)
        assertEquals("This conversation has no messages yet.", resolved.emptyDetail)
        scope.cancel()
    }
    @Test
    fun noPermissionAndErrorRemainFailClosedForDirectMessageAccess() = runTest {
        suspend fun resolve(result: DataResult<ThreadSnapshot>): SharedThreadState {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val dataSource =
                testSharedThreadDataSource(
                    storeScope = backgroundScope,
                    networkApi = FakeThreadApi(failChannelMessages = true),
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    conversationRepository = FakeConversationRepository(threadResult = result),
                    channelRepository = FakeChannelRepository { DataResult.Content(emptyHomeSnapshot()) }
                )
            val store = SharedThreadStore(dataSource = dataSource, scope = scope)
            store.dispatch(SharedThreadIntent.Start(directMessagePayload()))
            withTimeout(10_000) {
                while (
                    store.state.value.accessResolution != ThreadAccessResolution.NoPermission &&
                    store.state.value.accessResolution != ThreadAccessResolution.Error
                ) {
                    delay(10)
                }
            }
            return store.state.value.also { scope.cancel() }
        }

        val noPermission =
            resolve(DataResult.NoPermission(PermissionBlocker(code = "thread_forbidden")))
        assertEquals(ThreadAccessResolution.NoPermission, noPermission.accessResolution)
        assertFalse(noPermission.canonicalAccessResolved)
        assertEquals("no_permission", noPermission.payload.status)
        assertEquals(null, noPermission.payload.errorServerMessage)
        assertFalse(noPermission.writeActionsEnabled)
        assertFalse(noPermission.payload.joined)
        assertFalse(noPermission.payload.canSend)
        assertFalse(noPermission.payload.composer.enabled)

        val edgeBlocked =
            resolve(
                DataResult.NoPermission(
                    PermissionBlocker(code = "thread_forbidden", serverMessage = "edge_blocked")
                )
            )
        assertEquals(ThreadAccessResolution.NoPermission, edgeBlocked.accessResolution)
        assertEquals("no_permission", edgeBlocked.payload.status)
        assertEquals("edge_blocked", edgeBlocked.payload.errorServerMessage)
        assertFalse(edgeBlocked.writeActionsEnabled)

        val error = resolve(DataResult.Error(ScreenError(code = "offline", retryable = true)))
        assertEquals(ThreadAccessResolution.Error, error.accessResolution)
        assertFalse(error.canonicalAccessResolved)
        assertEquals("error", error.payload.status)
        assertFalse(error.writeActionsEnabled)
        assertFalse(error.payload.joined)
        assertFalse(error.payload.canSend)
        assertFalse(error.payload.composer.enabled)
    }
}
