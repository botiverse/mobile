package build.raft.app

import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RawMessagePage
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.WireMessage
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
import build.raft.app.network.sync.CanonicalMessageV2ThreadJoinConfirmed
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.MessageRefV2
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.ReplyMembershipV2
import build.raft.app.network.sync.ReplyScopeRefV2
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownFrame
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #168 step 3: what the thread store does for a reply-thread route around the durable V2
 * cache — it asks for the reply thread's own scope with the route's typed parent (enter snapshot,
 * offline stand-in, offline older paging, adjacent pages), and never for a reply route without one.
 * The loader / reader / writer are faked here; they are exercised in
 * `CanonicalMessageV2ReplyThreadDurableTest` and on SQLite in the reply-thread round trip.
 *
 * Every store - including the data source's message / payload stores - runs on the test's own `runTest`
 * scheduler (virtual time) (see `SharedThreadStoreDurableOlderPagingTest` and [dataSource]).
 */
class SharedThreadStoreReplyThreadDurableTest {
    @Test
    fun offlineReplyThreadOpenStandsInFromDiskAndPagesOlderFromIt() = runTest {
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader = FakeReader(replyFrame(count = 40))
        val store = SharedThreadStore(dataSource(api, scope, reader = reader, logs = logs), scope = scope)
        store.accept(SharedThreadIntent.Start(replyRoute()))

        val standIn = store.state.value.payload
        assertTrue(standIn.durableLastKnown)
        assertEquals((11..40).map { "reply $it" }, standIn.messages.map { it.body })
        assertTrue(standIn.canLoadMoreBefore, "10 stored replies are older than the 30 shown")
        assertEquals(listOf(ReplyRead(partition(ownerA), "thread-1", parent)), reader.replyReads)
        assertTrue(reader.topLevelReads.isEmpty(), "a reply route never reads a top-level window")
        assertTrue(logs.any { "thread_durable_last_known threadId=thread-1 outcome=frame scopeKind=thread" in it }, logs.toString())
        val callsAfterStart = api.channelMessageCalls

        store.accept(SharedThreadIntent.LoadOlder)
        val paged = store.state.value.payload
        assertEquals((1..40).map { "reply $it" }, paged.messages.map { it.body })
        assertFalse(paged.canLoadMoreBefore, "window exhausted and the cursor says it reaches the first reply")
        assertEquals(callsAfterStart, api.channelMessageCalls, "the older page came from disk")
        assertTrue(logs.any { "thread_durable_older_page threadId=thread-1 served=10" in it && "scopeKind=thread" in it }, logs.toString())
        scopeJob.cancelAndJoin()
    }

    @Test
    fun offlineReplyThreadOpenWithoutAStoredWindowTakesTheNetworkPath() = runTest {
        // Twin of the test above: identical route and failing network, but nothing on disk.
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader = FakeReader(frame = null)
        val store = SharedThreadStore(dataSource(api, scope, reader = reader), scope = scope)
        store.accept(SharedThreadIntent.Start(replyRoute()))

        val payload = store.state.value.payload
        assertFalse(payload.durableLastKnown)
        assertTrue(payload.messages.isEmpty())
        assertTrue(payload.status != "content", payload.status)
        assertEquals(1, api.channelMessageCalls, "the ordinary network load ran")
        // Task #18: nothing on disk -> read, bounded wait for the entry load, read again.
        assertEquals(List(2) { ReplyRead(partition(ownerA), "thread-1", parent) }, reader.replyReads)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun replyRouteWithoutItsTypedParentNeverConsultsTheDurableReader() = runTest {
        // Twin of the offline stand-in: identical stored frame and stream id, but the route lost its
        // parent channel, so it cannot name the thread scope's parent.
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val reader = FakeReader(replyFrame(count = 40))
        val store =
            SharedThreadStore(
                dataSource(SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true), scope, reader = reader),
                scope = scope,
            )
        store.accept(SharedThreadIntent.Start(replyRoute().copy(parentChannelId = null)))
        assertFalse(store.state.value.payload.durableLastKnown)
        assertTrue(reader.replyReads.isEmpty(), reader.replyReads.toString())
        assertTrue(reader.topLevelReads.isEmpty(), reader.topLevelReads.toString())
        scopeJob.cancelAndJoin()
    }

