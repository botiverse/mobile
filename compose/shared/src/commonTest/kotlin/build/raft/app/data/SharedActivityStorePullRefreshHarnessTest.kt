package build.raft.app.data

import build.raft.app.ReplyThreadAccessConversationRepositoryRecorder
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic load-generation harness for task #445.
 *
 * These cases exercise SharedActivityStore's mechanism only. They do not model a Kuikly gesture,
 * prove that a mounted panel can reach provider teardown, or claim a cause for Hands feedback
 * #436. A pending repository response is an explicit barrier, so cancelled generations can be
 * completed deliberately and their late continuations can be observed without wall-clock polling
 * or duration assertions. Each await is preceded by an immediate completion assertion, so a
 * missing transition fails contextually instead of hanging. The portable scope avoids
 * kotlinx-coroutines-test because the OHOS commonTest leg has no test KLIB.
 */
class SharedActivityStorePullRefreshHarnessTest {
    @Test
    fun realtimeWaitsForForegroundPullThenBackgroundFailureKeepsFlagClear() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = PullRefreshInboxHarness()
        val diagnostics = mutableListOf<String>()
        val store = pullRefreshStore(scope, inbox) { diagnostics += it }

        val initialStarted = inbox.expectNextInitialLoadStarted()
        store.dispatch(SharedActivityIntent.LoadInitial)
        initialStarted.awaitBarrier("initial load")
        inbox.completeNextSnapshot(inboxSnapshot())
        assertTrue(store.state.value.loaded)
        assertFalse(store.state.value.pullRefreshing)

        val foregroundStarted = inbox.expectNextInitialLoadStarted()
        store.dispatch(SharedActivityIntent.PullRefresh)
        foregroundStarted.awaitBarrier("foreground pull refresh")
        assertTrue(store.state.value.pullRefreshing)

        // Register before completing the foreground barrier. If background were allowed to jump
        // the owner, this waiter would complete while the foreground request is still pending.
        val backgroundStarted = inbox.expectNextInitialLoadStarted()
        val realtime =
            scope.launch {
                store.acceptRealtime(
                    SharedActivityIntent.ApplyRealtimeEvent(
                        SyncResumeResponse(messages = emptyList(), currentSeq = 1L, hasMore = false)
                    )
                )
            }
        assertFalse(backgroundStarted.isCompleted, "background reconcile must wait for pull owner")
        assertEquals(1, inbox.pendingCount)

        inbox.completeNextSnapshot(inboxSnapshot(listOf(activityItem("channel-1"))))
        val backgroundRequest = backgroundStarted.awaitBarrier("background reconcile after foreground")

        val backgroundStartLine =
            diagnostics.single { line ->
                line.contains("phase=start") && line.contains("background=true")
            }
        assertTrue(
            backgroundStartLine.contains("pullRefreshingBefore=false"),
            "background must start only after foreground terminal state clears the flag: $backgroundStartLine"
        )
        assertFalse(store.state.value.pullRefreshing)

