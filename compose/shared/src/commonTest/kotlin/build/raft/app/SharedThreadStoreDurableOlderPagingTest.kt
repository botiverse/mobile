package build.raft.app

import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownFrame
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #168 step 2b: a channel opened offline pages OLDER through the durable V2 window. While the
 * stand-in is shown, an older page is read from disk first; the network is asked only when the
 * window has nothing older. Every positive has a twin that differs in one fact.
 *
 * Every store here runs on the test's own `runBlocking` event loop (`coroutineContext + job`), the
 * single-threaded confinement production gets from `viewModelScope` (Main). An `Unconfined` store
 * scope resumes the store's `delay`s and its launched jobs (refresh, access resolution) on the
 * `DefaultExecutor` thread, so they mutate the store state concurrently with the test thread's
 * own `accept(...)` calls: a lost update that only shows on a slow machine.
 */
class SharedThreadStoreDurableOlderPagingTest {
    @Test
    fun offlineStandInPagesOlderFromDiskUntilTheStoredOldest() = runBlocking {
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader = FakeReader(frame(count = 120, hasLoadedOldest = true))
        val store = SharedThreadStore(dataSource(api, reader, logs = logs), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val standIn = store.state.value.payload
        assertTrue(standIn.durableLastKnown)
        assertEquals((91..120).map { "body $it" }, standIn.messages.map { it.body })
        assertTrue(standIn.canLoadMoreBefore, "90 stored facts are older than the 30 shown")
        assertEquals(1, api.channelMessageCalls, "only the (failed) refresh went to the network")

        for (oldestShown in listOf(61, 31)) {
            store.accept(SharedThreadIntent.LoadOlder)
            val paged = store.state.value.payload
            assertEquals((oldestShown..120).map { "body $it" }, paged.messages.map { it.body })
            assertTrue(paged.durableLastKnown, "pages read from disk keep the payload marked as the stand-in")
            assertTrue(paged.canLoadMoreBefore)
            assertEquals("idle", paged.olderMessagesStatus)
        }
        store.accept(SharedThreadIntent.LoadOlder)
        val last = store.state.value.payload
        assertEquals((1..120).map { "body $it" }, last.messages.map { it.body })
        assertFalse(last.canLoadMoreBefore, "window exhausted and the cursor says it reaches the channel start")
        assertEquals(1, api.channelMessageCalls, "no older page went to the network")
        assertEquals(3, logs.count { "thread_durable_older_page" in it && "served=30" in it }, logs.toString())
        assertTrue(logs.any { "served=30 reason=reached_oldest" in it }, logs.toString())
        scopeJob.cancelAndJoin()
    }

    @Test
    fun windowNotReachingTheChannelStartKeepsPagingOpenForTheNetwork() = runBlocking {
        // Twin of the test above: identical 120 facts, but the cursor says older history exists.
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val store =
            SharedThreadStore(dataSource(api, FakeReader(frame(count = 120, hasLoadedOldest = false)), logs = logs), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        repeat(3) { store.accept(SharedThreadIntent.LoadOlder) }
        val exhausted = store.state.value.payload
        assertEquals(120, exhausted.messages.size)
        assertTrue(exhausted.canLoadMoreBefore, "the window ends but the channel does not")
        assertEquals(1, api.channelMessageCalls, "only the (failed) refresh so far")

        // The next older load finds nothing on disk and goes to the network as before (offline: fails).
        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(2, api.channelMessageCalls, "the older page went to the network")
        val failed = store.state.value.payload
        assertEquals("error", failed.olderMessagesStatus)
        assertTrue(failed.canLoadMoreBefore, "the retry row stays available")
        assertEquals(120, failed.messages.size)
        assertTrue(logs.any { "served=0 reason=exhausted" in it }, logs.toString())
        scopeJob.cancelAndJoin()
    }

    @Test
    fun standInOfAWholeWindowThatReachesTheStartHasPagingClosed() = runBlocking {
        val closedScopeJob = SupervisorJob()
        val closedScope = CoroutineScope(coroutineContext + closedScopeJob)
        val closed =
            SharedThreadStore(
                dataSource(SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true), FakeReader(frame(count = 30, hasLoadedOldest = true))),
                scope = closedScope,
            )
        closed.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(30, closed.state.value.payload.messages.size)
        assertFalse(closed.state.value.payload.canLoadMoreBefore)
        closedScopeJob.cancelAndJoin()

        // Twin: the same 30 facts, but the window does not reach the start.
        val openScopeJob = SupervisorJob()
        val openScope = CoroutineScope(coroutineContext + openScopeJob)
        val open =
            SharedThreadStore(
                dataSource(SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true), FakeReader(frame(count = 30, hasLoadedOldest = false))),
                scope = openScope,
            )
        open.accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(open.state.value.payload.canLoadMoreBefore)
        openScopeJob.cancelAndJoin()
    }

