package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.activityReplyPayload
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.emptyHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyThreadConversation
import build.raft.app.SharedThreadStoreTestFixtures.unjoinedHomeSnapshot
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedThreadStoreReplyParentAccessTest {
    @Test
    fun activityReplyThreadUsesJoinedDirectMessageParentAccess() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = activityReplyPayload(
            parentKind = ConversationKind.DirectMessage,
            parentChannelId = "dm-1",
            parentMessageId = "dm-parent-message",
            threadId = "dm-thread-1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "dm-thread-1",
                            body = "network dm reply",
                            conversation =
                                replyThreadConversation(
                                    threadId = "dm-thread-1",
                                    parentChannelId = "dm-1",
                                    parentMessageId = "dm-parent-message",
                                    joined = false,
                                    canSend = false
                                )
                        )
                    )
            )
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(joinedDmHomeSnapshotWithConflictingReplyChild())
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
            contentPayloadForRoute(route, "cached dm reply", channelId = "dm-thread-1").copy(
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false)
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(RaftThreadSurfaceKind.DirectMessage, resolvedFrame.payload.parentSurfaceKind)
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertEquals("dm-1", resolvedFrame.payload.parentChannelId)
        assertEquals(1, channelRepository.directMessageCalls)
        scope.cancel()
    }
    @Test
    fun activityReplyThreadUsesCanonicalHomeStoreSnapshotWithoutDuplicateHomeRequest() = runTest {
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
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    )
            )
        val channelRepository = FakeChannelRepository { DataResult.Error(ScreenError("unexpected_home_request")) }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository,
                homeSnapshotProvider = { joinedHomeSnapshot() }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        assertEquals(0, channelRepository.homeCalls)
        assertEquals(1, repository.getReplyThreadRequests.size)
        assertEquals(RaftThreadSurfaceKind.Channel, store.state.value.payload.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, store.state.value.payload.parentChannelKind)
        assertTrue(store.state.value.payload.joined)
        assertTrue(store.state.value.payload.composer.enabled)
        scope.cancel()
    }
    @Test
    fun activityReplyThreadUsesJoinedJointParentAccess() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = activityReplyPayload(
            parentKind = ConversationKind.Joint,
            parentChannelId = "joint-1",
            parentMessageId = "joint-parent-message",
            threadId = "joint-thread-1"
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "joint-thread-1",
                            body = "network joint reply",
                            conversation =
                                replyThreadConversation(
                                    threadId = "joint-thread-1",
                                    parentChannelId = "joint-1",
                                    parentMessageId = "joint-parent-message",
                                    joined = false,
                                    canSend = false
                                )
                        )
                    )
            )
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(joinedJointHomeSnapshotWithConflictingReplyChild())
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
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertEquals(RaftThreadSurfaceKind.Channel, resolvedFrame.payload.parentSurfaceKind)
        assertEquals(JointChannelKindWireValue, resolvedFrame.payload.parentChannelKind)
        assertEquals(ConversationKind.Joint, resolvedFrame.payload.resolveThreadRouteIdentity().parentConversationKind)
        assertTrue(resolvedFrame.payload.joined)
        assertTrue(resolvedFrame.payload.canSend)
        assertTrue(resolvedFrame.payload.composer.enabled)
        assertEquals("joint-1", resolvedFrame.payload.parentChannelId)
        assertEquals(1, channelRepository.channelsCalls)
        scope.cancel()
    }
    @Test
    fun activityReplyThreadKeepsCanonicalRepliesReadableWhenParentAccessCandidateIsMissing() = runTest {
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
                            id = "reply-1",
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    )
            )
        val channelRepository =
            FakeChannelRepository {
                DataResult.Content(replyChildOnlyHomeSnapshot(joined = true, canSend = true))
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
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(10_000) {
            while (
                store.state.value.accessResolution != ThreadAccessResolution.Resolved ||
                store.state.value.writeActionsEnabled
            ) {
                delay(10)
            }
        }

        val degradedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, degradedFrame.accessResolution)
        assertFalse(degradedFrame.accessResolution.shouldRenderThreadAccessRetry())
        assertEquals(listOf("network reply"), degradedFrame.payload.messages.map { it.body })
        assertFalse(degradedFrame.writeActionsEnabled)
        assertFalse(degradedFrame.payload.joined)
        assertFalse(degradedFrame.payload.canSend)
        assertFalse(degradedFrame.payload.composer.enabled)
        assertTrue(
            degradedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = degradedFrame.timelineSlice,
                accessResolution = degradedFrame.accessResolution
            )
        )
        assertFalse(
            shouldShowThreadComposerForReadyContent(
                joined = degradedFrame.payload.joined,
                showComposer = degradedFrame.payload.showComposer,
                selectedTab = degradedFrame.payload.selectedTab
            )
        )
        assertEquals(
            null,
            degradedFrame.payload.resolvedTaskLookupChannelId(
                writeActionsEnabled = degradedFrame.writeActionsEnabled
            )
        )
        scope.cancel()
    }
    @Test
    fun readOnlyReplyBecomingEmptyStaysWriteClosedInBothParentRefreshCompletionOrders() = runTest {
        suspend fun verify(homeCompletesFirst: Boolean) {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val route =
                activityReplyPayload(
                    parentKind = ConversationKind.Channel,
                    parentChannelId = "channel-1",
                    parentMessageId = "m1",
                    threadId = "thread-m1"
                )
            val retryHomeStarted = CompletableDeferred<Unit>()
            val retryHomeFinished = CompletableDeferred<Unit>()
            val releaseRetryHome = CompletableDeferred<Unit>()
            val retryHomeMarker = "retry-home-${if (homeCompletesFirst) "first" else "last"}"
            var homeAttempt = 0
            val channelRepository =
                FakeChannelRepository {
                    homeAttempt += 1
                    if (homeAttempt == 2) {
                        retryHomeStarted.complete(Unit)
                        if (!homeCompletesFirst) {
                            releaseRetryHome.await()
                        }
                        retryHomeFinished.complete(Unit)
                    }
                    val home = replyChildOnlyHomeSnapshot(joined = true, canSend = true)
                    DataResult.Content(
                        if (homeAttempt == 2) {
                            home.copy(
                                channels =
                                    home.channels +
                                        ConversationSummary(
                                            threadId = ThreadId(retryHomeMarker),
                                            channelId = ChannelId(retryHomeMarker),
                                            kind = ConversationKind.Channel,
                                            name = retryHomeMarker
                                        ).toHomeChannelRow()
                            )
                        } else {
                            home
                        }
                    )
                }
            val repository =
                FakeConversationRepository(
                    threadResult =
                        DataResult.Content(
                            threadSnapshot(
                                threadId = "thread-m1",
                                body = "network reply",
                                id = "reply-1",
                                conversation = replyThreadConversation(joined = false, canSend = false)
                            )
                        )
                )
            val store =
                SharedThreadStore(
                    dataSource =
                        testSharedThreadDataSource(
                            storeScope = backgroundScope,
                            networkApi = FakeThreadApi(failChannelMessages = true),
                            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                            conversationRepository = repository,
                            channelRepository = channelRepository
                        ),
                    scope = scope
                )

            store.dispatch(SharedThreadIntent.Start(route))
            withTimeout(10_000) {
                while (
                    store.state.value.accessResolution != ThreadAccessResolution.Resolved ||
                    store.state.value.writeActionsEnabled
                ) {
                    delay(10)
                }
            }
            repository.threadResult = DataResult.Empty(EmptyReason.NoMessages)
            if (homeCompletesFirst) {
                // Start already resolved and cached the reply stream under this named environment,
                // so Refresh re-reads the thread by id (getThread) instead of by parent pair. The
                // parent-pair hook only fired here while a blank environment disabled that cache.
                repository.getThreadStarted = CompletableDeferred()
                repository.releaseGetThread = CompletableDeferred()
            }
            store.dispatch(SharedThreadIntent.Refresh)
            withTimeout(5_000) { retryHomeStarted.await() }
            if (homeCompletesFirst) {
                withTimeout(5_000) { repository.getThreadStarted?.await() }
                retryHomeFinished.await()
                withTimeout(10_000) {
                    while (retryHomeMarker !in store.state.value.payload.knownMarkdownChannelNames) {
                        delay(10)
                    }
                }
                assertEquals("content", store.state.value.payload.status)
                assertFalse(store.state.value.writeActionsEnabled)
                repository.releaseGetThread?.complete(Unit)
            } else {
                withTimeout(10_000) {
                    while (store.state.value.payload.status != "empty") {
                        delay(10)
                    }
                }
                assertFalse(store.state.value.writeActionsEnabled)
                releaseRetryHome.complete(Unit)
                retryHomeFinished.await()
            }
            withTimeout(10_000) {
                while (
                    store.state.value.payload.status != "empty" ||
                    retryHomeMarker !in store.state.value.payload.knownMarkdownChannelNames
                ) {
                    delay(10)
                }
            }

            val finalState = store.state.value
            assertEquals(ThreadAccessResolution.Resolved, finalState.accessResolution)
            assertFalse(finalState.writeActionsEnabled)
            assertFalse(finalState.payload.joined)
            assertFalse(finalState.payload.canSend)
            assertFalse(finalState.payload.composer.enabled)
            assertEquals(
                null,
                finalState.payload.resolvedTaskLookupChannelId(
                    writeActionsEnabled = finalState.writeActionsEnabled
                )
            )
            scope.cancel()
        }

        verify(homeCompletesFirst = true)
        verify(homeCompletesFirst = false)
    }
    @Test
    fun activityReplyThreadUsesLoadedParentContextWhenParentAccessLookupTimesOut() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = activityReplyPayload(
            parentKind = ConversationKind.Channel,
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            threadId = "thread-m1"
        )
        val neverCompleteHome = CompletableDeferred<Unit>()
        var homeAttempt = 0
        val channelRepository =
            FakeChannelRepository {
                homeAttempt += 1
                if (homeAttempt == 1) {
                    neverCompleteHome.await()
                }
                DataResult.Content(joinedHomeSnapshot())
            }
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "network reply",
                            id = "reply-1",
                            parentMessage =
                                message(
                                    threadId = "channel-1",
                                    id = "m1",
                                    seq = 9,
                                    body = "parent body"
                                ),
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        withTimeout(10_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertTrue(resolvedFrame.canonicalAccessResolved)
        assertTrue(resolvedFrame.initialHomeAccessLookupComplete)
        assertEquals(null, resolvedFrame.initialHomeAccessCandidate)
        assertEquals("loaded", resolvedFrame.payload.originStatus)
        assertEquals("m1", resolvedFrame.payload.originMessage?.id)
        assertEquals("parent body", resolvedFrame.payload.originMessage?.body)
        assertEquals("reply-1", resolvedFrame.payload.messages.single().id)
        assertEquals("network reply", resolvedFrame.payload.messages.single().body)
        assertEquals(ThreadAccessResolution.Resolved, resolvedFrame.accessResolution)
        assertFalse(resolvedFrame.accessResolution.shouldRenderThreadAccessRetry())
        assertFalse(resolvedFrame.payload.joined)
        assertFalse(resolvedFrame.payload.canSend)
        assertFalse(resolvedFrame.payload.composer.enabled)
        assertFalse(resolvedFrame.writeActionsEnabled)
        assertEquals(1, repository.getReplyThreadRequests.size)
        assertEquals(1, channelRepository.channelsCalls)

        store.dispatch(SharedThreadIntent.Refresh)
        withTimeout(10_000) {
            while (!store.state.value.writeActionsEnabled) {
                delay(10)
            }
        }
        assertEquals(ThreadAccessResolution.Resolved, store.state.value.accessResolution)
        assertTrue(store.state.value.payload.joined)
        assertTrue(store.state.value.payload.canSend)
        assertTrue(store.state.value.payload.composer.enabled)
        assertEquals(2, channelRepository.channelsCalls)
        scope.cancel()
    }
    @Test
    fun activityReplyThreadUsesLoadedParentContextWhenHomeLookupCompletesFirstWithoutCandidate() = runTest {
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
                            id = "reply-1",
                            parentMessage =
                                message(
                                    threadId = "channel-1",
                                    id = "m1",
                                    seq = 9,
                                    body = "parent body"
                                ),
                            conversation = replyThreadConversation(joined = false, canSend = false)
                        )
                    )
            ).apply {
                getReplyThreadStarted = CompletableDeferred()
                releaseGetReplyThread = CompletableDeferred()
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = FakeChannelRepository { DataResult.Content(emptyHomeSnapshot()) }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        repository.getReplyThreadStarted?.await()
        withTimeout(10_000) {
            while (!store.state.value.initialHomeAccessLookupComplete) {
                delay(10)
            }
        }
        assertFalse(store.state.value.canonicalAccessResolved)
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)

        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        val resolvedFrame = store.state.value
        assertTrue(resolvedFrame.canonicalAccessResolved)
        assertEquals("loaded", resolvedFrame.payload.originStatus)
        assertEquals("m1", resolvedFrame.payload.originMessage?.id)
        assertEquals("reply-1", resolvedFrame.payload.messages.single().id)
        assertFalse(resolvedFrame.payload.joined)
        assertFalse(resolvedFrame.payload.canSend)
        assertFalse(resolvedFrame.payload.composer.enabled)
        assertFalse(resolvedFrame.writeActionsEnabled)
        scope.cancel()
    }
    @Test
    fun refreshCanonicalAccessWinsInEitherInitialLookupCompletionOrder() = runTest {
        for (homeCompletesFirst in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val homeCalls = Channel<Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
            val canonicalCalls = Channel<Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
            val channelRepository =
                FakeChannelRepository {
                    val release = CompletableDeferred<Unit>()
                    val finished = CompletableDeferred<Unit>()
                    homeCalls.send(release to finished)
                    try {
                        release.await()
                        DataResult.Content(unjoinedHomeSnapshot())
                    } finally {
                        finished.complete(Unit)
                    }
                }
            var canonicalCallCount = 0
            val repository =
                object : ConversationRepository by FakeConversationRepository() {
                    override suspend fun getThread(
                        threadId: ThreadId,
                        includeFollowedSummaries: Boolean,
                        limit: Int,
                    ): DataResult<ThreadSnapshot> {
                        val callIndex = ++canonicalCallCount
                        val release = CompletableDeferred<Unit>()
                        val finished = CompletableDeferred<Unit>()
                        canonicalCalls.send(release to finished)
                        return try {
                            release.await()
                            DataResult.Content(threadSnapshot(body = "canonical-$callIndex"))
                        } finally {
                            finished.complete(Unit)
                        }
                    }
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
            val store = SharedThreadStore(dataSource = dataSource, scope = scope)

            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val (releaseFirstHome, firstHomeFinished) = withTimeout(10_000) { homeCalls.receive() }
            val (releaseFirstCanonical, firstCanonicalFinished) = withTimeout(10_000) { canonicalCalls.receive() }
            releaseFirstCanonical.complete(Unit)
            withTimeout(10_000) { firstCanonicalFinished.await() }
            withTimeout(10_000) {
                store.state.first { it.payload.messages.singleOrNull()?.body == "canonical-1" }
            }
            assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)

            store.dispatch(SharedThreadIntent.Refresh)
            withTimeout(10_000) { firstHomeFinished.await() }
            val (releaseSecondHome, secondHomeFinished) = withTimeout(10_000) { homeCalls.receive() }
            val (releaseSecondCanonical, secondCanonicalFinished) = withTimeout(10_000) { canonicalCalls.receive() }
            if (homeCompletesFirst) {
                releaseSecondHome.complete(Unit)
                withTimeout(10_000) { secondHomeFinished.await() }
                withTimeout(10_000) {
                    store.state.first { it.initialHomeAccessLookupComplete }
                }
                releaseSecondCanonical.complete(Unit)
                withTimeout(10_000) { secondCanonicalFinished.await() }
                withTimeout(10_000) {
                    store.state.first { it.payload.messages.singleOrNull()?.body == "canonical-2" }
                }
            } else {
                releaseSecondCanonical.complete(Unit)
                withTimeout(10_000) { secondCanonicalFinished.await() }
                withTimeout(10_000) {
                    store.state.first { it.payload.messages.singleOrNull()?.body == "canonical-2" }
                }
                releaseSecondHome.complete(Unit)
                withTimeout(10_000) { secondHomeFinished.await() }
                withTimeout(10_000) {
                    store.state.first { it.initialHomeAccessLookupComplete }
                }
            }
            releaseFirstHome.complete(Unit)

            val resolved =
                withTimeout(10_000) {
                    store.state.first {
                        it.initialHomeAccessLookupComplete &&
                            it.accessResolution == ThreadAccessResolution.Resolved &&
                            it.payload.messages.singleOrNull()?.body == "canonical-2" &&
                            it.payload.joined &&
                            it.payload.canSend &&
                            it.payload.composer.enabled &&
                            it.writeActionsEnabled
                    }
                }
            assertTrue(resolved.payload.joined)
            assertTrue(resolved.payload.canSend)
            assertTrue(resolved.payload.composer.enabled)
            assertTrue(resolved.writeActionsEnabled)
            scope.cancel()
        }
    }
    @Test
    fun warmCacheRefreshFailureDoesNotReenableStaleFooterAccess() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val releaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                releaseHome.await()
                DataResult.Content(unjoinedHomeSnapshot())
            }
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Error(ScreenError(code = "offline", retryable = true))
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
                conversationRepository = repository,
                channelRepository = channelRepository
            )
        dataSource.cachePayloadForTest(contentPayload("cached body").copy(joined = true, canSend = true))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        repository.getThreadStarted?.await()
        repository.releaseGetThread?.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Error) {
                delay(10)
            }
        }
        releaseHome.complete(Unit)
        delay(50)

        val failedFrame = store.state.value
        assertEquals(ThreadAccessResolution.Error, failedFrame.accessResolution)
        assertEquals("content", failedFrame.payload.status)
        assertFalse(
            failedFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = failedFrame.timelineSlice,
                accessResolution = failedFrame.accessResolution
            )
        )
        scope.cancel()
    }
    @Test
    fun retryResetsTimedOutHomeVerdictWithoutOverridingCanonicalJoinedAccess() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val firstHomeNeverCompletes = CompletableDeferred<Unit>()
        val releaseRetryHome = CompletableDeferred<Unit>()
        var homeAttempt = 0
        val channelRepository =
            FakeChannelRepository {
                homeAttempt += 1
                if (homeAttempt == 1) {
                    firstHomeNeverCompletes.await()
                } else {
                    releaseRetryHome.await()
                }
                DataResult.Content(unjoinedHomeSnapshot())
            }
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Error(ScreenError(code = "offline", retryable = true))
            )
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
        val renderEvents = mutableListOf<SharedThreadRenderSlice>()
        val renderJob = launch {
            store.renderSliceFlow().collect { slice -> renderEvents += slice }
        }

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        withTimeout(10_000) {
            while (
                store.state.value.accessResolution != ThreadAccessResolution.Error ||
                !store.state.value.initialHomeAccessLookupComplete
            ) {
                delay(10)
            }
        }

        repository.threadResult = DataResult.Content(threadSnapshot(conversation = conversationSummary().copy(joined = true)))
        renderEvents.clear()
        store.dispatch(SharedThreadIntent.Refresh)
        withTimeout(10_000) {
            while (homeAttempt < 2 || store.state.value.payload.messages.singleOrNull()?.body != "repository body") {
                delay(10)
            }
        }

        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(
            renderEvents.any { slice ->
                slice.accessResolution == ThreadAccessResolution.Resolved && slice.payload.joined
            }
        )

        releaseRetryHome.complete(Unit)
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }
        assertTrue(store.state.value.payload.joined)
        assertTrue(store.state.value.payload.canSend)
        assertTrue(store.state.value.payload.composer.enabled)
        assertTrue(store.state.value.writeActionsEnabled)
        renderJob.cancel()
        scope.cancel()
    }
    @Test
    fun resolvedAccessStaysReadyDuringOrdinaryBackgroundRefresh() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository = FakeConversationRepository()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        repository.getThreadStarted = CompletableDeferred()
        repository.releaseGetThread = CompletableDeferred()
        store.dispatch(SharedThreadIntent.Refresh)
        repository.getThreadStarted?.await()

        val refreshingFrame = store.state.value
        assertTrue(refreshingFrame.payload.backgroundRefreshing)
        assertEquals(ThreadAccessResolution.Resolved, refreshingFrame.accessResolution)
        assertTrue(
            refreshingFrame.payload.isThreadPrimaryFooterReady(
                timelineSlice = refreshingFrame.timelineSlice,
                accessResolution = refreshingFrame.accessResolution
            )
        )

        repository.releaseGetThread?.complete(Unit)
        scope.cancel()
    }
    @Test
    fun initialHomeAccessTimeoutFallsBackToCanonicalThreadAccessOnce() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val neverReleaseHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository {
                neverReleaseHome.await()
                DataResult.Content(emptyHomeSnapshot())
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = FakeConversationRepository(),
                channelRepository = channelRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(basePayload()))
        withTimeout(10_000) {
            while (store.state.value.accessResolution != ThreadAccessResolution.Resolved) {
                delay(10)
            }
        }

        assertTrue(store.state.value.canonicalAccessResolved)
        assertTrue(store.state.value.payload.joined)
        assertEquals(1, channelRepository.homeCalls)
        scope.cancel()
    }
    private fun joinedDmHomeSnapshotWithConflictingReplyChild(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(
                        threadId = "dm-thread-1",
                        parentChannelId = "dm-1",
                        parentMessageId = "dm-parent-message",
                        joined = false,
                        canSend = false
                    ).toHomeChannelRow()
                ),
            directMessages =
                listOf(
                    conversationSummary("dm-1").copy(
                        kind = ConversationKind.DirectMessage,
                        channelId = ChannelId("dm-1"),
                        joined = true,
                        permissions = ConversationPermissions(canSend = true)
                    ).toHomeDirectMessageRow()
                ),
            unreadTotal = 0
        )
    private fun joinedJointHomeSnapshotWithConflictingReplyChild(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(
                        threadId = "joint-thread-1",
                        parentChannelId = "joint-1",
                        parentMessageId = "joint-parent-message",
                        joined = false,
                        canSend = false
                    ).toHomeChannelRow(),
                    conversationSummary("joint-1").copy(
                        kind = ConversationKind.Joint,
                        channelId = ChannelId("joint-1"),
                        channelType = JointChannelKindWireValue,
                        joined = true,
                        permissions = ConversationPermissions(canRead = true, canSend = true)
                    ).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )
    private fun replyChildOnlyHomeSnapshot(
        joined: Boolean,
        canSend: Boolean
    ): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
            currentMember = member("u1", "Artin"),
            channels =
                listOf(
                    replyThreadConversation(joined = joined, canSend = canSend).toHomeChannelRow()
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )
}