    @Test
    fun replyThreadEntryRequestsTheReplyThreadSnapshotWithItsParent() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val loader = FakeLoader()
        val store = SharedThreadStore(dataSource(SharedThreadStoreTest.FakeThreadApi(), scope, loader = loader), scope = scope)
        store.accept(SharedThreadIntent.Start(replyRoute()))
        withTimeout(5_000L) { while (loader.replyLoads.isEmpty()) delay(10) }
        assertEquals(listOf(ReplyRead(partition(ownerA), "thread-1", parent)), loader.replyLoads)
        assertEquals(listOf(true), loader.stillCurrentAtCall)
        assertTrue(loader.topLevelLoads.isEmpty(), "a reply route is never loaded as a top-level window")
        scopeJob.cancelAndJoin()

        // Twin: identical route without its parent channel requests nothing.
        val twinJob = SupervisorJob()
        val twinScope = CoroutineScope(coroutineContext + twinJob)
        val twinLoader = FakeLoader()
        SharedThreadStore(dataSource(SharedThreadStoreTest.FakeThreadApi(), twinScope, loader = twinLoader), scope = twinScope)
            .accept(SharedThreadIntent.Start(replyRoute().copy(parentChannelId = null)))
        repeat(10) { yield() }
        assertTrue(twinLoader.replyLoads.isEmpty() && twinLoader.topLevelLoads.isEmpty(), twinLoader.replyLoads.toString())
        twinJob.cancelAndJoin()
    }

    @Test
    fun replyThreadPagesAreOfferedToTheExtenderWithTheRoutesParent() = runTest {
        val extender = RecordingExtender()
        val dataSource = dataSource(SharedThreadStoreTest.FakeThreadApi(), storeScope = null, extender = extender)
        val context = assertNotNull(dataSource.captureReplyCacheProducerContext())
        val raw = RawMessagePage(messages = JsonArray(emptyList()), requestedLimit = 30)
        val route = replyRoute().resolveThreadRouteIdentity()

        assertTrue(dataSource.extendDurableWindowIfCurrent(route, context, ThreadDurablePageDirection.Older, 10L, raw, parent))
        assertEquals(listOf<Pair<SyncScopeId, ThreadDurableReplyParent?>>("thread-1" to parent), extender.calls)
        // Twin: identical route and page without the typed parent is not admitted.
        assertFalse(dataSource.extendDurableWindowIfCurrent(route, context, ThreadDurablePageDirection.Older, 10L, raw))
        assertEquals(1, extender.calls.size)
        // A channel route is still offered as a top-level window (no parent).
        assertTrue(
            dataSource.extendDurableWindowIfCurrent(
                replyRoute().copy(surfaceKind = RaftThreadSurfaceKind.Channel, sourceThreadId = "channel-1", streamChannelId = null, parentChannelId = null, parentMessageId = null, originParentMessageId = null)
                    .resolveThreadRouteIdentity(),
                context,
                ThreadDurablePageDirection.Older,
                10L,
                raw,
            ),
        )
        assertEquals<Pair<SyncScopeId, ThreadDurableReplyParent?>>("channel-1" to null, extender.calls.last())
    }

    private data class ReplyRead(
        val partition: DurablePartitionKey,
        val threadChannelId: SyncScopeId,
        val replyParent: ThreadDurableReplyParent,
    )

    private class FakeReader(var frame: ThreadDurableLastKnownFrame?) : ThreadDurableLastKnownReader {
        val topLevelReads = mutableListOf<SyncScopeId>()
        val replyReads = mutableListOf<ReplyRead>()

        override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? {
            topLevelReads += channelId
            return null
        }

        override suspend fun readReplyThread(
            partition: DurablePartitionKey,
            threadChannelId: SyncScopeId,
            replyParent: ThreadDurableReplyParent,
        ): ThreadDurableLastKnownFrame? {
            replyReads += ReplyRead(partition, threadChannelId, replyParent)
            return frame?.takeIf { it.scope.partition == partition && it.scope.scopeId == threadChannelId }
        }
    }
    // ---- task #14c: a late join ack runs ONE gap catch-up on the open thread page, none once it closed ----

    private data class JoinCatchUpRun(val catchUps: Int, val baselines: List<Pair<SyncScopeId, ThreadDurableReplyParent?>>, val logs: List<String>)

    private suspend fun joinCatchUpRun(
        close: Boolean = false,
        confirmedThread: SyncScopeId = "thread-1",
        catchUpCode: Int = 200,
    ): JoinCatchUpRun = kotlinx.coroutines.coroutineScope {
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi()
        api.catchUpResponseCodes.addLast(catchUpCode)
        val extender = RecordingExtender()
        val store = SharedThreadStore(dataSource(api, scope, extender = extender, logs = logs), scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(replyRoute()))
        repeat(20) { yield() }
        if (close) store.clear()
        extender.confirmations.emit(CanonicalMessageV2ThreadJoinConfirmed(partition(ownerA), confirmedThread, "channel-1", 2L))
        // Bounded: the open page's catch-up ends with `outcome=done|failed`; a closed page never logs one.
        withTimeoutOrNull(if (close || confirmedThread != "thread-1") 1_000L else 10_000L) {
            while (logs.none { "thread_join_catch_up" in it && ("outcome=done" in it || "outcome=failed" in it) }) delay(10)
        }
        repeat(20) { yield() }
        scopeJob.cancelAndJoin()
        JoinCatchUpRun(api.catchUpCalls, extender.baselineBegins.toList(), logs.filter { "thread_join_catch_up" in it })
    }

    @Test
    fun aJoinConfirmationRunsOneCatchUpOnTheOpenThreadPageWithTheThreadsBaseline() = runTest {
        val open = joinCatchUpRun()
        assertEquals(1, open.catchUps, open.logs.toString())
        assertEquals(listOf<Pair<SyncScopeId, ThreadDurableReplyParent?>>("thread-1" to parent), open.baselines, "the thread scope, with its parent")
        assertEquals(
            listOf(
                "thread_join_catch_up threadId=thread-1 epoch=2 outcome=requested",
                "thread_join_catch_up threadId=thread-1 epoch=2 outcome=done",
            ),
            open.logs,
        )

        // Twins: the identical confirmation after the page closed, or for another thread, fetches nothing.
        val closed = joinCatchUpRun(close = true)
        assertEquals(0, closed.catchUps)
        assertEquals(emptyList(), closed.baselines)
        assertEquals(emptyList(), closed.logs)
        val other = joinCatchUpRun(confirmedThread = "thread-2")
        assertEquals(0, other.catchUps)
        assertEquals(emptyList(), other.logs)
    }

    @Test
    fun aFailedJoinCatchUpIsLoggedAndNotRetried() = runTest {
        val failed = joinCatchUpRun(catchUpCode = 500)
        assertEquals(1, failed.catchUps, "one request, no retry")
        assertEquals("thread_join_catch_up threadId=thread-1 epoch=2 outcome=requested", failed.logs.first())
        assertTrue(failed.logs.last().startsWith("thread_join_catch_up threadId=thread-1 epoch=2 outcome=failed retry=none"), failed.logs.toString())
        assertEquals(2, failed.logs.size)
        // Twin: the same catch-up succeeding logs no failure.
        assertEquals("thread_join_catch_up threadId=thread-1 epoch=2 outcome=done", joinCatchUpRun().logs.last())
    }

    private class FakeLoader : ThreadEnteredScopeSnapshotLoader {
        val topLevelLoads = mutableListOf<SyncScopeId>()
        val replyLoads = mutableListOf<ReplyRead>()
        val stillCurrentAtCall = mutableListOf<Boolean>()

        override suspend fun loadOnEnter(partition: DurablePartitionKey, channelId: SyncScopeId, stillCurrent: () -> Boolean) {
            topLevelLoads += channelId
        }

        override suspend fun loadReplyThreadOnEnter(
            partition: DurablePartitionKey,
            threadChannelId: SyncScopeId,
            replyParent: ThreadDurableReplyParent,
            stillCurrent: () -> Boolean,
        ) {
            replyLoads += ReplyRead(partition, threadChannelId, replyParent)
            stillCurrentAtCall += stillCurrent()
        }
    }

    private class RecordingExtender : ThreadDurablePageExtender {
        val calls = mutableListOf<Pair<SyncScopeId, ThreadDurableReplyParent?>>()

        /** Task #14c: what the canary would emit; and every catch-up baseline requested (channel, parent). */
        val confirmations = MutableSharedFlow<CanonicalMessageV2ThreadJoinConfirmed>(extraBufferCapacity = 8)
        val baselineBegins = mutableListOf<Pair<SyncScopeId, ThreadDurableReplyParent?>>()

        override fun threadJoinConfirmations(): Flow<CanonicalMessageV2ThreadJoinConfirmed> = confirmations

        override suspend fun beginLatestPageBaseline(
            partition: DurablePartitionKey,
            channelId: SyncScopeId,
            replyParent: ThreadDurableReplyParent?,
        ): CanonicalMessageV2EnterBaseline? {
            baselineBegins += channelId to replyParent
            return null
        }

        override suspend fun extendWithAdjacentPage(
            partition: DurablePartitionKey,
            channelId: SyncScopeId,
            direction: ThreadDurablePageDirection,
            anchorSeq: Long,
            rawMessages: JsonArray,
            requestedLimit: Int,
            replyParent: ThreadDurableReplyParent?,
            stillCurrent: () -> Boolean,
        ) {
            calls += channelId to replyParent
        }
    }

    /**
     * [storeScope] confines the data source's own message / payload stores (parent-context lookups,
     * reply-access flights) to the test's `runTest` scheduler. Their defaults launch on
     * `Dispatchers.Default`, where `fetchParent` logs into [logs] - a plain list the test thread
     * iterates - and calls the unsynchronized fake API: a `ConcurrentModificationException` that
     * only shows when the full suite loads the machine. `null` only for tests that start no store.
     */
    private fun dataSource(
        api: SharedThreadStoreTest.FakeThreadApi,
        storeScope: CoroutineScope?,
        reader: ThreadDurableLastKnownReader? = null,
        loader: ThreadEnteredScopeSnapshotLoader? = null,
        extender: ThreadDurablePageExtender? = null,
        logs: MutableList<String> = mutableListOf(),
        payloadStore: SharedThreadPayloadStore? = storeScope?.let { SharedThreadPayloadStore(scope = it) },
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            threadParseDispatcher = storeScope?.testDispatcher() ?: build.raft.app.data.RaftJsonParseDispatcher,
            networkApi = api,
            messageStore = storeScope?.let { SharedThreadMessageStore(scope = it) },
            payloadStore = payloadStore,
            sessionStore = InMemoryRaftAuthSessionStore(ownerA.session),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = { ownerA },
            durableLastKnownReader = reader,
            enteredScopeSnapshotLoader = loader,
            durablePageExtender = extender,
            diagnosticsLogger = logs::add,
        )

    private fun partition(identity: ThreadReplyCacheIdentitySnapshot): DurablePartitionKey =
        DurablePartitionKey(
            serverId = identity.serverId,
            principalId = identity.principalId,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = identity.environmentId,
        )

    /** Owner A's stored replies of `thread-1`: seq 1..[count], cursor reaching the first reply. */
    private fun replyFrame(count: Int): ThreadDurableLastKnownFrame {
        val replyScope = ReplyScopeRefV2("server-1", "thread-1")
        val root = MessageRefV2("server-1", "root-1")
        return ThreadDurableLastKnownFrame(
            scope = SyncScopeKey(partition(ownerA), "thread-1", "thread"),
            envelopes = (1..count).map { index ->
                CanonicalMessageEnvelopeV2(
                    fact = CanonicalMessageNodeV2(
                        id = "r-$index",
                        seq = index.toLong(),
                        senderType = "user",
                        senderId = "u-a",
                        content = "reply $index",
                        messageType = "chat",
                        createdAt = "2026-05-19T16:00:${index.toString().padStart(2, '0')}Z",
                        senderName = "Owner A",
                    ),
                    context = CanonicalConversationContextV2.Thread(
                        replyScope = replyScope,
                        parentTopLevelScope = TopLevelScopeRefV2.Channel("server-1", "channel-1"),
                        rootMessageRef = root,
                    ),
                    replyMembership = ReplyMembershipV2(root = root, parentScope = replyScope),
                )
            },
            cursor = PersistedCursor(
                latestSeq = count.toLong(),
                oldestLoadedSeq = 1,
                newestLoadedSeq = count.toLong(),
                hasLoadedOldest = true,
                parentScopeKind = "channel",
                parentScopeId = "channel-1",
                rootMessageId = "root-1",
            ),
        )
    }

    private val parent = ThreadDurableReplyParent(parentChannelId = "channel-1", rootMessageId = "root-1")

    private val ownerA =
        ThreadReplyCacheIdentitySnapshot(
            session = StoredAuthSession(
                user = StoredAuthUser(id = "u-a", email = "u-a@example.test", displayName = "Owner A"),
                accessToken = "access-u-a",
                refreshToken = "refresh-u-a",
                selectedServerId = "server-1",
            ),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-a",
            serverId = "server-1",
            authGeneration = 1L,
            selectionGeneration = 1L,
        )

    private fun replyRoute(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-thread-1",
            title = "Thread",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-1",
            streamChannelId = "thread-1",
            parentChannelId = "channel-1",
            parentMessageId = "root-1",
            originParentMessageId = "root-1",
            status = "loading",
            canSend = true,
        )

    // ---- Hands 4b6ccb0d: a confirmed own send survives a summary advance into the reopened first frame ----

    /** [replyRoute] with its typed parent kind, which a pending send's route identity requires. */
    private fun sendableReplyRoute(): RaftThreadScreenPayload = replyRoute().copy(parentChannelKind = "channel")

    /** Replies r-[first]..r-[last] of `thread-1` as the messages endpoint returns them. */
    private fun repliesResponse(range: IntRange): String =
        range.joinToString(prefix = """{"historyLimited":false,"messages":[""", postfix = "]}") { index ->
            """{"id":"r-$index","seq":$index,"channelId":"thread-1","content":"reply $index",""" +
                """"createdAt":"2026-05-19T16:00:${index.toString().padStart(2, '0')}Z","senderId":"u-a",""" +
                """"senderName":"Owner A","senderType":"user","messageType":"chat"}"""
        }

    private fun ownSentPage(index: Int): ThreadMessagesPage =
        ThreadMessagesPage(
            messages = listOf(
                WireMessage(
                    id = "r-$index",
                    seq = index.toLong(),
                    channelId = "thread-1",
                    senderType = "user",
                    senderId = "u-a",
                    senderName = "Owner A",
                    messageType = "chat",
                    content = "reply $index",
                    createdAt = "2026-05-19T16:01:00Z",
                ),
            ),
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
        )

    private fun summary(replyCount: Int): SharedThreadSummaryFact =
        SharedThreadSummaryFact(
            replyCount = replyCount,
            latestActivityMessageId = "r-$replyCount",
            latestActivityPreview = "reply $replyCount",
            latestActivityAt = "2026-05-19T16:01:${replyCount.toString().padStart(2, '0')}Z",
            lastMessageId = "r-$replyCount",
            lastMessagePreview = "reply $replyCount",
            lastMessageAt = "2026-05-19T16:01:${replyCount.toString().padStart(2, '0')}Z",
        )

    @Test
    fun ownSendSurvivesAnInboxSummaryAdvanceIntoTheReopenedFirstFrame() = runTest {
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(bodyText = repliesResponse(1..15))
        // The disk holds the thread as it was before the send (the realtime echo is never written to a
        // discontinuous reply-thread window).
        val reader = FakeReader(replyFrame(count = 15))
        val loader = FakeLoader()
        val payloadStore = SharedThreadPayloadStore(scope = scope)
        val dataSource = dataSource(api, scope, reader = reader, loader = loader, logs = logs, payloadStore = payloadStore)
        val context = assertNotNull(dataSource.captureReplyCacheProducerContext())
        val first = SharedThreadStore(dataSource, scope = scope, diagnosticsLogger = logs::add)
        first.accept(SharedThreadIntent.Start(sendableReplyRoute()))
        withTimeout(5_000L) { while (logs.none { "thread_refresh_result threadId=thread-1" in it }) delay(10) }
        assertEquals(15, first.state.value.payload.messages.size)

        // 1. The user's send is confirmed: the visible page and the memory cache hold 16 replies.
        first.accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = "local-16",
                page = ownSentPage(16),
                expectedProvenance = context.provenance,
                expectedRoute = first.state.value.payload.resolveThreadRouteIdentity(),
            ),
        )
        assertEquals(
            (1..16).map { "r-$it" },
            first.state.value.payload.messages.map { it.id },
        )
        // 2. The Activity inbox reloads with the higher reply count: the memory entry is superseded.
        payloadStore.freshnessLedger.acceptSummary("server-1", "thread-1", summary(15))
        assertTrue(payloadStore.freshnessLedger.acceptSummary("server-1", "thread-1", summary(16)))
        assertEquals(null, dataSource.cachedPayloadIfCurrent("thread-1", context.provenance), "precondition: get() misses")
        // 3. Leave; 4. reopen with the network down, so only the first frame can show the send.
        first.clear()
        api.failChannelMessages = true
        val reopenLogStart = logs.size
        val reopened = SharedThreadStore(dataSource, scope = scope, diagnosticsLogger = logs::add)
        reopened.accept(SharedThreadIntent.Start(sendableReplyRoute()))

        val reopenLogs = logs.drop(reopenLogStart)
        assertTrue(
            reopenLogs.any { "thread_visible_payload threadId=thread-1 status=content messages=16" in it },
            reopenLogs.toString(),
        )
        assertTrue(
            reopenLogs.any { "thread_start_superseded_memory threadId=thread-1" in it && "memorySeq=16 durableSeq=15 chosen=memory" in it },
            reopenLogs.toString(),
        )
        assertEquals((1..16).map { "r-$it" }, reopened.state.value.payload.messages.map { it.id })
        scopeJob.cancelAndJoin()
    }

    @Test
    fun aSupersededMemoryCopyOlderThanTheDiskStillLoses() = runTest {
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(bodyText = repliesResponse(1..10))
        val reader = FakeReader(frame = null)
        val payloadStore = SharedThreadPayloadStore(scope = scope)
        val dataSource = dataSource(api, scope, reader = reader, logs = logs, payloadStore = payloadStore)
        // Memory learns replies 1..10, then a summary supersedes it while the disk moved on to 1..15.
        val first = SharedThreadStore(dataSource, scope = scope, diagnosticsLogger = logs::add)
        first.accept(SharedThreadIntent.Start(sendableReplyRoute()))
        withTimeout(5_000L) { while (logs.none { "thread_refresh_result threadId=thread-1" in it }) delay(10) }
        assertEquals(10, first.state.value.payload.messages.size)
        first.clear()
        payloadStore.freshnessLedger.acceptSummary("server-1", "thread-1", summary(10))
        assertTrue(payloadStore.freshnessLedger.acceptSummary("server-1", "thread-1", summary(15)))
        reader.frame = replyFrame(count = 15)
        api.failChannelMessages = true
        val reopenLogStart = logs.size
        val reopened = SharedThreadStore(dataSource, scope = scope, diagnosticsLogger = logs::add)
        reopened.accept(SharedThreadIntent.Start(sendableReplyRoute()))

        val reopenLogs = logs.drop(reopenLogStart)
        assertTrue(reopenLogs.any { "memorySeq=10 durableSeq=15 chosen=durable" in it }, reopenLogs.toString())
        assertTrue(reopenLogs.any { "thread_visible_payload threadId=thread-1 status=content messages=15" in it }, reopenLogs.toString())
        assertTrue(reopened.state.value.payload.durableLastKnown)
        assertEquals((1..15).map { "reply $it" }, reopened.state.value.payload.messages.map { it.body })
        scopeJob.cancelAndJoin()
    }

    private suspend fun ownSendCatchUpRun(
        diskHasTheSend: Boolean,
        sendStoredLoose: Boolean = false,
    ): Pair<List<ReplyRead>, List<String>> =
        kotlinx.coroutines.coroutineScope {
            val logs = mutableListOf<String>()
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + scopeJob)
            val api = SharedThreadStoreTest.FakeThreadApi(bodyText = repliesResponse(1..15))
            val reader = FakeReader(replyFrame(count = 15))
            val loader = FakeLoader()
            val dataSource = dataSource(api, scope, reader = reader, loader = loader, logs = logs)
            val context = assertNotNull(dataSource.captureReplyCacheProducerContext())
            val store = SharedThreadStore(dataSource, scope = scope, diagnosticsLogger = logs::add)
            store.accept(SharedThreadIntent.Start(sendableReplyRoute()))
            withTimeout(5_000L) { while (logs.none { "thread_refresh_result threadId=thread-1" in it }) delay(10) }
            withTimeout(5_000L) { while (loader.replyLoads.isEmpty()) delay(10) }
            loader.replyLoads.clear()
            if (diskHasTheSend) reader.frame = replyFrame(count = 16)
            // Message storage Phase 3a: the realtime echo is stored, but loose (above the stored window 1..15).
            if (sendStoredLoose) reader.frame = replyFrame(count = 16).let { it.copy(cursor = it.cursor.copy(newestLoadedSeq = 15)) }
            store.accept(
                SharedThreadIntent.ReplaceLocalSendWithSentPage(
                    messageId = "local-16",
                    page = ownSentPage(16),
                    expectedProvenance = context.provenance,
                    expectedRoute = store.state.value.payload.resolveThreadRouteIdentity(),
                ),
            )
            delay(SharedThreadOwnSendDurableCheckDelayMillis + 500L)
            repeat(20) { yield() }
            scopeJob.cancelAndJoin()
            loader.replyLoads.toList() to logs.filter { "thread_own_send_durable_catchup" in it }
        }

    @Test
    fun aConfirmedSendMissingFromTheDiskRefetchesTheThreadsEnterSnapshot() = runTest {
        val (loads, logs) = ownSendCatchUpRun(diskHasTheSend = false)
        assertEquals(listOf(ReplyRead(partition(ownerA), "thread-1", parent)), loads, logs.toString())
        assertEquals(1, logs.size, logs.toString())
        assertTrue("sent=1 stored=false requested=true" in logs.single(), logs.toString())
        // Twin: identical send whose echo already reached the disk fetches nothing.
        val (twinLoads, twinLogs) = ownSendCatchUpRun(diskHasTheSend = true)
        assertEquals(emptyList(), twinLoads)
        assertEquals(emptyList(), twinLogs)
        // Phase 3a twin: the echo is on disk only as a loose fact (no covered range holds it): still refetched.
        val (looseLoads, looseLogs) = ownSendCatchUpRun(diskHasTheSend = false, sendStoredLoose = true)
        assertEquals(listOf(ReplyRead(partition(ownerA), "thread-1", parent)), looseLoads, looseLogs.toString())
        assertTrue("sent=1 stored=false requested=true" in looseLogs.single(), looseLogs.toString())
    }

}
