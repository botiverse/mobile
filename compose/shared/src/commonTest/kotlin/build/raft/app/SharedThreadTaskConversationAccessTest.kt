package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.threadPayloadOwnerScopeIdForTest
import build.raft.app.SharedThreadStoreTestFixtures.virtualForegroundLoadGate
import build.raft.app.SharedThreadStoreTestFixtures.unjoinedHomeSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.TaskConversationChannelRef
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Hands fa151b10: a task conversation has no Home row, so its composer waited for the whole
 * canonical load (messages/context, then channels/:id, then channels/:parent) before it mounted.
 * Its write authority is its task channel's, so the task channel's Home row resolves it first.
 */
class SharedThreadTaskConversationAccessTest {
    private val conversationId = "task-conv-1"

    private fun taskRoute(): RaftThreadScreenPayload =
        basePayload().copy(
            instanceId = "shared-shell-thread-$conversationId",
            title = "",
            sourceThreadId = conversationId,
            streamChannelId = conversationId,
            channelKind = RaftTaskConversationChannelKind,
        )

    private fun TestScope.startTaskConversation(home: HomeSnapshot): Triple<SharedThreadStore, FakeConversationRepository, CoroutineScope> {
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = conversationId,
                            body = "network body",
                            conversation =
                                conversationSummary(conversationId).copy(
                                    kind = ConversationKind.Task,
                                    channelType = RaftTaskConversationChannelKind,
                                    joined = true,
                                ),
                        )
                    )
            ).apply {
                // The canonical load never answers in this test: only the Home row can resolve access.
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
                taskConversationRefs =
                    mapOf(
                        ChannelId(conversationId) to
                            TaskConversationChannelRef(
                                conversationChannelId = ChannelId(conversationId),
                                parentChannelId = ChannelId("channel-1"),
                                taskId = TaskId("task-1"),
                            )
                    )
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = FakeChannelRepository { DataResult.Content(home) },
            )
        // The durable / memory frame a reopen shows while the canonical load runs.
        dataSource.cachePayloadForTest(contentPayloadForRoute(taskRoute(), "cached body"))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(taskRoute()))
        return Triple(store, repository, scope)
    }

    @Test
    fun joinedTaskChannelMountsTheComposerBeforeTheCanonicalLoad() = runTest {
        val (store, repository, scope) = startTaskConversation(joinedHomeSnapshot())
        repository.getThreadStarted?.await()
        // Well inside the initial lookup's 1 s deadline; the canonical load is still held.
        advanceTimeBy(500)
        runCurrent()

        val state = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, state.accessResolution, "state=$state")
        assertEquals(listOf("cached body"), state.payload.messages.map { it.body })
        assertFalse(state.canonicalAccessResolved)
        assertTrue(state.writeActionsEnabled)
        assertTrue(state.payload.joined)
        assertTrue(state.payload.canSend)
        assertTrue(state.payload.composer.enabled)
        assertTrue(state.payload.isThreadPrimaryFooterReady(state.timelineSlice, state.accessResolution))
        assertEquals(listOf(ChannelId(conversationId)), repository.taskConversationRefRequests)
        scope.cancel()
    }

    @Test
    fun unjoinedTaskChannelStillWaitsForTheCanonicalLoad() = runTest {
        val (store, repository, scope) = startTaskConversation(unjoinedHomeSnapshot())
        repository.getThreadStarted?.await()
        // Past the initial lookup's own deadline: nothing but the canonical load may resolve it now.
        advanceTimeBy(5_000)
        runCurrent()

        val state = store.state.value
        assertEquals(ThreadAccessResolution.Loading, state.accessResolution)
        assertFalse(state.writeActionsEnabled)
        assertFalse(state.payload.isThreadPrimaryFooterReady(state.timelineSlice, state.accessResolution))
        scope.cancel()
    }

    // --- Hands 1.21.0: the composer appeared ~14.5 s after opening a task conversation. ---

    private fun taskRef(): TaskConversationChannelRef =
        TaskConversationChannelRef(
            conversationChannelId = ChannelId(conversationId),
            parentChannelId = ChannelId("channel-1"),
            taskId = TaskId("task-1"),
        )

    private fun taskConversationThreadResult(
        joined: Boolean = true,
        body: String = "network body",
    ): DataResult.Content<build.raft.app.data.ThreadSnapshot> =
        DataResult.Content(
            threadSnapshot(
                threadId = conversationId,
                body = body,
                conversation =
                    conversationSummary(conversationId).copy(
                        kind = ConversationKind.Task,
                        channelType = RaftTaskConversationChannelKind,
                        joined = joined,
                        permissions =
                            build.raft.app.model.ConversationPermissions(canSend = joined, canAttach = joined),
                    ),
            )
        )

    private fun TestScope.taskDataSource(
        repository: FakeConversationRepository,
        home: HomeSnapshot,
        payloadStore: SharedThreadPayloadStore =
            SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate()),
    ): SharedThreadDataSource =
        testSharedThreadDataSource(
            storeScope = backgroundScope,
            networkApi = FakeThreadApi(failChannelMessages = true),
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            conversationRepository = repository,
            channelRepository = FakeChannelRepository { DataResult.Content(home) },
            payloadStore = payloadStore,
        ).also { it.cachePayloadForTest(contentPayloadForRoute(taskRoute(), "cached body")) }

    private fun TestScope.openTaskConversation(
        dataSource: SharedThreadDataSource,
        route: RaftThreadScreenPayload = taskRoute(),
    ): Pair<SharedThreadStore, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(route))
        return store to scope
    }

    private fun SharedThreadState.composerReady(): Boolean =
        accessResolution == ThreadAccessResolution.Resolved &&
            writeActionsEnabled &&
            payload.isThreadPrimaryFooterReady(timelineSlice, accessResolution)

    /** (a) The 1 s initial-access deadline used to cancel the ref fetch; a late ref must still resolve. */
    @Test
    fun refSlowerThanTheInitialDeadlineStillResolvesAccessWhenItArrives() = runTest {
        val repository =
            FakeConversationRepository(threadResult = taskConversationThreadResult()).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred() // the canonical load never answers
                taskConversationRefs = mapOf(ChannelId(conversationId) to taskRef())
                taskConversationRefDelayMillis = 1_900
            }
        val (store, scope) = openTaskConversation(taskDataSource(repository, joinedHomeSnapshot()))
        repository.getThreadStarted?.await()
        advanceTimeBy(1_200)
        runCurrent()
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution, "deadline passed, ref pending")

        advanceTimeBy(1_000)
        runCurrent()
        val state = store.state.value
        assertTrue(state.composerReady(), "state=$state")
        assertFalse(state.canonicalAccessResolved)
        scope.cancel()
    }

    /** (b) Realtime generation bumps during the canonical fetch must not hold access hostage. */
    @Test
    fun accessResolvesFromTheFirstCanonicalResultDespiteGenerationBumps() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = FakeConversationRepository(threadResult = taskConversationThreadResult())
        // No ref: the Home lookup cannot produce a candidate, only the canonical load can.
        val dataSource = taskDataSource(repository, joinedHomeSnapshot(), payloadStore)
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        var bumps = 0
        repository.threadDelayMillis = 2_000
        repository.threadResultProvider = { _, _, _ ->
            // A realtime event lands during every fetch (bounded so the old unbounded loop ends).
            if (bumps++ < 10) payloadStore.freshnessLedger.advance("server-1", conversationId, ownerScopeId)
            taskConversationThreadResult()
        }
        val (store, scope) = openTaskConversation(dataSource)
        advanceTimeBy(2_100)
        runCurrent()

        val state = store.state.value
        assertTrue(state.composerReady(), "state=$state")
        // Resolved by the first (superseded) fetch, while its refetch is still backing off.
        assertEquals(1, repository.getThreadRequests.size)
        // Provisional: the final generation-current result still records its own access.
        assertTrue(state.provisionalCanonicalAccess)
        assertFalse(state.canonicalAccessResolved)
        // The superseded content is not shown: the page still shows its cached frame.
        assertEquals(listOf("cached body"), state.payload.messages.map { it.body })
        scope.cancel()
    }

    /** (c) Continuous generation bumps: at most 2 extra rounds, then the latest result is accepted. */
    @Test
    fun staleCanonicalRefetchStopsAfterTheCap() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = FakeConversationRepository(threadResult = taskConversationThreadResult())
        val dataSource = taskDataSource(repository, joinedHomeSnapshot(), payloadStore)
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        var bumps = 0
        repository.threadDelayMillis = 10
        repository.threadResultProvider = { _, _, _ ->
            if (bumps++ < 10) payloadStore.freshnessLedger.advance("server-1", conversationId, ownerScopeId)
            taskConversationThreadResult()
        }
        val context = requireNotNull(dataSource.captureReplyCacheProducerContext())
        val request =
            async {
                dataSource.fetchThreadPayloadForContext(
                    basePayload = taskRoute(),
                    expectedContext = context,
                )
            }
        val result = request.await()

        assertIs<DataResult.Content<RaftThreadScreenPayload>>(result)
        // 1 fetch + 2 capped extra rounds (the old loop refetched until the bumps stopped: 11).
        assertEquals(3, repository.getThreadRequests.size)
    }

    /** (d) The ref never changes: a reopen resolves access from the cached ref without a request. */
    @Test
    fun cachedRefResolvesAccessInstantlyOnReopen() = runTest {
        val repository =
            FakeConversationRepository(threadResult = taskConversationThreadResult()).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
                taskConversationRefs = mapOf(ChannelId(conversationId) to taskRef())
            }
        val dataSource = taskDataSource(repository, joinedHomeSnapshot())
        val (first, firstScope) = openTaskConversation(dataSource)
        repository.getThreadStarted?.await()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(first.state.value.composerReady())
        firstScope.cancel()

        // Reopen: the ref endpoint is now slower than the whole deadline.
        repository.taskConversationRefDelayMillis = 30_000
        val (second, secondScope) = openTaskConversation(dataSource)
        advanceTimeBy(50)
        runCurrent()
        val state = second.state.value
        assertTrue(state.composerReady(), "state=$state")
        assertEquals(listOf(ChannelId(conversationId)), repository.taskConversationRefRequests)
        secondScope.cancel()
    }

    // --- Review of b18ff291 ---

    /** An early (superseded) positive is provisional: a negative final result still closes the composer. */
    @Test
    fun negativeFinalResultAfterASupersededPositiveClosesTheComposer() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = FakeConversationRepository(threadResult = taskConversationThreadResult())
        val dataSource = taskDataSource(repository, joinedHomeSnapshot(), payloadStore)
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        var calls = 0
        repository.threadDelayMillis = 2_000
        repository.threadResultProvider = { _, _, _ ->
            if (calls++ == 0) {
                // A realtime event lands during the first fetch, which still says "joined".
                payloadStore.freshnessLedger.advance("server-1", conversationId, ownerScopeId)
                taskConversationThreadResult(joined = true)
            } else {
                // Removed from the task channel meanwhile: the generation-current answer is negative.
                taskConversationThreadResult(joined = false)
            }
        }
        val (store, scope) = openTaskConversation(dataSource)
        advanceTimeBy(2_100)
        runCurrent()
        assertTrue(store.state.value.composerReady(), "state=${store.state.value}")
        assertTrue(store.state.value.payload.composer.enabled)

        advanceTimeBy(3_000)
        runCurrent()
        val state = store.state.value
        assertEquals(2, repository.getThreadRequests.size)
        assertTrue(state.canonicalAccessResolved)
        assertFalse(state.provisionalCanonicalAccess)
        // The page mounts the composer only for a joined conversation (ThreadPage `mountComposer`).
        assertFalse(state.payload.joined, "state=$state")
        assertFalse(state.payload.canSend)
        scope.cancel()
    }

    /** (c) at the store: capped superseded content is not shown or cached as final; one more refresh lands. */
    @Test
    fun cappedRefetchKeepsVisibleContentAndRefreshesOnceMore() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        val repository = FakeConversationRepository(threadResult = taskConversationThreadResult())
        val dataSource = taskDataSource(repository, joinedHomeSnapshot(), payloadStore)
        val ownerScopeId = dataSource.threadPayloadOwnerScopeIdForTest()
        var calls = 0
        repository.threadDelayMillis = 10
        repository.threadResultProvider = { _, _, _ ->
            val call = ++calls
            // Every fetch of the first refresh is superseded; the follow-up's fetch is current.
            if (call <= 3) payloadStore.freshnessLedger.advance("server-1", conversationId, ownerScopeId)
            taskConversationThreadResult(body = if (call <= 3) "superseded body" else "network body")
        }
        val (store, scope) = openTaskConversation(dataSource)
        while (repository.getThreadRequests.size < 3) advanceTimeBy(10)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(3, repository.getThreadRequests.size)
        assertEquals(listOf("cached body"), store.state.value.payload.messages.map { it.body })
        // Nothing was cached from the superseded rounds.
        val cachedBodies =
            payloadStore
                .get("server-1", conversationId, TestThreadEnvironmentId, TestThreadPrincipalId, ownerScopeId)
                ?.messages
                ?.map { it.body }
                .orEmpty()
        assertFalse("superseded body" in cachedBodies, "cached=$cachedBodies")
        assertTrue(store.state.value.composerReady(), "access is provisional from the first round")

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(4, repository.getThreadRequests.size)
        assertEquals(listOf("network body"), store.state.value.payload.messages.map { it.body })
        scope.cancel()
    }

    /** The late lookup is bounded: a ref answering after 15 s is left to the canonical load. */
    @Test
    fun lateRefLookupGivesUpAfterItsBound() = runTest {
        val repository =
            FakeConversationRepository(threadResult = taskConversationThreadResult()).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
                taskConversationRefs = mapOf(ChannelId(conversationId) to taskRef())
                taskConversationRefDelayMillis = 20_000
            }
        val (store, scope) = openTaskConversation(taskDataSource(repository, joinedHomeSnapshot()))
        repository.getThreadStarted?.await()
        advanceTimeBy(21_000)
        runCurrent()
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(store.state.value.writeActionsEnabled)
        scope.cancel()
    }

    /** A late ref never applies to the route the page moved to. */
    @Test
    fun lateRefDoesNotApplyAfterARouteChange() = runTest {
        val otherId = "task-conv-2"
        val repository =
            FakeConversationRepository(threadResult = taskConversationThreadResult()).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
                taskConversationRefs = mapOf(ChannelId(conversationId) to taskRef())
                taskConversationRefDelayMillis = 1_900
            }
        val (store, scope) = openTaskConversation(taskDataSource(repository, joinedHomeSnapshot()))
        repository.getThreadStarted?.await()
        advanceTimeBy(1_200)
        runCurrent()
        val otherRoute =
            taskRoute().copy(
                instanceId = "shared-shell-thread-$otherId",
                sourceThreadId = otherId,
                streamChannelId = otherId,
            )
        store.dispatch(SharedThreadIntent.Start(otherRoute))
        advanceTimeBy(1_500)
        runCurrent()

        val state = store.state.value
        assertEquals(otherId, state.payload.sourceThreadId)
        assertEquals(ThreadAccessResolution.Loading, state.accessResolution, "state=$state")
        assertFalse(state.writeActionsEnabled)
        scope.cancel()
    }

    /** A late ref never reopens access after the canonical load answered NoPermission. */
    @Test
    fun lateRefDoesNotApplyAfterCanonicalNoPermission() = runTest {
        val repository =
            FakeConversationRepository(threadResult = DataResult.NoPermission(PermissionBlocker("channel_forbidden"))).apply {
                taskConversationRefs = mapOf(ChannelId(conversationId) to taskRef())
                taskConversationRefDelayMillis = 1_900
                threadDelayMillis = 1_200
            }
        val (store, scope) = openTaskConversation(taskDataSource(repository, joinedHomeSnapshot()))
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(ThreadAccessResolution.NoPermission, store.state.value.accessResolution)

        advanceTimeBy(1_000)
        runCurrent()
        val state = store.state.value
        assertEquals(ThreadAccessResolution.NoPermission, state.accessResolution, "state=$state")
        assertFalse(state.writeActionsEnabled)
        assertFalse(state.payload.composer.enabled)
        scope.cancel()
    }

    /** Sign-out / account retirement drops cached refs; other accounts keep theirs. */
    @Test
    fun cachedRefsLeaveWithTheSessionAndTheAccount() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        fun key(principal: String) =
            SharedTaskConversationRefKey(TestThreadEnvironmentId, principal, "server-1", conversationId)
        payloadStore.taskConversationRef(key("user-1")) { DataResult.Content(taskRef()) }
        payloadStore.taskConversationRef(key("user-2")) { DataResult.Content(taskRef()) }
        assertNotNull(payloadStore.cachedTaskConversationRef(key("user-1")))

        payloadStore.retireAccount(TestThreadEnvironmentId, "user-1")
        assertNull(payloadStore.cachedTaskConversationRef(key("user-1")))
        assertNotNull(payloadStore.cachedTaskConversationRef(key("user-2")))

        payloadStore.clearAll()
        assertNull(payloadStore.cachedTaskConversationRef(key("user-2")))
    }

    /** Finished, failed and cleared ref fetches all leave the flight map. */
    @Test
    fun refFlightsLeaveTheMapWhenTheyEnd() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope, foregroundLoadGate = virtualForegroundLoadGate())
        fun key(id: String) = SharedTaskConversationRefKey(TestThreadEnvironmentId, "user-1", "server-1", id)
        payloadStore.taskConversationRef(key("a")) { DataResult.Content(taskRef()) }
        payloadStore.taskConversationRef(key("b")) { DataResult.Error(build.raft.app.data.ScreenError(code = "x", retryable = true)) }
        assertEquals(0, payloadStore.taskConversationRefFlightCountForTest())

        val waiter = async { payloadStore.taskConversationRef(key("c")) { CompletableDeferred<Nothing>().await() } }
        runCurrent()
        assertEquals(1, payloadStore.taskConversationRefFlightCountForTest())
        waiter.cancel() // the caller stops waiting; the shared fetch keeps running
        runCurrent()
        assertEquals(1, payloadStore.taskConversationRefFlightCountForTest())
        payloadStore.clearAll()
        runCurrent()
        assertEquals(0, payloadStore.taskConversationRefFlightCountForTest())
    }
}