    @Test
    fun onlineFrameLoadsOlderFromTheNetworkAndNeverReadsTheWindow() = runBlocking {
        // Twin of the offline walk: same 120-fact window, but the network answers, so the frame is
        // no longer the stand-in when the user pages older.
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi()
        val reader = FakeReader(frame(count = 120, hasLoadedOldest = true))
        val store = SharedThreadStore(dataSource(api, reader), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = false))
        assertFalse(store.state.value.payload.durableLastKnown)
        val readsBefore = reader.reads.size

        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(1, api.beforeSeqs.size, "older page went to the network")
        assertEquals(readsBefore, reader.reads.size, "the durable window was not read")
        assertTrue(store.state.value.payload.messages.any { it.body == "older body" })
        scopeJob.cancelAndJoin()
    }

    @Test
    fun onlineRefreshReplacesTheStandInIncludingItsDiskPages() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val loadingStateApplied = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val store =
            SharedThreadStore(
                dataSource = dataSource(SharedThreadStoreTest.FakeThreadApi(), FakeReader(frame(count = 120, hasLoadedOldest = true))),
                scope = scope,
                afterRefreshLoadingStateApplied = {
                    loadingStateApplied.complete(Unit)
                    releaseRefresh.await()
                },
            )
        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            loadingStateApplied.await()
            // The refresh is in flight; the user pages older on the stand-in meanwhile.
            store.accept(SharedThreadIntent.LoadOlder)
            val paged = store.state.value.payload
            assertTrue(paged.durableLastKnown)
            assertEquals((61..120).map { "body $it" }, paged.messages.map { it.body })

            releaseRefresh.complete(Unit)
            val settled = withTimeout(5_000L) { store.state.first { !it.payload.backgroundRefreshing }.payload }
            assertFalse(settled.durableLastKnown)
            assertEquals(listOf("network body"), settled.messages.map { it.body }, "disk pages are replaced too")
        } finally {
            releaseRefresh.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun anotherOwnerNeverPagesTheStandInFromDisk() = runBlocking {
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader = FakeReader(frame(count = 120, hasLoadedOldest = true))
        var identity = ownerA
        val store = SharedThreadStore(dataSource(api, reader, identity = { identity }, logs = logs), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(store.state.value.payload.canLoadMoreBefore)
        val readsBefore = reader.reads.size

        // Twin of the offline walk: identical stand-in, but the account switched before paging.
        identity = ownerB
        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(readsBefore, reader.reads.size, "no durable read for another owner")
        assertTrue(store.state.value.payload.messages.none { it.body == "body 90" })
        assertTrue(logs.any { "thread_durable_older_page" in it && "reason=owner_changed" in it }, logs.toString())
        scopeJob.cancelAndJoin()
    }

    @Test
    fun replyRouteIsNeverServedFromTheWindow() = runBlocking {
        val logs = mutableListOf<String>()
        val dataSource = dataSource(SharedThreadStoreTest.FakeThreadApi(), FakeReader(frame(count = 120, hasLoadedOldest = true)), logs = logs)
        val context = kotlin.test.assertNotNull(dataSource.captureReplyCacheProducerContext())
        val reply =
            basePayload().copy(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-parent-1",
                streamChannelId = "thread-parent-1",
                parentChannelId = "channel-1",
                parentMessageId = "parent-1",
                originParentMessageId = "parent-1",
            ).resolveThreadRouteIdentity()
        val owner = context.provenance.threadPayloadOwnerScopeId
        assertEquals(null, dataSource.durableOlderPageIfCurrent(reply, context, beforeSeq = 91L, expectedOwnerScopeId = owner))
        assertTrue(logs.any { "reason=not_top_level" in it }, logs.toString())
        // Positive control: the channel route with the same context is served.
        val page = kotlin.test.assertNotNull(
            dataSource.durableOlderPageIfCurrent(
                basePayload().resolveThreadRouteIdentity(),
                context,
                beforeSeq = 91L,
                expectedOwnerScopeId = owner,
            ),
        )
        assertEquals((61..90).map { "body $it" }, page.messages.map { it.body })
    }

    private class FakeReader(private val frame: ThreadDurableLastKnownFrame) : ThreadDurableLastKnownReader {
        val reads = mutableListOf<Pair<DurablePartitionKey, SyncScopeId>>()

        override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? {
            reads += partition to channelId
            return frame.takeIf { it.scope.partition == partition && it.scope.scopeId == channelId }
        }
    }

    private fun dataSource(
        api: SharedThreadStoreTest.FakeThreadApi,
        reader: ThreadDurableLastKnownReader,
        identity: () -> ThreadReplyCacheIdentitySnapshot = { ownerA },
        logs: MutableList<String> = mutableListOf(),
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            networkApi = api,
            sessionStore = InMemorySlockAuthSessionStore(identity().session),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = { identity() },
            durableLastKnownReader = reader,
            diagnosticsLogger = logs::add,
        )

    private fun partition(identity: ThreadReplyCacheIdentitySnapshot): DurablePartitionKey =
        DurablePartitionKey(
            serverId = identity.serverId,
            principalId = identity.principalId,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = identity.environmentId,
        )

    /** Owner A's window: facts seq 1..[count] (stored newest-first), cursor oldest=1. */
    private fun frame(count: Int, hasLoadedOldest: Boolean): ThreadDurableLastKnownFrame =
        ThreadDurableLastKnownFrame(
            scope = SyncScopeKey(partition(ownerA), "channel-1", "channel"),
            envelopes = (count downTo 1).map { index ->
                CanonicalMessageEnvelopeV2(
                    fact = CanonicalMessageNodeV2(
                        id = "v2-$index",
                        seq = index.toLong(),
                        senderType = "user",
                        senderId = "u-a",
                        content = "body $index",
                        messageType = "chat",
                        createdAt = "2026-05-19T16:${(index / 60).toString().padStart(2, '0')}:${(index % 60).toString().padStart(2, '0')}Z",
                        senderName = "Owner A",
                    ),
                    context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", "channel-1")),
                )
            },
            cursor = PersistedCursor(count.toLong(), 1, count.toLong(), hasLoadedOldest = hasLoadedOldest, epoch = "epoch-v2"),
        )

    private val ownerA = owner("u-a", "Owner A", authGeneration = 1L)
    private val ownerB = owner("u-b", "Owner B", authGeneration = 2L)

    private fun owner(userId: String, name: String, authGeneration: Long) =
        ThreadReplyCacheIdentitySnapshot(
            session = StoredAuthSession(
                user = StoredAuthUser(id = userId, email = "$userId@example.test", displayName = name),
                accessToken = "access-$userId",
                refreshToken = "refresh-$userId",
                selectedServerId = "server-1",
            ),
            environmentId = TestThreadEnvironmentId,
            principalId = userId,
            serverId = "server-1",
            authGeneration = authGeneration,
            selectionGeneration = 1L,
        )

    private fun basePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )
}
