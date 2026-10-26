package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.threadPayloadOwnerScopeIdForTest
import build.raft.app.SharedThreadStoreTestFixtures.virtualForegroundLoadGate
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class SharedThreadStorePrewarmTest {
    @Test
    fun sharedThreadPayloadStorePoolsPrewarmRequestsGlobally() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store =
            SharedThreadPayloadStore(
                scope = appScope,
                maxConcurrentPrewarms = 2,
                prewarmStartIntervalMillis = 0
            )
        val release = CompletableDeferred<Unit>()
        val firstTwoStarted = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        var starts = 0
        val jobs =
            (1..3).map { index ->
                async {
                    store.prewarm("server-1", "thread-$index", TestThreadOwnerScopeId) {
                        active += 1
                        starts += 1
                        maxActive = maxOf(maxActive, active)
                        if (starts == 2) firstTwoStarted.complete(Unit)
                        release.await()
                        active -= 1
                        val payload = basePayload().copy(sourceThreadId = "thread-$index")
                        store.put("server-1", "thread-$index", payload, TestThreadOwnerScopeId)
                        DataResult.Content(payload)
                    }
                }
            }

        firstTwoStarted.await()
        assertEquals(2, active)
        assertEquals(2, starts)
        assertEquals(2, maxActive)

        release.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(3, starts)
        assertEquals(2, maxActive)
        appScope.cancel()
    }
    @Test
    fun sharedThreadPayloadStoreRateLimitsPrewarmStarts() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store =
            SharedThreadPayloadStore(
                scope = appScope,
                maxConcurrentPrewarms = 2,
                prewarmStartIntervalMillis = 40,
                prewarmStartTimeSource = testScheduler.timeSource,
            )
        val origin = testScheduler.timeSource.markNow()
        val starts = mutableListOf<Long>()
        val jobs =
            (1..2).map { index ->
                async {
                    store.prewarm("server-1", "thread-$index", TestThreadOwnerScopeId) {
                        starts += origin.elapsedNow().inWholeMilliseconds
                        val payload = basePayload().copy(sourceThreadId = "thread-$index")
                        store.put("server-1", "thread-$index", payload, TestThreadOwnerScopeId)
                        DataResult.Content(payload)
                    }
                }
            }

        jobs.forEach { it.await() }

        assertEquals(2, starts.size)
        assertTrue(starts[1] - starts[0] >= 30L)
        appScope.cancel()
    }
    @Test
    fun initialThreadFetchJoinsCanonicalPrewarmInFlightWithoutRepositoryRequest() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
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
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-test", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val warmed =
            contentPayloadForRoute(
                base = route,
                body = "prewarmed reply body",
                id = "reply-warm",
                channelId = "thread-m1"
            )
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        val prewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    dataSource.cachePayloadForTest(warmed)
                    DataResult.Content(warmed)
                }
            }
        fetchStarted.await()

        val opened =
            async {
                dataSource.fetchThreadPayload(
                    basePayload = route,
                    reusePrewarm = true
                )
            }

        assertEquals(emptyList(), repository.getReplyThreadRequests)
        releaseFetch.complete(Unit)
        assertEquals("prewarmed reply body", (opened.await() as DataResult.Content).data.messages.single().body)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
        prewarm.await()
        appScope.cancel()
    }
    @Test
    fun initialThreadFetchConsumesCompletedPrewarmCacheWithoutRepositoryRequest() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope)
        val repository = FakeConversationRepository()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-success", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val warmed =
            contentPayloadForRoute(
                base = route,
                body = "completed prewarm body",
                id = "reply-warm",
                channelId = "thread-m1"
            )
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
            dataSource.cachePayloadForTest(warmed)
            DataResult.Content(warmed)
        }

        val result = dataSource.fetchThreadPayload(basePayload = route, reusePrewarm = true)

        assertEquals("completed prewarm body", (result as DataResult.Content).data.messages.single().body)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
        appScope.cancel()
    }
    @Test
    fun supersededPrewarmCompletionCannotReplaceTheNewestActivityGeneration() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale =
            contentPayloadForRoute(
                base = route,
                body = "stale generation",
                id = "reply-stale",
                channelId = "thread-m1"
            )
        val fresh =
            contentPayloadForRoute(
                base = route,
                body = "fresh generation",
                id = "reply-fresh",
                channelId = "thread-m1"
            )
        val staleStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val freshStarted = CompletableDeferred<Unit>()
        val releaseFresh = CompletableDeferred<Unit>()
        val stalePrewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    staleStarted.complete(Unit)
                    releaseStale.await()
                    DataResult.Content(stale)
                }
            }
        staleStarted.await()
        payloadStore.freshnessLedger.advance("server-1", "thread-m1", TestThreadOwnerScopeId)
        val freshPrewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    freshStarted.complete(Unit)
                    releaseFresh.await()
                    DataResult.Content(fresh)
                }
            }
        freshStarted.await()

        releaseFresh.complete(Unit)
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(freshPrewarm.await())
        releaseStale.complete(Unit)
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(stalePrewarm.await())

        val consumed =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                payloadStore.joinOrConsumePrewarm("server-1", "thread-m1", TestThreadOwnerScopeId)
            )
        assertEquals("fresh generation", consumed.data.messages.single().body)
        appScope.cancel()
    }
    @Test
    fun lateCompletionFromSupersededInFlightGenerationPublishesNoReadyCandidate() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale =
            contentPayloadForRoute(
                base = route,
                body = "late stale body",
                id = "reply-stale",
                channelId = "thread-m1"
            )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val prewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    started.complete(Unit)
                    release.await()
                    DataResult.Content(stale)
                }
            }
        started.await()

        payloadStore.freshnessLedger.advance("server-1", "thread-m1", TestThreadOwnerScopeId)
        release.complete(Unit)
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(prewarm.await())

        assertEquals(null, payloadStore.joinOrConsumePrewarm("server-1", "thread-m1", TestThreadOwnerScopeId))
        assertEquals(null, payloadStore.get("server-1", "thread-m1", TestThreadEnvironmentId, TestThreadPrincipalId, TestThreadOwnerScopeId))
        appScope.cancel()
    }
    @Test
    fun serverInvalidationMakesReadyAndOrdinaryCachedCandidatesUnusable() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val cached =
            contentPayloadForRoute(
                base = route,
                body = "old candidate",
                id = "reply-old",
                channelId = "thread-m1"
            )
        payloadStore.put("server-1", "thread-m1", cached, TestThreadOwnerScopeId)
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(
            payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) { DataResult.Content(cached) }
        )

        payloadStore.freshnessLedger.invalidateServer("server-1")

        assertEquals(null, payloadStore.get("server-1", "thread-m1", TestThreadEnvironmentId, TestThreadPrincipalId, TestThreadOwnerScopeId))
        assertEquals(null, payloadStore.joinOrConsumePrewarm("server-1", "thread-m1", TestThreadOwnerScopeId))
        appScope.cancel()
    }
    @Test
    fun ordinaryCanonicalFetchRetriesCurrentGenerationBeforeReturningOrCaching() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        var responseIndex = 0
        val repository = FakeConversationRepository()
        repository.threadResultProvider = { _, _, _ ->
            if (responseIndex++ == 0) {
                DataResult.Content(threadSnapshot(body = "request-start stale", id = "stale"))
            } else {
                DataResult.Content(threadSnapshot(body = "current fresh", id = "fresh"))
            }
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.getThreadStarted = started
        repository.releaseGetThread = release
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("canonical-generation", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        val request =
            async {
                dataSource.fetchThreadPayload(
                    basePayload = basePayload(),
                    persistResult = true,
                    reusePrewarm = false
                )
            }
        started.await()

        payloadStore.freshnessLedger.advance("server-1", "channel-1", ownerScopeId)
        release.complete(Unit)
        val result = assertIs<DataResult.Content<RaftThreadScreenPayload>>(request.await())

        assertEquals("current fresh", result.data.messages.single().body)
        assertEquals(
            "current fresh",
            payloadStore.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerScopeId)?.messages?.single()?.body
        )
        assertEquals(2, repository.getThreadRequests.size)
        appScope.cancel()
    }
    @Test
    fun visibleJoinOfSupersededPrewarmRefetchesCurrentGeneration() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "current after join", id = "fresh"))
            )
        suspend fun dataSource(shellId: String): SharedThreadDataSource {
            val result = testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(RaftShellStoreScope.create(shellId, "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
            result.readSession()
            return result
        }
        val firstDataSource = dataSource("join-current-first")
        val secondDataSource = dataSource("join-current-second")
        val ownerScopeId = firstDataSource.threadPayloadOwnerScopeIdForTest()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stalePrewarm =
            async {
                payloadStore.prewarm("server-1", "channel-1", ownerScopeId) {
                    started.complete(Unit)
                    release.await()
                    DataResult.Content(contentPayload("stale joined", id = "stale"))
                }
            }
        started.await()
        val firstVisible = async { firstDataSource.fetchThreadPayload(basePayload(), reusePrewarm = true) }
        val secondVisible = async { secondDataSource.fetchThreadPayload(basePayload(), reusePrewarm = true) }
        yield()

        payloadStore.freshnessLedger.advance("server-1", "channel-1", ownerScopeId)
        release.complete(Unit)
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(stalePrewarm.await())
        val firstResult = assertIs<DataResult.Content<RaftThreadScreenPayload>>(firstVisible.await())
        val secondResult = assertIs<DataResult.Content<RaftThreadScreenPayload>>(secondVisible.await())

        assertEquals("current after join", firstResult.data.messages.single().body)
        assertEquals("current after join", secondResult.data.messages.single().body)
        assertEquals(1, repository.getThreadRequests.size)
        appScope.cancel()
    }
    @Test
    fun supersededFailedTakeoverRetriesCurrentGenerationBeforeVisibleSuccess() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        var responseIndex = 0
        val repository = FakeConversationRepository()
        repository.threadResultProvider = { _, _, _ ->
            if (responseIndex++ == 0) {
                DataResult.Content(threadSnapshot(body = "stale takeover", id = "stale"))
            } else {
                DataResult.Content(threadSnapshot(body = "current takeover", id = "fresh"))
            }
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.getThreadStarted = started
        repository.releaseGetThread = release
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("takeover-current", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "channel-1", ownerScopeId) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }
        val visible = async { dataSource.fetchThreadPayload(basePayload(), reusePrewarm = true) }
        started.await()

        payloadStore.freshnessLedger.advance("server-1", "channel-1", ownerScopeId)
        release.complete(Unit)
        val result = assertIs<DataResult.Content<RaftThreadScreenPayload>>(visible.await())

        assertEquals("current takeover", result.data.messages.single().body)
        assertEquals(2, repository.getThreadRequests.size)
        appScope.cancel()
    }
    @Test
    fun retiredOwnerFailedPrewarmTakeoverDoesNotRetryOrWriteEitherOwnerCache() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "retired owner", id = "retired"))
            )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.getThreadStarted = started
        repository.releaseGetThread = release
        val authStore = InMemoryRaftAuthSessionStore(sampleSession())
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = authStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("retired-takeover", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val ownerA = dataSource.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "channel-1", ownerA) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }
        val visible = async { dataSource.fetchThreadPayload(basePayload(), reusePrewarm = true) }
        started.await()

        authStore.saveSession(
            sampleSession().copy(
                user = StoredAuthUser(id = "u2", email = "second@example.test", displayName = "Second"),
                accessToken = "access-u2",
                refreshToken = "refresh-u2"
            )
        )
        val ownerB = dataSource.threadPayloadOwnerScopeIdForTest()
        release.complete(Unit)

        val result = assertIs<DataResult.Error>(visible.await())
        assertEquals("thread_reply_cache_provenance_stale", result.error.code)
        assertEquals(false, result.error.retryable)
        assertEquals(1, repository.getThreadRequests.size)
        assertNull(payloadStore.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerA))
        assertNull(payloadStore.get("server-1", "channel-1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerB))
        appScope.cancel()
    }
    @Test
    fun removeAfterReadyPrewarmSelfHealsToOneFreshFlight() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale =
            contentPayloadForRoute(
                base = route,
                body = "removed ready",
                id = "removed-ready",
                channelId = "thread-m1"
            )
        val fresh =
            contentPayloadForRoute(
                base = route,
                body = "fresh after remove",
                id = "fresh-after-remove",
                channelId = "thread-m1"
            )
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(
            payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) { DataResult.Content(stale) }
        )
        payloadStore.remove("server-1", "thread-m1", TestThreadOwnerScopeId)
        var freshFetches = 0

        val result =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    freshFetches += 1
                    DataResult.Content(fresh)
                }
            )

        assertEquals(1, freshFetches)
        assertEquals("fresh after remove", result.data.messages.single().body)
        appScope.cancel()
    }
    @Test
    fun failedPrewarmTakeoverIsSingleFlightAcrossVisibleStores() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
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
                        )
                    )
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val takeoverStarted = CompletableDeferred<Unit>()
        val releaseTakeover = CompletableDeferred<Unit>()
        repository.getReplyThreadStarted = takeoverStarted
        repository.releaseGetReplyThread = releaseTakeover
        suspend fun dataSource(shellId: String): SharedThreadDataSource {
            val dataSource = testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(RaftShellStoreScope.create(shellId, "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
            dataSource.readSession()
            return dataSource
        }
        val firstStore = dataSource("failed-prewarm-first")
        val secondStore = dataSource("failed-prewarm-second")
        val ownerScopeId = firstStore.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }

        val first = async { firstStore.fetchThreadPayload(route, reusePrewarm = true) }
        takeoverStarted.await()
        val second = async { secondStore.fetchThreadPayload(route, reusePrewarm = true) }

        assertEquals(1, repository.getReplyThreadRequests.size)
        releaseTakeover.complete(Unit)
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(first.await())
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(second.await())
        assertEquals(1, repository.getReplyThreadRequests.size)
        appScope.cancel()
    }
    @Test
    fun failedNewGenerationCannotReturnThePreviousReadyPayloadDuringVisibleTakeover() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val stale =
            contentPayloadForRoute(
                base = route,
                body = "stale v1",
                id = "reply-v1",
                channelId = "thread-m1"
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(
                                        threadId = "thread-m1",
                                        id = "reply-fresh",
                                        seq = 3,
                                        body = "fresh takeover"
                                    )
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
        val takeoverStarted = CompletableDeferred<Unit>()
        val releaseTakeover = CompletableDeferred<Unit>()
        repository.getReplyThreadStarted = takeoverStarted
        repository.releaseGetReplyThread = releaseTakeover
        suspend fun dataSource(shellId: String): SharedThreadDataSource {
            val dataSource =
                testSharedThreadDataSource(
                    storeScope = backgroundScope,
                    networkApi = FakeThreadApi(),
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    selectedServerStore = SelectedServerStore(RaftShellStoreScope.create(shellId, "server-1")),
                    conversationRepository = repository,
                    payloadStore = payloadStore
                )
            dataSource.readSession()
            return dataSource
        }
        val firstDataSource = dataSource("v2-takeover-first")
        val secondDataSource = dataSource("v2-takeover-second")
        val ownerScopeId = firstDataSource.threadPayloadOwnerScopeIdForTest()
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(
            payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
                DataResult.Content(stale)
            }
        )
        payloadStore.freshnessLedger.advance("server-1", "thread-m1", ownerScopeId)
        assertIs<DataResult.Error>(
            payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
                DataResult.Error(ScreenError(code = "prewarm_v2_offline", retryable = true))
            }
        )
        val first = async { firstDataSource.fetchThreadPayload(route, reusePrewarm = true) }
        takeoverStarted.await()
        val second = async { secondDataSource.fetchThreadPayload(route, reusePrewarm = true) }

        assertEquals(1, repository.getReplyThreadRequests.size)
        releaseTakeover.complete(Unit)
        val firstPayload = assertIs<DataResult.Content<RaftThreadScreenPayload>>(first.await()).data
        val secondPayload = assertIs<DataResult.Content<RaftThreadScreenPayload>>(second.await()).data
        assertEquals("fresh takeover", firstPayload.messages.single().body)
        assertEquals("fresh takeover", secondPayload.messages.single().body)
        assertEquals(1, repository.getReplyThreadRequests.size)
        appScope.cancel()
    }
    @Test
    fun manualRetrySuccessClearsTerminalFailedPrewarmGeneration() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope)
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Error(ScreenError(code = "takeover_offline", retryable = true))
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        suspend fun dataSource(shellId: String): SharedThreadDataSource {
            val dataSource = testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(RaftShellStoreScope.create(shellId, "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
            dataSource.readSession()
            return dataSource
        }

        val terminalDataSource = dataSource("failed-prewarm-terminal")
        val ownerScopeId = terminalDataSource.threadPayloadOwnerScopeIdForTest()
        payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }

        assertIs<DataResult.Error>(
            terminalDataSource.fetchThreadPayload(
                basePayload = route,
                persistResult = false,
                reusePrewarm = true
            )
        )
        assertEquals(1, repository.getReplyThreadRequests.size)

        repository.threadResult =
            DataResult.Content(
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
                )
            )
        // Under a named environment the failed access flight is remembered per identity, so a
        // manual retry must say so (the Store passes explicitRetry here); a blank environment
        // used to skip that memory entirely and made this test pass without it.
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(
            dataSource("failed-prewarm-manual-retry").fetchThreadPayload(
                basePayload = route,
                persistResult = false,
                reusePrewarm = false,
                replaceReplyAccessFailure = true
            )
        )
        assertEquals(2, repository.getReplyThreadRequests.size)

        assertIs<DataResult.Content<RaftThreadScreenPayload>>(
            dataSource("failed-prewarm-reopen").fetchThreadPayload(
                basePayload = route,
                persistResult = false,
                reusePrewarm = true
            )
        )
        assertEquals(3, repository.getReplyThreadRequests.size)
        appScope.cancel()
    }
    @Test
    fun failedPrewarmTakeoverJoinsNewPrewarmStartedDuringHandoff() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope)
        payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
            DataResult.Error(ScreenError(code = "prewarm_offline", retryable = true))
        }
        val replacementStarted = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val replacementPayload =
            contentPayloadForRoute(
                base = replyPayload(parentMessageId = "m1", threadId = "thread-m1"),
                body = "replacement prewarm body",
                id = "replacement-prewarm",
                channelId = "thread-m1"
            )
        val replacement =
            async {
                payloadStore.prewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    replacementStarted.complete(Unit)
                    releaseReplacement.await()
                    DataResult.Content(replacementPayload)
                }
            }
        replacementStarted.await()
        var directFetches = 0
        val takeover =
            async {
                payloadStore.takeOverFailedPrewarm("server-1", "thread-m1", TestThreadOwnerScopeId) {
                    directFetches += 1
                    DataResult.Error(ScreenError(code = "duplicate_fresh", retryable = true))
                }
            }

        yield()
        assertEquals(0, directFetches)
        releaseReplacement.complete(Unit)
        assertEquals(
            "replacement prewarm body",
            (takeover.await() as DataResult.Content).data.messages.single().body
        )
        replacement.await()
        assertEquals(0, directFetches)
        appScope.cancel()
    }
    @Test
    fun activityReplyPrewarmUsesBackgroundRepositoryPriorityAndCanonicalCache() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
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
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-marker", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val marker =
            ActivityReplyThreadPrewarmMarker(
                key =
                    ActivityReplyThreadPrewarmKey(
                        serverId = "server-1",
                        parentChannelId = "channel-1",
                        parentMessageId = "m1",
                        threadChannelId = "thread-m1"
                )
            )

        val result = dataSource.prewarmReplyThread(marker)

        assertTrue(result is DataResult.Content)
        assertEquals(listOf(DataRequestPriority.Background), repository.getReplyThreadPriorities)
        assertEquals(listOf(DataRequestPriority.Background), repository.getMessageContextPriorities)
        assertEquals("thread-m1", dataSource.cachedPayload("thread-m1")?.sourceThreadId)
    }
    @Test
    fun ownerTransitionDropsLateReplyPrewarmWithoutWritingEitherOwnerCache() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, prewarmStartIntervalMillis = 0L)
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
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
                        )
                    )
            ).also {
                it.getReplyThreadStarted = CompletableDeferred()
                it.releaseGetReplyThread = CompletableDeferred()
            }
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
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-owner-transition", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore,
                replyCacheIdentityAuthority = { identity }
            )
        val ownerA = dataSource.threadPayloadOwnerScopeIdForTest()
        val marker =
            ActivityReplyThreadPrewarmMarker(
                key =
                    ActivityReplyThreadPrewarmKey(
                        serverId = "server-1",
                        parentChannelId = "channel-1",
                        parentMessageId = "m1",
                        threadChannelId = "thread-m1"
                    )
            )
        val prewarm = async { dataSource.prewarmReplyThread(marker) }
        repository.getReplyThreadStarted?.await()

        identity =
            ThreadReplyCacheIdentitySnapshot(
                session = ownerBSession,
                environmentId = "https://prod.example.test",
                principalId = "u-b",
                serverId = "server-1",
                authGeneration = 2L,
                selectionGeneration = 1L
            )
        val ownerB = dataSource.threadPayloadOwnerScopeIdForTest()
        repository.releaseGetReplyThread?.complete(Unit)

        val result = assertIs<DataResult.Error>(prewarm.await())
        assertEquals("thread_reply_access_authority_changed", result.error.code)
        assertNull(payloadStore.get("server-1", "thread-m1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerA))
        assertNull(payloadStore.get("server-1", "thread-m1", environmentId = TestThreadEnvironmentId, principalId = TestThreadPrincipalId, ownerScopeId = ownerB))
    }
    @Test
    fun newerActivityVersionReplacesCompletedPrewarmBeforeVisibleOpen() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, prewarmStartIntervalMillis = 0L)
        fun snapshot(messages: List<Message>) =
            DataResult.Content(
                threadSnapshot(
                    threadId = "thread-m1",
                    messages = messages,
                    parentMessage =
                        message(
                            threadId = "channel-1",
                            id = "m1",
                            seq = 1,
                            body = "parent"
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
        val repository =
            FakeConversationRepository(
                threadResult =
                    snapshot(
                        listOf(
                            message(
                                threadId = "thread-m1",
                                id = "reply-1",
                                seq = 2,
                                body = "reply one"
                            )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-version", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore
            )
        val key =
            ActivityReplyThreadPrewarmKey(
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                threadChannelId = "thread-m1"
            )

        assertIs<DataResult.Content<Unit>>(
            dataSource.prewarmReplyThread(
                ActivityReplyThreadPrewarmMarker(key = key)
            )
        )
        repository.threadResult =
            snapshot(
                listOf(
                    message(
                        threadId = "thread-m1",
                        id = "reply-1",
                        seq = 2,
                        body = "reply one"
                    ),
                    message(
                        threadId = "thread-m1",
                        id = "reply-2",
                        seq = 3,
                        body = "reply two"
                    )
                )
            )
        payloadStore.freshnessLedger.advance(
            "server-1",
            "thread-m1",
            dataSource.threadPayloadOwnerScopeIdForTest()
        )

        assertIs<DataResult.Content<Unit>>(
            dataSource.prewarmReplyThread(
                ActivityReplyThreadPrewarmMarker(key = key)
            )
        )
        val visible =
            assertIs<DataResult.Content<RaftThreadScreenPayload>>(
                dataSource.fetchThreadPayload(
                    basePayload = replyPayload(parentMessageId = "m1", threadId = "thread-m1"),
                    reusePrewarm = true
                )
            )

        assertEquals(2, repository.getReplyThreadRequests.size)
        assertEquals(listOf("reply-1", "reply-2"), visible.data.messages.map { it.id })
        assertEquals(listOf(DataRequestPriority.Background, DataRequestPriority.Background), repository.getReplyThreadPriorities)
    }
}
