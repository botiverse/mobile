package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.focusedReplySnapshot
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.ConcurrentTestLogBuffer
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.SharedThreadStoreTestFixtures.activityReplyPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.focusedReplyRoute
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.testReplyCacheIdentityAuthority
import build.raft.app.SharedThreadStoreTestFixtures.threadPayloadOwnerScopeIdForTest
import build.raft.app.SharedThreadStoreTestFixtures.virtualForegroundLoadGate
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.SharedForegroundLoadGate
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class SharedThreadStoreForegroundGateTest {
    @Test
    fun replyThreadStartPromotesQueuedActivityPrewarmToForeground() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore =
            SharedThreadPayloadStore(
                scope = appScope,
                maxConcurrentPrewarms = 1,
                prewarmStartIntervalMillis = 0L
            )
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val blocker =
            async {
                payloadStore.prewarm("server-1", "thread-blocker", TestThreadOwnerScopeId) {
                    blockerStarted.complete(Unit)
                    releaseBlocker.await()
                    DataResult.Content(
                        contentPayloadForRoute(
                            base = replyPayload(parentMessageId = "blocker", threadId = "thread-blocker"),
                            body = "blocker body",
                            id = "blocker-reply",
                            channelId = "thread-blocker"
                        )
                    )
                }
            }
        blockerStarted.await()

        val repository =
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
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-promote", "server-1")),
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
        val queuedPrewarm =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    dataSource.prewarmReplyThread(marker)
                } catch (_: CancellationException) {
                    // A visible open owns the replacement foreground request.
                }
            }
        assertEquals(emptyList(), repository.getReplyThreadRequests)

        val visibleScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = visibleScope
            )
        withTimeout(10_000L) {
            store.accept(
                SharedThreadIntent.Start(replyPayload(parentMessageId = "m1", threadId = "thread-m1"))
            )
        }

        assertEquals(
            listOf(ChannelId("channel-1") to MessageId("m1")),
            repository.getReplyThreadRequests
        )
        assertEquals(listOf(DataRequestPriority.Foreground), repository.getReplyThreadPriorities)
        assertEquals("reply body", store.state.value.payload.messages.single().body)
        queuedPrewarm.join()
        releaseBlocker.complete(Unit)
        blocker.await()
        visibleScope.cancel()
        appScope.cancel()
    }
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
    fun replyThreadStartJoinsInFlightPrewarmOfSameThreadWithoutSecondRequest() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = foregroundGateReplyRepository()
        val logs = ConcurrentTestLogBuffer()
        val dataSource = foregroundGateDataSource(repository, payloadStore, "thread-fg-join-same", logs::add, storeScope = backgroundScope)
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1")
        val warmed = contentPayloadForRoute(base = route, body = "prewarmed reply body", id = "reply-warm", channelId = "thread-m1")
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        val prewarm =
            async {
                payloadStore.prewarm("server-1", "thread-m1", ownerScopeId) {
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    DataResult.Content(warmed)
                }
            }
        fetchStarted.await()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        val opened = async { store.accept(SharedThreadIntent.Start(route)) }
        delay(50L)
        assertEquals(emptyList(), repository.getReplyThreadRequests)
        releaseFetch.complete(Unit)
        withTimeout(10_000L) { opened.await() }

        assertEquals(emptyList(), repository.getReplyThreadRequests, "the open must reuse the in-flight prewarm")
        assertEquals("prewarmed reply body", store.state.value.payload.messages.single().body)
        assertTrue(
            logs.any { it == "thread_refresh_reuse_prewarm threadId=thread-m1 outcome=joined_in_flight reason=prewarm_content" }
        )
        prewarm.await()
        scope.cancel()
        appScope.cancel()
    }
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
    fun threadForegroundGateIsReleasedByFirstContentReadyCancellationAndClear() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        // One payload store (and so one gate) per variant: a shared one would serve the next
        // variant's Start from the memory cache the previous variant wrote.
        lateinit var gate: SharedForegroundLoadGate

        suspend fun startBlocked(name: String): Triple<SharedThreadStore, CoroutineScope, FakeConversationRepository> {
            val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
            gate = payloadStore.foregroundLoadGate
            val repository = foregroundGateReplyRepository()
            repository.getReplyThreadStarted = CompletableDeferred()
            repository.releaseGetReplyThread = CompletableDeferred()
            val dataSource = foregroundGateDataSource(repository, payloadStore, name, storeScope = backgroundScope)
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val store = SharedThreadStore(dataSource = dataSource, scope = scope)
            store.dispatch(SharedThreadIntent.Start(replyPayload(parentMessageId = "m1", threadId = "thread-m1")))
            withTimeout(10_000L) { repository.getReplyThreadStarted?.await() }
            assertEquals(1, gate.activeCount())
            return Triple(store, scope, repository)
        }

        // First content ready (e.g. a cached frame rendered) releases before the load finishes.
        val (readyStore, readyScope, readyRepository) = startBlocked("thread-fg-gate-ready")
        readyStore.accept(SharedThreadIntent.FirstContentReady)
        assertEquals(0, gate.activeCount())
        readyRepository.releaseGetReplyThread?.complete(Unit)
        readyScope.cancel()

        // Cancellation of the load (page scope torn down without clear) releases via finally.
        val (_, cancelledScope, _) = startBlocked("thread-fg-gate-cancel")
        cancelledScope.cancel()
        // Well inside the 5 s lease timeout, so only the real release can make this green.
        withTimeout(2_000L) { while (gate.activeCount() != 0) delay(5L) }

        // Page close.
        val (closedStore, closedScope, _) = startBlocked("thread-fg-gate-clear")
        closedStore.clear()
        assertEquals(0, gate.activeCount())
        closedScope.cancel()
        appScope.cancel()
    }
    @Test
    fun activityReplyPrewarmWaitsForAVisibleThreadStartThroughTheSharedPayloadStore() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = foregroundGateReplyRepository()
        repository.getReplyThreadStarted = CompletableDeferred()
        repository.releaseGetReplyThread = CompletableDeferred()
        val dataSource = foregroundGateDataSource(repository, payloadStore, "thread-fg-gate-activity", storeScope = backgroundScope)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val prewarmCalls = kotlinx.atomicfu.atomic(0)
        val activityScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val activity =
            build.raft.app.data.activityStore(
                scope = activityScope,
                foregroundLoadGate = payloadStore.foregroundLoadGate,
                prewarmReplyThread = {
                    prewarmCalls.incrementAndGet()
                    DataResult.Content(Unit)
                }
            )

        val opened = async { store.accept(SharedThreadIntent.Start(replyPayload(parentMessageId = "m1", threadId = "thread-m1"))) }
        withTimeout(10_000L) { repository.getReplyThreadStarted?.await() }
        activity.dispatch(
            SharedActivityIntent.PrewarmVisibleReplyThreads(
                setOf(
                    ActivityReplyThreadPrewarmMarker(
                        ActivityReplyThreadPrewarmKey("server-1", "channel-2", "m2", "thread-m2")
                    )
                )
            )
        )
        delay(100L)
        assertEquals(0, prewarmCalls.value, "Activity prewarm must yield to the visible open")

        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(10_000L) { opened.await() }
        withTimeout(5_000L) { while (prewarmCalls.value == 0) delay(5L) }
        assertEquals(1, prewarmCalls.value)
        activityScope.cancel()
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
    fun threadRestartReleasesThePreviousForegroundLease() = runTest {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = foregroundGateReplyRepository()
        repository.getReplyThreadStarted = CompletableDeferred()
        repository.releaseGetReplyThread = CompletableDeferred()
        val dataSource = foregroundGateDataSource(repository, payloadStore, "thread-fg-gate-restart", storeScope = backgroundScope)
        val gate = payloadStore.foregroundLoadGate
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val logs = ConcurrentTestLogBuffer()
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        // accept (not dispatch): the first Start is NOT cancelled, so only the restart path can release it.
        val first = async { store.accept(SharedThreadIntent.Start(replyPayload(parentMessageId = "m1", threadId = "thread-m1"))) }
        withTimeout(10_000L) { repository.getReplyThreadStarted?.await() }
        assertEquals(1, gate.activeCount())
        val second = async { store.accept(SharedThreadIntent.Start(replyPayload(parentMessageId = "m2", threadId = "thread-m2"))) }
        withTimeout(10_000L) {
            while (!logs.any { it.startsWith("thread_foreground_load_acquired threadId=thread-m2 ") }) delay(5L)
        }

        assertEquals(1, gate.activeCount(), "a restart must release the previous route's lease")
        assertTrue(logs.any { it.startsWith("thread_foreground_load_released reason=restarted ") })
        repository.releaseGetReplyThread?.complete(Unit)
        withTimeout(10_000L) {
            first.await()
            second.await()
        }
        assertEquals(0, gate.activeCount())
        scope.cancel()
        appScope.cancel()
    }
    @Test
    fun activityTabOpenThatCachedTheThreadDropsItsQueuedPrewarm() = runTest {
        val run = activityTabOpenWithQueuedPrewarm(openFails = false)
        assertEquals(0, run.prewarmCalls, "the queued prewarm must not run for the opened, cached thread")
        assertEquals(run.requestsAfterOpen, run.finalRequests, "the queued prewarm must not re-fetch the opened thread")
        assertTrue(run.logs.any { it.startsWith("activity_reply_prewarm_result result=skipped reason=foreground_open_owns_load ") })
    }
    @Test
    fun activityTabOpenThatFailedLeavesItsQueuedPrewarmToRun() = runTest {
        // Twin: same route, same queue, same tap; the open's load fails, so nothing is cached.
        val run = activityTabOpenWithQueuedPrewarm(openFails = true)
        // (Its repository request may be answered by the data source's remembered failed lookup, so the
        // observable is that the prewarm itself ran, not the request count.)
        assertEquals(1, run.prewarmCalls, "the prewarm must still run")
        assertFalse(run.logs.any { "result=skipped" in it })
    }
    private class ActivityTabOpenRun(
        val requestsAfterOpen: Int,
        val finalRequests: Int,
        val prewarmCalls: Int,
        val logs: ConcurrentTestLogBuffer,
    )
    private suspend fun TestScope.activityTabOpenWithQueuedPrewarm(openFails: Boolean): ActivityTabOpenRun {
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloadStore = SharedThreadPayloadStore(appScope, prewarmStartIntervalMillis = 0L, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = foregroundGateReplyRepository()
        if (openFails) repository.threadResult = DataResult.Error(ScreenError(code = "thread_network", retryable = true))
        val logs = ConcurrentTestLogBuffer()
        val dataSource = foregroundGateDataSource(repository, payloadStore, "thread-fg-activity-tab-$openFails", logs::add, storeScope = backgroundScope)
        val activityScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prewarmCalls = kotlinx.atomicfu.atomic(0)
        val activity =
            build.raft.app.data.activityStore(
                scope = activityScope,
                foregroundLoadGate = payloadStore.foregroundLoadGate,
                replyThreadFreshnessLedger = payloadStore.freshnessLedger,
                prewarmReplyThread = { marker ->
                    prewarmCalls.incrementAndGet()
                    dataSource.prewarmReplyThread(marker)
                },
                replyThreadContentCached = dataSource::hasCurrentReplyThreadContent,
                diagnosticsLogger = logs::add
            )
        // Another open holds the gate, so the row's prewarm is queued (the device timeline).
        val blocker =
            payloadStore.foregroundLoadGate.acquire(
                build.raft.app.data.SharedForegroundThreadKey(serverId = "server-1", threadId = "thread-blocker")
            )
        activity.dispatch(
            SharedActivityIntent.PrewarmVisibleReplyThreads(
                setOf(ActivityReplyThreadPrewarmMarker(ActivityReplyThreadPrewarmKey("server-1", "channel-1", "m1", "thread-m1")))
            )
        )
        withTimeout(10_000L) { while (!logs.any { it.startsWith("activity_reply_prewarm_deferred ") }) delay(5L) }
        delay(20L) // the tap lands strictly after the prewarm was queued

        // The same payload shape the Activity tab launches (home_open_thread_launch source=activity_tab).
        val route = activityReplyPayload(ConversationKind.Channel, "channel-1", "m1", "thread-m1")
        assertEquals("thread-m1", route.sourceThreadId)
        assertEquals("channel-1", route.parentChannelId)
        assertEquals("m1", route.parentMessageId)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        withTimeout(10_000L) { store.accept(SharedThreadIntent.Start(route)) }
        val requestsAfterOpen = repository.getReplyThreadRequests.size
        assertTrue(requestsAfterOpen >= 1)

        blocker.release()
        withTimeout(10_000L) { while (!logs.any { it.startsWith("activity_reply_prewarm_result ") }) delay(5L) }
        val finalRequests = repository.getReplyThreadRequests.size
        activityScope.cancel()
        scope.cancel()
        appScope.cancel()
        return ActivityTabOpenRun(requestsAfterOpen, finalRequests, prewarmCalls.value, logs)
    }
    @Test
    fun replyThreadStartJoinsActivityPrewarmAndHydratesParentAfterGraceTimeout() = runTest {
        // Payload store, message store, prewarm and store all run on the one test scheduler. The
        // store's state writes are plain read-modify-write and rely on the single-threaded scope
        // it gets in production (viewModelScope). On Dispatchers.Default the members hydration
        // could write membersLoadKey between the secondary refresh's two reads of the state, so
        // the "unchanged" secondary commit reported emitted=true (flaky in CI).
        val payloadStore = SharedThreadPayloadStore(backgroundScope, prewarmStartIntervalMillis = 0L)
        val logs = mutableListOf<String>()
        fun logDump(): String = logs.joinToString(separator = "\n", prefix = "\nlogs:\n")
        val parentMessageId = MessageId("m1")
        val releaseParent = CompletableDeferred<Unit>()
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        focusedReplySnapshot(
                            threadId = "thread-m1",
                            parentChannelId = "channel-1",
                            parentMessageId = parentMessageId.value,
                            focusedMessageId = "reply-1"
                        )
                    ),
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            id = parentMessageId.value,
                            seq = 1,
                            body = "late parent"
                        )
                    )
            )
        repository.releaseMessageContextById[parentMessageId] = releaseParent
        val replyFetchStarted = CompletableDeferred<Unit>()
        val releaseReplyFetch = CompletableDeferred<Unit>()
        repository.getReplyThreadStarted = replyFetchStarted
        repository.releaseGetReplyThread = releaseReplyFetch
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore =
                    SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-visible-start", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore,
                // The default message store launches the parent-lookup producer on its own
                // Dispatchers.Default scope; keep it on the test scheduler too.
                messageStore = SharedThreadMessageStore(scope = backgroundScope),
                replyOriginHydrationGraceMillis = 0L,
                diagnosticsLogger = { message ->
                    projectionRejectingDiagnosticsLogger(message)
                    logs += "data_source $message"
                }
            )
        val marker =
            ActivityReplyThreadPrewarmMarker(
                ActivityReplyThreadPrewarmKey(
                    serverId = "server-1",
                    parentChannelId = "channel-1",
                    parentMessageId = parentMessageId.value,
                    threadChannelId = "thread-m1"
                )
            )
        val prewarm = async { dataSource.prewarmReplyThread(marker) }
        replyFetchStarted.await()

        val store = SharedThreadStore(dataSource = dataSource, scope = backgroundScope, diagnosticsLogger = logs::add)
        val visibleStart = async {
            store.accept(
                SharedThreadIntent.Start(replyPayload(parentMessageId.value, "thread-m1").copy(originStatus = "loading"))
            )
        }
        // Run everything due at virtual time zero without advancing the clock: the prewarm is
        // held on releaseReplyFetch, so the visible Start has either joined that flight or issued
        // its own request by now. Advancing time here would expire the 5s prewarm join timeout.
        runCurrent()
        assertEquals(
            listOf(ChannelId("channel-1") to parentMessageId),
            repository.getReplyThreadRequests,
            "the visible Start must join the in-flight Activity prewarm instead of issuing another request" +
                logDump()
        )
        assertFalse(
            visibleStart.isCompleted,
            "the visible Start must be waiting on the joined prewarm flight while it is held" + logDump()
        )
        releaseReplyFetch.complete(Unit)
        assertTrue(prewarm.await() is DataResult.Content, "the Activity prewarm must load the reply thread")
        visibleStart.await()
        val reuse = logs.filter { it.startsWith("data_source thread_refresh_reuse_prewarm ") }
        assertTrue(
            reuse.size == 1 &&
                (reuse.single().contains("outcome=consumed_ready") || reuse.single().contains("outcome=joined_in_flight")),
            "the visible Start must reuse the Activity prewarm result, not time out into its own fetch" + logDump()
        )
        // Let the launched secondary refresh and focused-origin hydration park (the latter on
        // releaseParent) before snapshotting the pre-parent state.
        runCurrent()

        val beforeParent = store.state.value.payload
        assertEquals("loading", beforeParent.originStatus, "parent is still held after the grace timeout" + logDump())
        assertEquals(listOf("focused reply"), beforeParent.messages.map { it.body })
        assertEquals("loading", dataSource.cachedPayload("thread-m1")?.originStatus)
        releaseParent.complete(Unit)
        runCurrent()
        val loaded = store.state.value.payload
        assertEquals(
            "loaded",
            loaded.originStatus,
            "the late parent must hydrate the visible route after the grace timeout" + logDump()
        )
        assertEquals("late parent", loaded.originMessage?.body)
        assertEquals(
            1,
            repository.getMessageContextRequests.count { it == parentMessageId },
            "the visible route must reuse the prewarm's parent lookup: ${repository.getMessageContextRequests}" +
                logDump()
        )
        store.accept(SharedThreadIntent.FirstContentReady)
        runCurrent()

        assertEquals(
            listOf(ChannelId("channel-1") to parentMessageId),
            repository.getReplyThreadRequests
        )
        assertEquals(listOf(false), repository.getReplyThreadIncludeFollowedSummaries)
        assertEquals(listOf(DataRequestPriority.Background), repository.getReplyThreadPriorities)
        assertEquals("focused reply", store.state.value.payload.messages.single().body)
        val secondaryResults =
            logs.filter { it.startsWith("thread_secondary_refresh_result") && it.contains("emitted=") }
        assertEquals(
            1,
            secondaryResults.size,
            "the stashed secondary refresh must apply exactly once after FirstContentReady" + logDump()
        )
        assertTrue(
            secondaryResults.single().contains("emitted=false"),
            "an unchanged secondary payload must not emit a second state or trigger recomposition" + logDump()
        )
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
    fun fetchThreadPayloadKeepsRouteJointChannelKindOverGenericRepositoryKind() = runTest {
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
                                    channelType = "channel",
                                    kind = ConversationKind.Channel,
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
                channelKind = "joint"
            )

        val payload = (dataSource.fetchThreadPayload(base) as DataResult.Content).data

        assertEquals(listOf(ThreadId("joint-1")), repository.getThreadRequests)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("joint", payload.channelKind)
        assertEquals(RaftLucideIconName.GitBranch, payload.channelKind.threadChannelHeaderIcon())
        assertEquals("repository body", payload.messages.single().body)
    }
    @Test
    fun repositoryThreadPayloadDoesNotBlockOnMemberProfileAvatarBeforeFirstRender() = runTest {
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages =
                                listOf(
                                    message(id = "m1", seq = 1, body = "agent body", authorKind = MemberKind.Agent)
                                        .copy(
                                            author =
                                                MemberProfile(
                                                    id = MemberId("agent-1"),
                                                    displayName = "Product UX",
                                                    handle = "product-ux",
                                                    kind = MemberKind.Agent,
                                                    avatar = AvatarReference.fromWireValue("https://www.gravatar.com/avatar/agent-hash?s=52&d=404"),
                                                )
                                        )
                                )
                        )
                    )
            )
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                memberRepository = memberRepository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        val message = payload.messages.single()
        assertEquals("pixel:robot", message.avatarWireValue)
        assertEquals(emptyList(), memberRepository.serverMemberRequests)
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
    @Test
    fun fetchReplyThreadPayloadScopesFocusedMessageContextToLocalThreadChannel() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "fallback body")),
            messageContextResult =
                DataResult.Content(
                    threadSnapshot(
                        threadId = "joint-thread-local",
                        messages =
                            listOf(
                                message(
                                    threadId = "joint-thread-local",
                                    channelId = "joint-thread-local",
                                    id = "joint-reply-1",
                                    seq = 44,
                                    body = "joint reply body"
                                )
                            ),
                        conversation =
                            conversationSummary("joint-thread-local").copy(
                                kind = ConversationKind.Thread,
                                channelId = ChannelId("joint-parent-local"),
                                thread =
                                    ThreadConversation(
                                        parentChannelId = ChannelId("joint-parent-local"),
                                        parentMessageId = MessageId("parent-1"),
                                        streamChannelId = ChannelId("joint-thread-local")
                                    )
                            )
                    ).copy(highlightedMessageId = MessageId("joint-reply-1"))
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
        val route =
            basePayload().copy(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "joint-thread-local",
                streamChannelId = "joint-thread-local",
                parentChannelId = "joint-parent-local",
                parentMessageId = "parent-1",
                originParentMessageId = "parent-1",
                focusedMessageId = "joint-reply-1"
            )

        val payload = (dataSource.fetchThreadPayload(route) as DataResult.Content).data

        assertEquals(listOf(MessageId("joint-reply-1")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(ChannelId("joint-thread-local")), repository.getMessageContextChannelIdRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf("joint reply body"), payload.messages.map { it.body })
        assertEquals("joint-reply-1", payload.focusedMessageId)
        assertEquals("joint-thread-local", payload.sourceThreadId)
    }
    @Test
    fun focusedReplyContextKeepsRouteParentAsOrigin() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "fallback body")),
            messageContextResult =
                DataResult.Content(
                    threadSnapshot(
                        threadId = "thread-parent-1",
                        messages =
                            listOf(
                                message(
                                    threadId = "parent-channel-1",
                                    channelId = "parent-channel-1",
                                    id = "parent-1",
                                    seq = 1,
                                    body = "route parent body"
                                ),
                                message(
                                    threadId = "thread-parent-1",
                                    channelId = "thread-parent-1",
                                    id = "reply-focus-1",
                                    seq = 2,
                                    body = "focused reply body"
                                )
                            ),
                        parentMessage =
                            message(
                                threadId = "thread-parent-1",
                                channelId = "thread-parent-1",
                                id = "reply-focus-1",
                                seq = 2,
                                body = "focused reply body"
                            ),
                        conversation =
                            conversationSummary("thread-parent-1").copy(
                                kind = ConversationKind.Thread,
                                channelId = ChannelId("thread-parent-1"),
                                thread =
                                    ThreadConversation(
                                        parentChannelId = ChannelId("parent-channel-1"),
                                        parentMessageId = MessageId("parent-1"),
                                        streamChannelId = ChannelId("thread-parent-1")
                                    )
                            )
                    ).copy(highlightedMessageId = MessageId("reply-focus-1"))
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
        val route =
            basePayload().copy(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-parent-1",
                streamChannelId = "thread-parent-1",
                parentChannelId = "parent-channel-1",
                parentMessageId = "parent-1",
                originParentMessageId = "parent-1",
                focusedMessageId = "reply-focus-1"
            )

        val payload = (dataSource.fetchThreadPayload(route) as DataResult.Content).data

        assertEquals(listOf(MessageId("reply-focus-1")), repository.getMessageContextRequests)
        assertEquals(listOf<ChannelId?>(ChannelId("thread-parent-1")), repository.getMessageContextChannelIdRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals("parent-1", payload.parentMessageId)
        assertEquals("parent-1", payload.originParentMessageId)
        assertEquals("parent-1", payload.originMessage?.id)
        assertEquals("reply-focus-1", payload.focusedMessageId)
        assertEquals(listOf("reply-focus-1"), payload.messages.map { it.id })
    }
    @Test
    fun focusedReplyCommitsTimelineBeforeParentAndCachesLateOrigin() = runTest {
        val parentMessageId = MessageId("parent-1")
        val focusedMessageId = MessageId("reply-focus-1")
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
                                                body = "late parent"
                                            )
                                        )
                                )
                            )
                    )
            ).also { fake ->
                fake.messageContextStartedById[parentMessageId] = parentStarted
                fake.releaseMessageContextById[parentMessageId] = releaseParent
            }
        val messageStore = SharedThreadMessageStore(scope = backgroundScope)
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
        val route = focusedReplyRoute()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(route))
        withTimeout(10_000L) { parentStarted.await() }

        val beforeParent = store.state.value.payload
        assertEquals("content", beforeParent.status)
        assertEquals(listOf("reply-focus-1"), beforeParent.messages.map { it.id })
        assertEquals("loading", beforeParent.originStatus)
        assertNull(beforeParent.originMessage)

        releaseParent.complete(Unit)
        val afterParent =
            withTimeout(10_000L) {
                store.state.map { it.payload }.first { it.originStatus == "loaded" }
            }
        assertEquals(listOf("reply-focus-1"), afterParent.messages.map { it.id })
        assertEquals("late parent", afterParent.originMessage?.body)
        assertEquals(null, afterParent.errorCode)

        val secondScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val secondStore = SharedThreadStore(dataSource = dataSource, scope = secondScope)
        secondStore.accept(SharedThreadIntent.Start(route.copy(focusedMessageRequestNonce = 2)))
        assertEquals("loaded", secondStore.state.value.payload.originStatus)
        assertEquals("late parent", secondStore.state.value.payload.originMessage?.body)
        assertEquals(1, repository.getMessageContextRequests.count { it == parentMessageId })
        scope.cancel()
        secondScope.cancel()
    }
}