        inbox.complete(backgroundRequest, DataResult.Error(ScreenError("background_failed")))
        realtime.joinBarrier("realtime background reconcile")
        assertFalse(store.state.value.pullRefreshing, "background failure must not re-contaminate the flag")
        scope.cancel()
    }

    @Test
    fun cancelledOlderPullGenerationsCannotClearNewestFlag() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = PullRefreshInboxHarness()
        val abandonedGenerations = mutableListOf<Long>()
        val abandonedSignals =
            listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val store =
            pullRefreshStore(scope, inbox) { line ->
                if (line.contains("phase=abandoned") && line.contains("pullRefresh=true")) {
                    val generation = line.substringAfter("generation=").substringBefore(' ').toLong()
                    abandonedGenerations += generation
                    abandonedSignals.getOrNull(abandonedGenerations.size - 1)?.complete(Unit)
                }
            }

        val initialStarted = inbox.expectNextInitialLoadStarted()
        store.dispatch(SharedActivityIntent.LoadInitial)
        initialStarted.awaitBarrier("initial load")
        inbox.completeNextSnapshot(inboxSnapshot())

        val aStarted = inbox.expectNextInitialLoadStarted()
        store.dispatch(SharedActivityIntent.PullRefresh)
        aStarted.awaitBarrier("pull generation A")
        assertTrue(store.state.value.pullRefreshing)

        val bStarted = inbox.expectNextInitialLoadStarted()
        store.dispatch(SharedActivityIntent.PullRefresh)
        bStarted.awaitBarrier("pull generation B")
        assertTrue(store.state.value.pullRefreshing)

        val cStarted = inbox.expectNextInitialLoadStarted()
        store.dispatch(SharedActivityIntent.PullRefresh)
        cStarted.awaitBarrier("pull generation C")
        assertTrue(store.state.value.pullRefreshing)

        // A and B are cancelled, but their underlying deferred responses remain completable. Their
        // late continuations must be abandoned by generation check and leave C's flag untouched.
        inbox.completeNextSnapshot(inboxSnapshot(listOf(activityItem("a-late"))))
        abandonedSignals[0].awaitBarrier("generation A cancellation")
        assertTrue(store.state.value.pullRefreshing, "A must not clear C's flag")

        inbox.completeNextSnapshot(inboxSnapshot(listOf(activityItem("b-late"))))
        abandonedSignals[1].awaitBarrier("generation B cancellation")
        assertTrue(store.state.value.pullRefreshing, "B must not clear C's flag")

        inbox.completeNextSnapshot(inboxSnapshot(listOf(activityItem("c-current"))))
        assertFalse(store.state.value.pullRefreshing, "only current C termination owns flag clearing")
        assertEquals(listOf(2L, 3L), abandonedGenerations)
        scope.cancel()
    }

    @Test
    fun clearCancelsCurrentLoadAndLeavesFlagForBackgroundFailureMechanismCandidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val inbox = PullRefreshInboxHarness()
        val diagnostics = mutableListOf<String>()
        val store = pullRefreshStore(scope, inbox) { diagnostics += it }

        val initialStarted = inbox.expectNextInitialLoadStarted()
        store.dispatch(SharedActivityIntent.LoadInitial)
        initialStarted.awaitBarrier("initial load")
        inbox.completeNextSnapshot(inboxSnapshot())

        val foregroundStarted = inbox.expectNextInitialLoadStarted()
        store.dispatch(SharedActivityIntent.PullRefresh)
        foregroundStarted.awaitBarrier("foreground pull refresh")
        assertTrue(store.state.value.pullRefreshing)

        // clear() is intentionally called directly. The test proves the lifecycle mechanism
        // (loadJob is cancelled/removed while state retains the flag), not a user-reachable path.
        store.clear()
        assertTrue(store.state.value.pullRefreshing)
        assertEquals(1, inbox.pendingCount, "the cancelled foreground barrier remains completable")

        val backgroundStarted = inbox.expectNextInitialLoadStarted()
        val realtime =
            scope.launch {
                store.acceptRealtime(
                    SharedActivityIntent.ApplyRealtimeEvent(
                        SyncResumeResponse(messages = emptyList(), currentSeq = 1L, hasMore = false)
                    )
                )
            }
        val backgroundRequest = backgroundStarted.awaitBarrier("background reconcile after clear")
        val backgroundStartLine =
            diagnostics.single { line ->
                line.contains("phase=start") && line.contains("background=true")
            }
        assertTrue(
            backgroundStartLine.contains("pullRefreshingBefore=true"),
            "the direct clear candidate preserves the flag into background start: $backgroundStartLine"
        )

        inbox.complete(backgroundRequest, DataResult.Error(ScreenError("background_failed")))
        realtime.joinBarrier("realtime background reconcile after clear")
        assertTrue(store.state.value.pullRefreshing, "background failure must preserve the candidate flag")

        // Release the cancelled foreground barrier so this mechanism harness leaves no suspended
        // request behind. Its generation is stale and must not change the assertion above.
        inbox.completeNextSnapshot(inboxSnapshot(listOf(activityItem("stale-after-clear"))))
        assertTrue(store.state.value.pullRefreshing)
        scope.cancel()
    }

    private fun pullRefreshStore(
        scope: CoroutineScope,
        inbox: InboxRepository,
        diagnosticsLogger: (String) -> Unit = {}
    ): SharedActivityStore =
        SharedActivityStore(
            authStore = InMemorySlockAuthSessionStore(authSessionFixture()),
            inboxRepository = inbox,
            readReceiptRepository = PullRefreshReadReceiptRepository,
            conversationRepository =
                ReplyThreadAccessConversationRepositoryRecorder(
                    replyResult = DataResult.Error(ScreenError("unused"))
                ),
            perReceiverStateVersionStore =
                PerReceiverStateVersionStore(InMemorySlockKeyValueStore()),
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            realtimeBackgroundReconcileDelayMillis = 0L
        )

    private object PullRefreshReadReceiptRepository : ReadReceiptRepository {
        override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> =
            error("unused")

        override suspend fun markChannelReadToSeq(
            channelId: ChannelId,
            seq: Long
        ): DataResult<ReadReceiptResult> = error("unused")

        override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> =
            error("unused")

        override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> =
            error("unused")

        override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> =
            error("unused")
    }

    private class PullRefreshInboxHarness : InboxRepository {
        private val lock = SynchronizedObject()
        private val pending = ArrayDeque<PendingInitial>()
        private val started = ArrayDeque<PendingInitial>()
        private val startWaiters = ArrayDeque<CompletableDeferred<PendingInitial>>()

        val pendingCount: Int
            get() = synchronized(lock) { pending.size }

        override suspend fun loadInitial(
            serverId: ServerId,
            filter: InboxFilter,
            limit: Int
        ): DataResult<InboxSnapshot> {
            val request = PendingInitial(serverId, filter, limit)
            val waiter =
                synchronized(lock) {
                    pending += request
                    val waiting = startWaiters.removeFirstOrNull()
                    if (waiting == null) started += request
                    waiting
                }
            waiter?.complete(request)
            return withContext(NonCancellable) { request.result.await() }
        }

        override suspend fun loadMore(
            serverId: ServerId,
            filter: InboxFilter,
            offset: Int,
            limit: Int
        ): DataResult<InboxSnapshot> = error("unused")

        override suspend fun markDone(channelId: ChannelId): DataResult<InboxDoneResult> =
            error("unused")

        fun expectNextInitialLoadStarted(): CompletableDeferred<PendingInitial> {
            val signal = CompletableDeferred<PendingInitial>()
            val existing =
                synchronized(lock) {
                    started.removeFirstOrNull()
                        ?: run {
                            startWaiters += signal
                            null
                        }
                }
            existing?.let(signal::complete)
            return signal
        }

        fun completeNextSnapshot(snapshot: InboxSnapshot) {
            completeNext(DataResult.Content(snapshot))
        }

        fun complete(request: PendingInitial, result: DataResult<InboxSnapshot>) {
            synchronized(lock) {
                check(pending.remove(request)) { "request was already completed or unknown" }
            }
            request.result.complete(result)
        }

        private fun completeNext(result: DataResult<InboxSnapshot>) {
            val request =
                synchronized(lock) {
                    pending.removeFirstOrNull()
                        ?: error("no pending initial load to complete")
                }
            request.result.complete(result)
        }

        data class PendingInitial(
            val serverId: ServerId,
            val filter: InboxFilter,
            val limit: Int,
            val result: CompletableDeferred<DataResult<InboxSnapshot>> = CompletableDeferred()
        )
    }

    private suspend fun <T> CompletableDeferred<T>.awaitBarrier(label: String): T {
        check(isCompleted) { "$label did not complete after its deterministic barrier" }
        return await()
    }

    private suspend fun Job.joinBarrier(label: String) {
        check(isCompleted) { "$label did not complete after its deterministic barrier" }
        join()
    }

    private fun inboxSnapshot(items: List<InboxItem> = emptyList()): InboxSnapshot =
        InboxSnapshot(
            server = ServerSummary(ServerId("server-1"), "One"),
            items = items,
            totalCount = items.size,
            totalUnreadCount = items.sumOf(InboxItem::unreadCount)
        )

    private fun activityItem(channelId: String): InboxItem =
        InboxItem(
            key = "channel:$channelId",
            kind = InboxItemKind.Channel,
            channelId = ChannelId(channelId),
            channelName = channelId,
            channelKind = ConversationKind.Channel,
            lastMessageId = MessageId("last-$channelId"),
            latestActivityMessageId = MessageId("latest-$channelId"),
            latestActivitySenderKind = MemberKind.Human
        )
}
