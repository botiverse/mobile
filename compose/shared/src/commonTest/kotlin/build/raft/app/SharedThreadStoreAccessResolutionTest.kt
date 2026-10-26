package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.activityReplyPayload
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyThreadConversation
import build.raft.app.SharedThreadStoreTestFixtures.unjoinedHomeSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.toHomeChannelRow
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedThreadStoreAccessResolutionTest {
    @Test
    fun cachedThreadRefreshExposesBackgroundRefreshingUntilNetworkCompletes() = runTest {
        repeat(25) { iteration ->
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(scopeJob + UnconfinedTestDispatcher(testScheduler))
            val loadingStateApplied = CompletableDeferred<RaftThreadScreenPayload>()
            val releaseLoadingState = CompletableDeferred<Unit>()
            val networkStarted = CompletableDeferred<Unit>()
            val releaseNetwork = CompletableDeferred<Unit>()
            val api =
                FakeThreadApi(bodyText = messagesResponse("network body $iteration")).apply {
                    channelMessagesStarted = networkStarted
                    releaseChannelMessages = releaseNetwork
                }
            val dataSource =
                testSharedThreadDataSource(
                    storeScope = backgroundScope,
                    networkApi = api,
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
                )
            dataSource.cachePayloadForTest(contentPayload("cached body $iteration"))
            val store =
                SharedThreadStore(
                    dataSource = dataSource,
                    scope = scope,
                    afterRefreshLoadingStateApplied = { payload ->
                        assertTrue(
                            loadingStateApplied.complete(payload),
                            "iteration=$iteration loading state must be applied exactly once"
                        )
                        releaseLoadingState.await()
                    }
                )

            try {
                store.dispatch(SharedThreadIntent.Start(basePayload()))

                val committed = loadingStateApplied.await()
                assertEquals(0, api.channelMessageCalls, "iteration=$iteration must gate before fetch")
                assertEquals("content", committed.status, "iteration=$iteration")
                assertEquals("cached body $iteration", committed.messages.single().body, "iteration=$iteration")
                assertTrue(committed.backgroundRefreshing, "iteration=$iteration")

                releaseLoadingState.complete(Unit)
                networkStarted.await()
                val inFlight = store.state.value.payload
                assertEquals(1, api.channelMessageCalls, "iteration=$iteration")
                assertEquals("cached body $iteration", inFlight.messages.single().body, "iteration=$iteration")
                assertTrue(inFlight.backgroundRefreshing, "iteration=$iteration")

                releaseNetwork.complete(Unit)
                val completed =
                    store.state
                        .map { state -> state.payload }
                        .first { payload ->
                            !payload.backgroundRefreshing &&
                                payload.messages.singleOrNull()?.body == "network body $iteration"
                        }
                assertFalse(completed.backgroundRefreshing, "iteration=$iteration")
                assertEquals("network body $iteration", completed.messages.single().body, "iteration=$iteration")
                assertEquals(1, api.channelMessageCalls, "iteration=$iteration must fetch exactly once")
            } finally {
                releaseLoadingState.complete(Unit)
                releaseNetwork.complete(Unit)
                scopeJob.cancelAndJoin()
            }
        }
    }
    @Test
    fun warmChannelCacheResolvesFooterFromHomeCandidateBeforeCanonicalFetch() = runTest {
        // Task #1141: on a Channel route with renderable cached content, the Home directory
        // candidate (joined + canSend) is the write authority; the footer must resolve when the
        // directory answers, not after the first network refresh. Home releases first, the
        // canonical fetch stays held.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "network body",
                            conversation = conversationSummary().copy(joined = true)
                        )
                    )
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
            }
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                homeRequested.complete(Unit)
                releaseHome.await()
                DataResult.Content(joinedHomeSnapshot())
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
        )
        // A real memory-cached payload was written from a canonical snapshot, which already
        // carried the composer enabled for a joined + canSend conversation; model that here.
        val cached = contentPayload("cached body")
        dataSource.cachePayloadForTest(
            cached.copy(joined = true, canSend = true, composer = cached.composer.copy(enabled = true))
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        repository.getThreadStarted?.await()
        homeRequested.await()

        val cachedFrame = store.state.value
        assertEquals("content", cachedFrame.payload.status)
        assertEquals(ThreadAccessResolution.Loading, cachedFrame.accessResolution)
        assertFalse(cachedFrame.writeActionsEnabled)

        releaseHome.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }
        val homeResolved = store.state.value
        assertFalse(homeResolved.canonicalAccessResolved)
        assertEquals(ThreadAccessResolution.Resolved, homeResolved.accessResolution)
        assertTrue(homeResolved.writeActionsEnabled)
        assertTrue(homeResolved.payload.joined)
        assertTrue(homeResolved.payload.canSend)
        assertTrue(homeResolved.payload.composer.enabled)
        assertTrue(
            homeResolved.payload.isThreadPrimaryFooterReady(
                timelineSlice = homeResolved.timelineSlice,
                accessResolution = homeResolved.accessResolution
            )
        )

        repository.releaseGetThread?.complete(Unit)
        // The canonical body and the canonicalAccessResolved flag land in separate reduces;
        // wait for the flag, then read one frame.
        withTimeout(10_000) {
            while (!store.state.value.canonicalAccessResolved) {
                delay(10)
            }
        }
        val canonicalFrame = store.state.value
        assertEquals("network body", canonicalFrame.payload.messages.singleOrNull()?.body)
        assertEquals(ThreadAccessResolution.Resolved, canonicalFrame.accessResolution)
        assertTrue(canonicalFrame.writeActionsEnabled)
        scope.cancel()
    }
    @Test
    fun warmChannelCacheKeepsFooterUnresolvedUntilInitialLookupCompletes() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "network body",
                            conversation = conversationSummary().copy(joined = true)
                        )
                    )
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
            }
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                homeRequested.complete(Unit)
                releaseHome.await()
                DataResult.Content(unjoinedHomeSnapshot())
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
        )
        dataSource.cachePayloadForTest(contentPayload("cached body").copy(joined = true, canSend = true))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        repository.getThreadStarted?.await()
        homeRequested.await()
        store.accept(
            SharedThreadIntent.ApplyRouteIntent(
                basePayload().copy(
                    selectedTab = "files",
                    focusedMessageId = "m1",
                    focusedMessageRequestNonce = 7
                )
            )
        )

        val cachedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Loading, cachedFrame.accessResolution)
        assertEquals("content", cachedFrame.payload.status)
        assertTrue(cachedFrame.payload.joined)
        assertFalse(
            cachedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = cachedFrame.timelineSlice,
                accessResolution = cachedFrame.accessResolution
            )
        )

        repository.releaseGetThread?.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.payload.messages.singleOrNull()?.body != "network body") {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        releaseHome.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, resolvedFrame.accessResolution)
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertTrue(resolvedFrame.writeActionsEnabled)
        assertEquals("files", resolvedFrame.payload.selectedTab)
        assertEquals("m1", resolvedFrame.payload.focusedMessageId)
        assertEquals(7, resolvedFrame.payload.focusedMessageRequestNonce)
        assertEquals(1, channelRepository.homeCalls)
        assertTrue(
            resolvedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = resolvedFrame.timelineSlice,
                accessResolution = resolvedFrame.accessResolution
            )
        )
        scope.cancel()
    }
    @Test
    fun warmReplyThreadCacheUsesJoinedParentAccessWhenCanonicalCompletesFirst() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val canonicalConversation = replyThreadConversation(joined = false, canSend = false)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            conversation = canonicalConversation
                        )
                    )
            ).apply {
                getReplyThreadStarted = CompletableDeferred()
                releaseGetReplyThread = CompletableDeferred()
            }
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                homeRequested.complete(Unit)
                releaseHome.await()
                DataResult.Content(joinedHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply").copy(
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false)
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(route))
        repository.getReplyThreadStarted?.await()
        homeRequested.await()

        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(
            store.state.value.payload.isThreadPrimaryFooterReady(
                timelineSlice = store.state.value.timelineSlice,
                accessResolution = store.state.value.accessResolution
            )
        )

        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(10_000) {
            while (!store.state.value.canonicalAccessResolved) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        releaseHome.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, resolvedFrame.accessResolution)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, resolvedFrame.payload.surfaceKind)
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertTrue(resolvedFrame.writeActionsEnabled)
        assertEquals(1, channelRepository.channelsCalls)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && !slice.payload.joined
            }
        )
        assertTrue(
            resolvedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = resolvedFrame.timelineSlice,
                accessResolution = resolvedFrame.accessResolution
            )
        )
        renderJob.cancel()
        scope.cancel()
    }
    @Test
    fun warmReplyThreadCacheUsesJoinedParentAccessWhenHomeCompletesFirst() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val canonicalConversation = replyThreadConversation(joined = false, canSend = false)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            conversation = canonicalConversation
                        )
                    )
            ).apply {
                getReplyThreadStarted = CompletableDeferred()
                releaseGetReplyThread = CompletableDeferred()
            }
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(joinedHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply").copy(
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false)
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(route))
        repository.getReplyThreadStarted?.await()
        withTimeout(10_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }

        // Hands cd41d823: a positive parent candidate (joined + canSend) is the only
        // write authority the reply surface ever consults — the canonical reply stream
        // does not attest it. With renderable cached content the footer must therefore
        // resolve when the directory answers, not after the held canonical fetch.
        val homeFirstFrame = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, homeFirstFrame.accessResolution)
        assertTrue(homeFirstFrame.payload.joined)
        assertTrue(homeFirstFrame.payload.canSend)
        assertTrue(homeFirstFrame.payload.composer.enabled)
        assertTrue(homeFirstFrame.writeActionsEnabled)
        assertTrue(
            homeFirstFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = homeFirstFrame.timelineSlice,
                accessResolution = homeFirstFrame.accessResolution
            )
        )

        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertEquals(1, channelRepository.channelsCalls)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && !slice.payload.joined
            }
        )
        assertTrue(
            resolvedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = resolvedFrame.timelineSlice,
                accessResolution = resolvedFrame.accessResolution
            )
        )
        renderJob.cancel()
        scope.cancel()
    }
    @Test
    fun warmReplyThreadCacheUsesUnjoinedParentAccessOverCanonicalThreadAccess() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val canonicalConversation = replyThreadConversation(joined = true, canSend = true)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            conversation = canonicalConversation
                        )
                    )
            )
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                releaseHome.await()
                DataResult.Content(unjoinedHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply").copy(joined = true, canSend = true)
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(10_000) {
            while (!store.state.value.canonicalAccessResolved) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        releaseHome.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(RaftThreadSurfaceKind.ReplyThread, resolvedFrame.payload.surfaceKind)
        assertFalse(resolvedFrame.payload.joined)
        assertFalse(resolvedFrame.payload.canSend)
        assertFalse(resolvedFrame.payload.composer.enabled)
        assertTrue(resolvedFrame.writeActionsEnabled)
        assertEquals(1, channelRepository.channelsCalls)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && slice.payload.joined
            }
        )
        assertTrue(
            resolvedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = resolvedFrame.timelineSlice,
                accessResolution = resolvedFrame.accessResolution
            )
        )
        renderJob.cancel()
        scope.cancel()
    }
    @Test
    fun warmReplyThreadCacheUsesUnjoinedParentAccessWhenHomeCompletesFirst() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            conversation = replyThreadConversation(joined = true, canSend = true)
                        )
                    )
            ).apply {
                getReplyThreadStarted = CompletableDeferred()
                releaseGetReplyThread = CompletableDeferred()
            }
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(unjoinedHomeSnapshotWithConflictingReplyChild())
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply").copy(joined = true, canSend = true)
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(route))
        repository.getReplyThreadStarted?.await()
        withTimeout(10_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(store.state.value.payload.joined)
        assertFalse(
            store.state.value.payload.isThreadPrimaryFooterReady(
                timelineSlice = store.state.value.timelineSlice,
                accessResolution = store.state.value.accessResolution
            )
        )

        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertFalse(resolvedFrame.payload.joined)
        assertFalse(resolvedFrame.payload.canSend)
        assertFalse(resolvedFrame.payload.composer.enabled)
        assertTrue(resolvedFrame.writeActionsEnabled)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && slice.payload.joined
            }
        )
        renderJob.cancel()
        scope.cancel()
    }
    private fun joinedHomeSnapshotWithConflictingReplyChild(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(joined = false, canSend = false).toHomeChannelRow(),
                    conversationSummary().copy(
                        joined = true,
                        permissions = ConversationPermissions(canSend = true)
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )
    private fun unjoinedHomeSnapshotWithConflictingReplyChild(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(joined = true, canSend = true).toHomeChannelRow(),
                    conversationSummary().copy(
                        joined = false,
                        permissions = ConversationPermissions(canSend = false)
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )
}
