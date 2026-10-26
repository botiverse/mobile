package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.emptyMessagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.ConcurrentTestLogBuffer
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.activityReplyPayload
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.directMessagePayload
import build.raft.app.SharedThreadStoreTestFixtures.emptyHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.testReplyCacheIdentityAuthority
import build.raft.app.SharedThreadStoreTestFixtures.unresolvedReplyPayload
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class SharedThreadStoreStartAndOwnerSwitchTest {
    @Test
    fun storeInitialPayloadKeepsRouteIdentityBeforeStartDispatch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val routePayload =
            basePayload().copy(
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1"
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                initialPayload = routePayload
            )

        val payload = store.state.value.payload
        val identity = payload.resolveThreadRouteIdentity()
        assertEquals("channel-1", payload.sourceThreadId)
        assertEquals("channel-1", payload.streamChannelId)
        assertEquals("channel:channel-1", identity.instanceKey)
        assertEquals("channel-1", store.state.value.timelineSlice.sourceThreadId)
        scope.cancel()
    }
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
    fun viewModelEnsureStartedDispatchesStartExactlyOnce() = runTest {
        // task #387 follow-up: the page calls ensureStarted() during composition; recompositions
        // and the page's later effect must not double the load. The injected scope stands in for
        // the Kuikly page scope (unavailable in JUnit — it needs the pager id).
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
        val viewModel =
            SharedThreadViewModel(
                dataSource = dataSource,
                initialPayload = basePayload(),
                scopeOverride = scope
            )

        viewModel.ensureStarted()
        viewModel.ensureStarted()
        // Let the fire-and-forget store coroutine pass its first-frame delay and reach refresh.
        withTimeout(10_000) {
            while (api.channelMessageCalls < 1 || viewModel.state.value.payload.status != "content") delay(10)
        }

        val payload = viewModel.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("cached body", payload.messages.single().body)
        assertEquals(1, api.channelMessageCalls)
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
    fun ownerSwitchDuringFirstFrameAdmissionRetainsOldCacheWithoutPublishingIt() = runTest {
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
                selectionGeneration = 1L
            )
        val admissionStarted = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        val loadingApplied = CompletableDeferred<Unit>()
        val releaseLoading = CompletableDeferred<Unit>()
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
        assertTrue(
            dataSource.cachePayload(
                contentPayload("owner-a cached body"),
                serverId = "server-1",
                ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty(),
                expectedProvenance = ownerA
            )
        )
        dataSource.onReplyCacheFirstFrameAdmissionForTest = {
            admissionStarted.complete(Unit)
            releaseAdmission.await()
        }
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                afterRefreshLoadingStateApplied = {
                    loadingApplied.complete(Unit)
                    releaseLoading.await()
                }
            )

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            withTimeout(5_000) { admissionStarted.await() }
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
            releaseAdmission.complete(Unit)
            withTimeout(5_000) { loadingApplied.await() }

            val firstFrame = store.state.value
            assertEquals(ThreadAccessResolution.Loading, firstFrame.accessResolution)
            assertEquals("loading", firstFrame.timelineSlice.status)
            assertTrue(firstFrame.timelineSlice.messages.isEmpty())
            assertFalse(firstFrame.writeActionsEnabled)
            assertEquals(
                "owner-a cached body",
                payloadStore
                    .get("server-1", "channel-1", environmentId = ownerA.environmentId, principalId = ownerA.principalId, ownerScopeId = ownerA.threadPayloadOwnerScopeId.orEmpty())
                    ?.messages
                    ?.single()
                    ?.body
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
            releaseAdmission.complete(Unit)
            releaseLoading.complete(Unit)
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
    fun startRendersChannelShellWhenRealtimeInvalidatedFirstFrameCache() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val route = basePayload()
        dataSource.cachePayloadForTest(contentPayload("stale cached body"))
        dataSource.invalidateCachedPayload(route)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))

        val firstFrame = store.state.value.payload
        assertEquals("loading", firstFrame.status)
        assertEquals("channel-1", firstFrame.sourceThreadId)
        assertTrue(firstFrame.messages.isEmpty())
        assertFalse(firstFrame.shouldRenderThreadEarlyLoadingShell())
        assertEquals(0, api.channelMessageCalls)
        scope.cancel()
    }
    @Test
    fun startRendersCachedReplyParentContentBeforeReplyNetworkFetch() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val api = FakeThreadApi(bodyText = messagesResponse("reply body"))
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        dataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-1",
            parentChannelId = "channel-1",
            parentMessage = contentPayload("stored parent body").messages.single(),
            threadChannelId = "thread-m1"
        )
        val route = unresolvedReplyPayload(parentMessageId = "m1")
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))

        val firstFrame = store.state.value.payload
        assertEquals(RaftThreadSurfaceKind.ReplyThread, firstFrame.surfaceKind)
        assertEquals("loading", firstFrame.status)
        assertEquals("loaded", firstFrame.originStatus)
        assertEquals("stored parent body", firstFrame.originMessage?.body)
        assertEquals("m1", firstFrame.originParentMessageId)
        assertTrue(firstFrame.messages.isEmpty())
        assertFalse(firstFrame.shouldRenderThreadEarlyLoadingShell())
        assertEquals(0, api.channelMessageCalls)
        scope.cancel()
    }
    @Test
    fun activityReplyStartKeepsStoreParentWhenTimelineCacheStillMarksItMissing() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
        val api = FakeThreadApi(bodyText = messagesResponse("network reply"))
        val dataSource =
            SharedThreadDataSource(
                threadParseDispatcher = backgroundScope.testDispatcher(),
                payloadStore = SharedThreadPayloadStore(backgroundScope),
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                messageStore = messageStore,
                cacheEnvironmentIdProvider = { "https://prod.example.test" },
                replyCacheIdentityAuthority = testReplyCacheIdentityAuthority(
                    sessionProvider = { sampleSession() }
                )
            )
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        dataSource.rememberReplyParentCandidate(
            provenance = provenance,
            serverId = "server-1",
            parentChannelId = "channel-1",
            parentMessage = contentPayload("stored parent body").messages.single(),
            threadChannelId = "thread-m1"
        )
        val route =
            replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                .copy(focusedMessageId = "reply-1")
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(
                base = route,
                body = "cached focused reply",
                id = "reply-1",
                channelId = "thread-m1"
            ).copy(
                originMessage = null,
                originPreview = null,
                originStatus = "missing",
                originErrorCode = null
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))

        val firstState = store.state.value
        val firstFrame = firstState.payload
        assertEquals("cached focused reply", firstFrame.messages.single().body)
        assertEquals("loaded", firstFrame.originStatus)
        assertEquals("m1", firstFrame.originMessage?.id)
        assertEquals("stored parent body", firstFrame.originMessage?.body)
        assertEquals(ThreadAccessResolution.Loading, firstState.accessResolution)
        assertEquals(
            ThreadRouteMountPlan.FeatureSurfaces,
            firstFrame.threadRouteMountPlan(
                timelineSlice = firstState.timelineSlice,
                accessResolution = firstState.accessResolution
            )
        )
        assertFalse(
            firstFrame.hasRenderableContentForComposer(
                timelineSlice = firstState.timelineSlice,
                accessResolution = firstState.accessResolution
            )
        )
        assertEquals(0, api.channelMessageCalls)
        scope.cancel()
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
    fun sameRouteIntentDoesNotRestartOrImportOriginCandidateContent() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val originCommitStarted = CompletableDeferred<Unit>()
        val releaseOriginCommit = CompletableDeferred<Unit>()
        val originCommitFinished = CompletableDeferred<Unit>()
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = { message ->
                    if (message.startsWith("thread_focused_origin_result")) {
                        originCommitFinished.complete(Unit)
                    }
                },
                beforeFocusedOriginCommit = {
                    originCommitStarted.complete(Unit)
                    releaseOriginCommit.await()
                }
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")

        try {
            store.accept(SharedThreadIntent.Start(route))
            // Freeze the independent origin writer before applying the same-route intent. The
            // hook is test-only; production does not wait here. A failed origin lookup is allowed
            // to advance loading -> error/missing in production, so loading is not itself the
            // invariant. Holding this boundary lets the test inspect the route-intent projection
            // before that legitimate transition, then verify the transition below as well.
            withTimeout(5_000) { originCommitStarted.await() }
            val afterStart = store.state.value.payload
            assertEquals(1, api.channelMessageCalls)
            assertEquals("loading", afterStart.originStatus)

            val originCandidate =
                RaftThreadMessageItem(
                    id = "m1",
                    authorId = "route-author",
                    authorName = "Route",
                    body = "late route origin should stay out of state"
                )
            store.accept(
                SharedThreadIntent.ApplyRouteIntent(
                    route.copy(
                        selectedTab = "files",
                        focusedMessageId = "reply-1",
                        focusedMessageRequestNonce = 7,
                        originMessage = originCandidate,
                        originStatus = "loaded",
                        messages =
                            listOf(
                                RaftThreadMessageItem(
                                    id = "route-message",
                                    authorId = "route-author",
                                    authorName = "Route",
                                    body = "late route body should stay out of state"
                                )
                            )
                    )
                )
            )

            val payloadBeforeOriginCommit = store.state.value.payload
            assertEquals(1, api.channelMessageCalls)
            assertEquals("files", payloadBeforeOriginCommit.selectedTab)
            assertEquals("reply-1", payloadBeforeOriginCommit.focusedMessageId)
            assertEquals(7, payloadBeforeOriginCommit.focusedMessageRequestNonce)
            assertEquals(afterStart.messages, payloadBeforeOriginCommit.messages)
            assertNull(payloadBeforeOriginCommit.originMessage)
            assertEquals(afterStart.originStatus, payloadBeforeOriginCommit.originStatus)

            releaseOriginCommit.complete(Unit)
            withTimeout(5_000) { originCommitFinished.await() }
            val payloadAfterOriginCommit = store.state.value.payload
            assertTrue(payloadAfterOriginCommit.originStatus != "loading")
            assertFalse(payloadAfterOriginCommit.originMessage?.body == originCandidate.body)
            assertEquals("files", payloadAfterOriginCommit.selectedTab)
            assertEquals("reply-1", payloadAfterOriginCommit.focusedMessageId)
        } finally {
            releaseOriginCommit.complete(Unit)
            scope.cancel()
        }
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
    fun staleFocusedOriginWriterCannotOverwriteRepeatedActivityReplyFocus() = runTest {
        val externalJob = SupervisorJob()
        val scope = CoroutineScope(externalJob + StandardTestDispatcher(testScheduler))
        val staleCommitStarted = CompletableDeferred<Unit>()
        val freshCommitStarted = CompletableDeferred<Unit>()
        val releaseStaleCommit = CompletableDeferred<Unit>()
        val freshCommitFinished = CompletableDeferred<Unit>()
        val staleCommitFinished = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("reply body", id = "reply-2", channelId = "thread-m1")
            )
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = api,
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
                    ),
                scope = scope,
                diagnosticsLogger = { message ->
                    if (message.contains("thread_focused_origin_result")) {
                        if (!freshCommitFinished.complete(Unit)) {
                            staleCommitFinished.complete(Unit)
                        }
                    }
                },
                beforeFocusedOriginCommit = { payloadBeforeCommit ->
                    if (staleCommitStarted.complete(Unit)) {
                        assertEquals("reply-1", payloadBeforeCommit.focusedMessageId)
                        try {
                            releaseStaleCommit.await()
                        } catch (_: CancellationException) {
                            withContext(NonCancellable) { releaseStaleCommit.await() }
                        }
                    } else {
                        freshCommitStarted.complete(Unit)
                    }
                }
            )
        val firstRoute =
            replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                .copy(focusedMessageId = "reply-1")
        val secondRoute = firstRoute.copy(focusedMessageId = "reply-2")

        try {
            store.accept(SharedThreadIntent.Start(firstRoute))
            withTimeout(5_000) { staleCommitStarted.await() }

            withTimeout(5_000) { store.accept(SharedThreadIntent.Start(secondRoute)) }
            withTimeout(5_000) { freshCommitStarted.await() }
            withTimeout(5_000) { freshCommitFinished.await() }
            assertEquals("reply-2", store.state.value.payload.focusedMessageId)

            releaseStaleCommit.complete(Unit)
            withTimeout(5_000) { staleCommitFinished.await() }
            assertEquals("reply-2", store.state.value.payload.focusedMessageId)
        } finally {
            releaseStaleCommit.complete(Unit)
            externalJob.cancelAndJoin()
        }
    }
    @Test
    fun routeContextDoesNotOverrideCanonicalCacheMembershipState() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(
            contentPayload("cached body").copy(
                joined = false,
                canSend = false,
                membersCount = 7
            )
        )
        val routePayload =
            basePayload().copy(
                joined = true,
                canSend = true,
                membersCount = 99
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(routePayload))

        val payload = store.state.value.payload
        assertEquals("cached body", payload.messages.single().body)
        assertEquals(false, payload.joined)
        assertEquals(false, payload.canSend)
        assertEquals(7, payload.membersCount)
        scope.cancel()
    }
    @Test
    fun successfulFetchWritesPayloadCacheForNextStart() = runTest {
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

        assertEquals("network body", store.state.value.payload.messages.single().body)
        api.failChannelMessages = true
        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertEquals("network body", payload.messages.single().body)
        assertEquals(2, api.channelMessageCalls)
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
    fun freshDirectMessageEmptyResolvesBeforeDelayedHomeProjection() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                homeRequested.complete(Unit)
                releaseHome.await()
                DataResult.Content(emptyHomeSnapshot())
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Empty(EmptyReason.NoMessages)
                    ),
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val accessHistory = mutableListOf<ThreadAccessResolution>()
        val accessCollector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                store.state
                    .map { it.accessResolution }
                    .distinctUntilChanged()
                    .collect(accessHistory::add)
            }

        store.dispatch(SharedThreadIntent.Start(directMessagePayload()))
        homeRequested.await()
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedBeforeHome = store.state.value
        assertEquals("empty", resolvedBeforeHome.payload.status)
        assertEquals(null, resolvedBeforeHome.payload.errorCode)
        assertEquals(ThreadAccessResolution.Resolved, resolvedBeforeHome.accessResolution)
        assertTrue(resolvedBeforeHome.canonicalAccessResolved)
        assertFalse(resolvedBeforeHome.initialHomeAccessLookupComplete)
        assertEquals(null, resolvedBeforeHome.initialHomeAccessCandidate)

        releaseHome.complete(Unit)
        withTimeout(10_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
        yield()
        assertTrue(accessHistory.isNotEmpty())
        assertEquals(ThreadAccessResolution.Loading, accessHistory.first())
        assertEquals(ThreadAccessResolution.Resolved, accessHistory.last())
        assertFalse(accessHistory.contains(ThreadAccessResolution.Error))
        assertFalse(accessHistory.contains(ThreadAccessResolution.NoPermission))
        accessCollector.cancel()
        scope.cancel()
    }
    @Test
    fun freshDirectMessageEmptyStaysLoadingWhenHomeCompletesFirstWithoutCandidate() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val conversationRepository =
            FakeConversationRepository(
                threadResult = DataResult.Empty(EmptyReason.NoMessages)
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = conversationRepository,
                channelRepository = FakeChannelRepository { DataResult.Content(emptyHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val accessHistory = mutableListOf<ThreadAccessResolution>()
        val accessCollector =
            launch(start = CoroutineStart.UNDISPATCHED) {
                store.state
                    .map { it.accessResolution }
                    .distinctUntilChanged()
                    .collect(accessHistory::add)
            }

        store.dispatch(SharedThreadIntent.Start(directMessagePayload()))
        conversationRepository.getThreadStarted?.await()
        withTimeout(10_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }

        val homeFirst = store.state.value
        assertTrue(homeFirst.initialHomeAccessLookupComplete)
        assertEquals(null, homeFirst.initialHomeAccessCandidate)
        assertFalse(homeFirst.canonicalAccessResolved)
        assertEquals(ThreadAccessResolution.Loading, homeFirst.accessResolution)
        yield()
        assertTrue(accessHistory.isNotEmpty())
        assertTrue(accessHistory.all { it == ThreadAccessResolution.Loading })

        conversationRepository.releaseGetThread?.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolved = store.state.value
        assertEquals("empty", resolved.payload.status)
        assertTrue(resolved.canonicalAccessResolved)
        assertTrue(resolved.initialHomeAccessLookupComplete)
        assertEquals(null, resolved.initialHomeAccessCandidate)
        assertEquals(ThreadAccessResolution.Resolved, resolved.accessResolution)
        yield()
        assertEquals(ThreadAccessResolution.Loading, accessHistory.first())
        assertEquals(ThreadAccessResolution.Resolved, accessHistory.last())
        assertFalse(accessHistory.contains(ThreadAccessResolution.Error))
        assertFalse(accessHistory.contains(ThreadAccessResolution.NoPermission))
        accessCollector.cancel()
        scope.cancel()
    }
    @Test
    fun existingChannelEmptyResolvesWithCanonicalEmptyAndHomeCandidate() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Empty(EmptyReason.NoMessages)
                    ),
                channelRepository = FakeChannelRepository { DataResult.Content(joinedHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolved = store.state.value
        assertEquals("empty", resolved.payload.status)
        assertEquals(ThreadAccessResolution.Resolved, resolved.accessResolution)
        assertTrue(resolved.canonicalAccessResolved)
        assertTrue(resolved.initialHomeAccessLookupComplete)
        assertTrue(resolved.initialHomeAccessCandidate?.ids.orEmpty().contains("channel-1"))
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
    fun emptyReplyThreadStillFailsClosedWithoutParentAccessCandidate() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route =
            activityReplyPayload(
                parentKind = ConversationKind.Channel,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                threadId = "thread-m1"
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Empty(EmptyReason.NoMessages),
                        createThreadResult = DataResult.Error(ScreenError("missing_thread_identity"))
                    ),
                channelRepository = FakeChannelRepository { DataResult.Content(emptyHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Error) {
                delay(10)
            }
        }

        val failed = store.state.value
        assertEquals(RaftThreadSurfaceKind.ReplyThread, failed.payload.surfaceKind)
        assertEquals("empty", failed.payload.status)
        assertTrue(failed.canonicalAccessResolved)
        assertTrue(failed.initialHomeAccessLookupComplete)
        assertEquals(null, failed.initialHomeAccessCandidate)
        assertFalse(failed.writeActionsEnabled)
        assertFalse(failed.payload.joined)
        assertFalse(failed.payload.canSend)
        assertFalse(failed.payload.composer.enabled)
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
