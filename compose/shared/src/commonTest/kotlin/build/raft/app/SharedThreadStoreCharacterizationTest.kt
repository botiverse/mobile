package build.raft.app

import build.raft.app.SharedThreadStoreMarkThreadUnreadTest.RecordingReadReceiptRepository
import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.data.ChannelSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.ReadReceiptCoordinator
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.ReadReceiptResult
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Characterization of [SharedThreadStore] before it is split into collaborator classes
 * (#proj-mobile:#166, plan section 4 and the Lagoon review invariants). These cases pin what the
 * store does TODAY, including the exact diagnostics lines scripts and telemetry parse; a refactor
 * that only moves code must keep every one of them green without edits.
 *
 * Pinned here:
 * - Start vs Refresh cancel different job sets (a deferred parent-directory hint survives a
 *   Refresh and is replayed; a Start cancels it).
 * - The visible-read coalesce window on the scheduler's virtual clock.
 * - Where the route stale token is checked after the refresh's suspension points.
 * - Realtime events dispatched from a background (Dispatchers.Default) thread.
 */
class SharedThreadStoreCharacterizationTest {
    @BeforeTest
    fun forgetLounges() {
        RaftAgentLounges.clear()
    }

    @AfterTest
    fun forgetLoungesAfter() {
        RaftAgentLounges.clear()
    }

    // ---- 1. Start vs Refresh job cancellation -------------------------------------------------

    /**
     * `dispatch` cancels the initial-access, reply-parent authority fallback, deferred
     * parent-directory hint and capped refresh follow-up jobs on Start only. The deferred hint is
     * the one of those a Refresh visibly keeps: it waits on state alone (no stale token), so after
     * a Refresh it is replayed once the access passes ran, and after a Start it is gone.
     *
     * (The initial-access and parent-authority fallback jobs are relaunched by the refresh itself
     * while access is unresolved, and the capped follow-up checks the route stale token that a
     * Refresh advances, so their Start-only cancellation has no state difference to observe.)
     */
    @Test
    fun aDeferredParentDirectoryHintSurvivesRefreshButNotStart() = runTest {
        data class Case(val restart: SharedThreadIntent, val replayed: Boolean)
        val cases =
            listOf(
                Case(restart = SharedThreadIntent.Refresh, replayed = true),
                Case(restart = SharedThreadIntent.Start(replyThreadInitialAccessPayload(parentChannelId = HintParentId)), replayed = false),
            )
        for (case in cases) {
            val name = case.restart::class.simpleName.orEmpty()
            val harness = hintHarness()
            harness.store.accept(
                SharedThreadIntent.ApplyParentDirectoryHint(
                    parentChannelId = HintParentId,
                    parentMessageId = "m1",
                    present = true,
                    joined = true,
                    canSend = true,
                    authoritative = true,
                )
            )
            assertEquals(
                listOf("thread_parent_authority_hint_deferred threadId=thread-m1 joined=true canSend=true"),
                harness.logs.filter { it.startsWith("thread_parent_authority_hint_deferred") },
                name,
            )
            assertFalse(harness.store.state.value.writeActionsEnabled, "$name: nothing granted before the lookup")

            harness.store.dispatch(case.restart)
            harness.releaseLookup.complete(Unit)
            val settled =
                awaitThreadState(harness.store, "$name: access passes ran") { state ->
                    state.initialHomeAccessLookupComplete &&
                        state.canonicalAccessResolved &&
                        (!case.replayed || state.replyParentAuthority == ReplyParentAuthorityStatus.Writable)
                }
            testScheduler.runCurrent()

            val replayed = harness.logs.filter { it.startsWith("thread_parent_authority_hint_replayed") }
            if (case.replayed) {
                assertEquals(1, replayed.size, "$name: $replayed")
                assertTrue(replayed.single().startsWith("thread_parent_authority_hint_replayed threadId=thread-m1 authority="), name)
                assertEquals(ReplyParentAuthorityStatus.Writable, harness.store.state.value.replyParentAuthority, name)
                assertTrue(harness.store.state.value.writeActionsEnabled, name)
            } else {
                assertTrue(replayed.isEmpty(), "$name: the Start cancelled the deferred hint: $replayed")
                assertTrue(settled.replyParentAuthority.isUndeterminedReplyParentAuthority(), "$name: ${settled.replyParentAuthority}")
                assertTrue(harness.store.state.value.replyParentAuthority.isUndeterminedReplyParentAuthority(), name)
                assertFalse(harness.store.state.value.writeActionsEnabled, name)
            }
            // The fallback window never opens in this harness: no parent read either way.
            assertTrue(harness.conversations.conversationDetailRequests.isEmpty(), name)
            harness.close()
        }
    }

    private class HintHarness(
        val store: SharedThreadStore,
        val conversations: ReplyThreadAccessConversationRepositoryRecorder,
        val releaseLookup: CompletableDeferred<Unit>,
        val logs: List<String>,
        private val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()
    }

    /**
     * A reply thread whose parent is an ordinary channel missing from the Home directory: the
     * parent lookup (the channel list) waits on [HintHarness.releaseLookup], so a hint sent now
     * is deferred. The lookup deadline and the parent-authority fallback window never fire.
     */
    private suspend fun TestScope.hintHarness(): HintHarness {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val logs = mutableListOf<String>()
        val releaseLookup = CompletableDeferred<Unit>()
        val home = replyThreadAccessHomeSnapshot()
        val channelRepository =
            FakeChannelRepository(
                channelsResult = { serverId ->
                    releaseLookup.await()
                    DataResult.Content(ChannelSnapshot(server = home.server.copy(id = serverId), channels = home.channels))
                },
            )
        val conversations =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot(parentChannelIdValue = HintParentId))
            )
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = conversations,
                        channelRepository = channelRepository,
                    ),
                scope = scope,
                diagnosticsLogger = { logs.add(it) },
                initialAccessLookupDeadline = { CompletableDeferred<Unit>().await() },
                replyParentAuthorityFallbackDelay = { CompletableDeferred<Unit>().await() },
            )
        store.accept(SharedThreadIntent.Start(replyThreadInitialAccessPayload(parentChannelId = HintParentId)))
        return HintHarness(store, conversations, releaseLookup, logs, scope)
    }

    // ---- 2. Visible-read coalescing on the virtual clock --------------------------------------

    /**
     * The 2 s coalesce window after a completed visible read: reads asked for inside it go out once,
     * when it ends, for the newest marker shown; after it a read goes out at once; a Mark as Unread
     * hold on the open conversation suppresses reads altogether.
     */
    @Test
    fun visibleReadsCoalesceInsideTheWindowFireAfterItAndStayHeldAfterMarkUnread() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val logs = mutableListOf<String>()
        val repository = RecordingReadReceiptRepository(DataResult.Content(ReadReceiptResult(unreadCount = 3, readStateVersion = 7L)))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false },
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                    ),
                readReceiptCoordinator = ReadReceiptCoordinator(scope = scope, repository = repository),
                readReceiptOwnerFactory = { serverId, accountId -> ReadReceiptOwner("production", accountId, serverId) },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = { logs.add(it) },
                readCoalesceTimeSource = testScheduler.timeSource,
            )
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.MarkVisibleRead())
        val windowStart = testScheduler.currentTime
        assertEquals(listOf("read:channel-1"), repository.calls)

        // Inside the window: two newer markers, each deferred to the window's end.
        testScheduler.advanceTimeBy(500)
        store.accept(realtimeMessage("m2", 11L))
        store.accept(SharedThreadIntent.MarkVisibleRead())
        val firstDefer = CoalesceWindowMillis - (testScheduler.currentTime - windowStart)
        testScheduler.advanceTimeBy(500)
        store.accept(realtimeMessage("m3", 12L))
        store.accept(SharedThreadIntent.MarkVisibleRead())
        val secondDefer = CoalesceWindowMillis - (testScheduler.currentTime - windowStart)
        assertEquals(listOf("read:channel-1"), repository.calls, "coalesced inside the window")
        assertEquals(
            listOf(
                "thread_read_skip channelId=channel-1 reason=recently_marked latest=m2 deferredMs=$firstDefer",
                "thread_read_skip channelId=channel-1 reason=recently_marked latest=m3 deferredMs=$secondDefer",
            ),
            logs.filter { it.startsWith("thread_read_skip") },
        )

        // The deferred retry fires exactly when the window ends, once, with the newest seq.
        testScheduler.advanceTimeBy(secondDefer - 1)
        testScheduler.runCurrent()
        assertEquals(listOf("read:channel-1"), repository.calls, "one ms before the window ends")
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(listOf("read:channel-1", "read:channel-1@12"), repository.calls)
        assertTrue(
            logs.any { it.startsWith("thread_read_result channelId=channel-1 result=content mode=seq seq=12 latest=m3 durationMs=") },
            logs.toString(),
        )

        // A full window after that read, a newer marker goes out at once (no deferral).
        testScheduler.advanceTimeBy(CoalesceWindowMillis)
        store.accept(realtimeMessage("m4", 13L))
        store.accept(SharedThreadIntent.MarkVisibleRead())
        assertEquals(listOf("read:channel-1", "read:channel-1@12", "read:channel-1@13"), repository.calls)

        // Mark as Unread on the open conversation holds every later visible read.
        store.accept(SharedThreadIntent.MarkThreadUnread("channel-1"))
        assertTrue(logs.contains("thread_mark_unread channelId=channel-1 result=content outcome=Counted"), logs.toString())
        store.accept(realtimeMessage("m5", 14L))
        store.accept(SharedThreadIntent.MarkVisibleRead())
        testScheduler.advanceTimeBy(5 * CoalesceWindowMillis)
        testScheduler.runCurrent()
        assertEquals(
            listOf("read:channel-1", "read:channel-1@12", "read:channel-1@13", "unread:channel-1"),
            repository.calls,
        )
        assertTrue(
            logs.contains("thread_read_skip channelId=channel-1 reason=marked_unread_hold latest=m5"),
            logs.toString(),
        )
        scope.cancel()
    }

    // ---- 3. Route stale token after suspension -----------------------------------------------

    /**
     * Route A's refresh passes its route checks, applies its loading state and is parked right after
     * (the `afterRefreshLoadingStateApplied` hook); route B starts and finishes meanwhile. A then
     * runs its remaining suspension points (first-frame delay, the fetch - which still goes out)
     * and is dropped only at the `after_fetch` stale-token check, never overwriting B.
     */
    @Test
    fun aSupersededRoutesRefreshIsDroppedAtTheAfterFetchStaleTokenCheck() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val logs = mutableListOf<String>()
        val api =
            FakeThreadApi().apply {
                bodyTextByThreadId["channel-1"] = messagesResponse("route a body", id = "a1", channelId = "channel-1")
                bodyTextByThreadId["channel-2"] = messagesResponse("route b body", id = "b1", channelId = "channel-2")
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val routeAParked = CompletableDeferred<Unit>()
        val releaseRouteA = CompletableDeferred<Unit>()
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = { logs.add(it) },
                afterRefreshLoadingStateApplied = { payload ->
                    if (payload.sourceThreadId == "channel-1" && routeAParked.complete(Unit)) {
                        releaseRouteA.await()
                    }
                },
            )
        val routeB = basePayload().copy(instanceId = "shared-shell-thread-channel-2", sourceThreadId = "channel-2")

        val routeAJob = scope.launch { store.accept(SharedThreadIntent.Start(basePayload())) }
        routeAParked.await()
        assertFalse("channel-1" in api.channelIds, "route A is parked before its fetch")

        scope.launch { store.accept(SharedThreadIntent.Start(routeB)) }
        awaitThreadState(store, "route B content") { state ->
            state.payload.sourceThreadId == "channel-2" && state.payload.messages.any { it.id == "b1" }
        }

        releaseRouteA.complete(Unit)
        routeAJob.join()

        assertTrue("channel-1" in api.channelIds, "route A's fetch still went out: ${api.channelIds}")
        assertEquals(
            listOf(
                "thread_refresh_stale_drop threadId=channel-1 currentThreadId=channel-2 stage=after_fetch " +
                    "reason=stale_token token=1 currentToken=2"
            ),
            logs.filter { it.startsWith("thread_refresh_stale_drop") },
        )
        val routeAResult = logs.filter { it.startsWith("thread_refresh_result threadId=channel-1 ") }
        assertEquals(1, routeAResult.size, routeAResult.toString())
        assertTrue(
            routeAResult.single().startsWith(
                "thread_refresh_result threadId=channel-1 result=content ignored=true reason=stale_route durationMs="
            ),
            routeAResult.single(),
        )
        val shown = store.state.value.payload
        assertEquals("channel-2", shown.sourceThreadId)
        assertEquals(listOf("b1"), shown.messages.map { it.id })
        scope.cancel()
    }

    // ---- 5. Realtime off the main thread -----------------------------------------------------

    /**
     * Lagoon review invariant 1: realtime events reach the store on Dispatchers.Default. The same
     * `message:new` dispatched from a Default thread into a store whose scope is Default lands in
     * state exactly as it does on the caller's dispatcher.
     *
     * Real threads on purpose: the property is "nothing assumes the main / test thread", which a
     * virtual-time dispatcher cannot express. Waits are event-driven `state.first` (no polling),
     * bounded only so a regression fails with the state instead of hanging.
     */
    @Test
    fun realtimeMessageDispatchedFromADefaultThreadLandsAsOnTheCallersDispatcher() = runBlocking {
        val event =
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        seq = 11L,
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "fresh body"
                    )
                )
            )

        val referenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reference = SharedThreadStore(dataSource = realtimeDataSource(), scope = referenceScope)
        reference.accept(SharedThreadIntent.Start(basePayload()))
        awaitThreadState(reference, "reference write authority") { it.accessResolution == ThreadAccessResolution.Resolved }
        reference.accept(event)
        val expected = awaitThreadState(reference, "reference m2") { state -> state.payload.messages.any { it.id == "m2" } }
        referenceScope.cancel()

        val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SharedThreadStore(dataSource = realtimeDataSource(), scope = defaultScope)
            store.accept(SharedThreadIntent.Start(basePayload()))
            awaitThreadState(store, "write authority") { it.accessResolution == ThreadAccessResolution.Resolved }
            withContext(Dispatchers.Default) { store.dispatch(event) }
            val landed = awaitThreadState(store, "m2 from a Default thread") { state -> state.payload.messages.any { it.id == "m2" } }

            assertEquals(expected.payload.status, landed.payload.status)
            assertEquals(
                expected.payload.messages.filter { it.id == "m2" }.map { it.id to it.body },
                landed.payload.messages.filter { it.id == "m2" }.map { it.id to it.body },
            )
            assertEquals(listOf("m2" to "fresh body"), landed.payload.messages.filter { it.id == "m2" }.map { it.id to it.body })
            assertEquals(expected.payload.messages.map { it.id }.toSet(), landed.payload.messages.map { it.id }.toSet())
        } finally {
            defaultScope.cancel()
        }
    }

    /** Production store defaults (no test `storeScope`): the message / payload stores run on Default. */
    private fun realtimeDataSource(): SharedThreadDataSource =
        testSharedThreadDataSource(
            networkApi = FakeThreadApi(failChannelMessages = true),
            sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            conversationRepository =
                FakeConversationRepository(
                    threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                ),
        ).also { it.cachePayloadForTest(contentPayload("cached body")) }

    private fun realtimeMessage(id: String, seq: Long) =
        SharedThreadIntent.ApplyRealtimeEvent(
            MessageNew(
                Message(
                    id = MessageId(id),
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    seq = seq,
                    author = member("u2", "Stdrc"),
                    timestampLabel = "2026-05-19T16:01:00Z",
                    body = "body $id"
                )
            )
        )

    private companion object {
        /** ThreadVisibleReadCoalesceWindowMillis (private in SharedThreadStore.kt). */
        const val CoalesceWindowMillis = 2_000L
        const val HintParentId = "channel-design"
    }
}

private suspend fun awaitThreadState(
    store: SharedThreadStore,
    reason: String,
    predicate: (SharedThreadState) -> Boolean,
): SharedThreadState =
    try {
        withTimeout(10_000) { store.state.first(predicate) }
    } catch (_: TimeoutCancellationException) {
        val state = store.state.value
        error(
            "waited for $reason: thread=${state.payload.sourceThreadId} status=${state.payload.status} " +
                "messages=${state.payload.messages.map { it.id }} access=${state.accessResolution} " +
                "authority=${state.replyParentAuthority} lookupComplete=${state.initialHomeAccessLookupComplete} " +
                "canonical=${state.canonicalAccessResolved} writeActions=${state.writeActionsEnabled}"
        )
    }
