package build.raft.app

import build.raft.app.resources.*
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadMessageStore
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.dispatch
import build.raft.app.thread.threadOlderMessagesStatusSpec
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.network.sync.CanonicalActionMetadataV2
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #168 step 2b: a channel opened offline pages OLDER through the durable V2 window. While the
 * stand-in is shown, an older page is read from disk first; the network is asked only when the
 * window has nothing older. Every positive has a twin that differs in one fact.
 *
 * Every store here runs on the test's own `runTest` scheduler (`coroutineContext + job`, virtual time), the
 * single-threaded confinement production gets from `viewModelScope` (Main). An `Unconfined` store
 * scope resumes the store's `delay`s and its launched jobs (refresh, access resolution) on the
 * `DefaultExecutor` thread, so they mutate the store state concurrently with the test thread's
 * own `accept(...)` calls: a lost update that only shows on a slow machine.
 */
class SharedThreadStoreDurableOlderPagingTest {
    @Test
    fun offlineStandInPagesOlderFromDiskUntilTheStoredOldest() = runTest {
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader = FakeReader(frame(count = 120, hasLoadedOldest = true))
        val store = SharedThreadStore(dataSource(storeScope = backgroundScope, api, reader, logs = logs), scope = scope)
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
            assertFalse(paged.durableReachedOldest)
            assertEquals("idle", paged.olderMessagesStatus)
        }
        assertFalse(standIn.durableReachedOldest)
        assertNull(threadOlderMessagesStatusSpec(standIn), "idle older-paging stand-in hides the loading row")
        assertEquals(
            Res.string.thread_state_loading_older.desc(),
            threadOlderMessagesStatusSpec(standIn.copy(olderMessagesStatus = "loading"))?.label,
        )
        store.accept(SharedThreadIntent.LoadOlder)
        val last = store.state.value.payload
        assertEquals((1..120).map { "body $it" }, last.messages.map { it.body })
        assertFalse(last.canLoadMoreBefore, "window exhausted and the cursor says it reaches the channel start")
        assertTrue(last.durableReachedOldest, "the stored window reaches the conversation's first message")
        assertEquals(
            Res.string.thread_state_beginning_conversation.desc(),
            threadOlderMessagesStatusSpec(last)?.label,
            "the real beginning is labelled as such, not as an offline cut",
        )
        assertEquals(1, api.channelMessageCalls, "no older page went to the network")
        assertEquals(3, logs.count { "thread_durable_older_page" in it && "served=30" in it }, logs.toString())
        assertTrue(logs.any { "served=30 reason=reached_oldest" in it }, logs.toString())
        scopeJob.cancelAndJoin()
    }

    @Test
    fun windowNotReachingTheChannelStartStopsCleanlyOfflineWithOuterBoundaryAndRestoresOnlineOnRefresh() = runTest {
        // Twin of the test above: identical 120 facts, but the cursor says older history exists.
        // #proj-mobile:#388: offline stops cleanly at the contiguous cached window edge with the
        // single outer "Older messages are not available offline" boundary row (no failed network
        // retry spam), and reconnecting via Refresh restores online older paging seamlessly.
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val store =
            SharedThreadStore(dataSource(storeScope = backgroundScope, api, FakeReader(frame(count = 120, hasLoadedOldest = false)), logs = logs), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        repeat(3) { store.accept(SharedThreadIntent.LoadOlder) }
        val exhausted = store.state.value.payload
        assertEquals(120, exhausted.messages.size)
        assertFalse(exhausted.canLoadMoreBefore, "offline closes paging cleanly at contiguous window edge")
        assertFalse(exhausted.durableReachedOldest, "window does not reach the start")
        assertEquals("idle", exhausted.olderMessagesStatus)
        assertEquals(
            Res.string.thread_offline_older_unavailable.desc(),
            threadOlderMessagesStatusSpec(exhausted)?.label,
            "single outer boundary row says older messages are unavailable offline",
        )
        assertEquals(1, api.channelMessageCalls, "only the (failed) initial refresh went to the network")

        // Additional LoadOlder while offline stays a clean no-op with 0 extra network attempts.
        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(1, api.channelMessageCalls)
        assertEquals("idle", store.state.value.payload.olderMessagesStatus)
        assertFalse(store.state.value.payload.canLoadMoreBefore)
        assertFalse(store.state.value.payload.durableReachedOldest)

        // Connectivity returns -> Refresh replaces the offline stand-in and restores online paging.
        api.failChannelMessages = false
        store.accept(SharedThreadIntent.Refresh)
        val reconnected = store.state.value.payload
        assertFalse(reconnected.durableLastKnown)
        assertFalse(reconnected.mainLoadFailed)
        assertEquals(2, api.channelMessageCalls)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun onlineRefreshReplacesTheStandInIncludingItsDiskPages() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val loadingStateApplied = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val store =
            SharedThreadStore(
                dataSource = dataSource(storeScope = backgroundScope, SharedThreadStoreTest.FakeThreadApi(), FakeReader(frame(count = 120, hasLoadedOldest = true))),
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
    fun anotherOwnerNeverPagesTheStandInFromDisk() = runTest {
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader = FakeReader(frame(count = 120, hasLoadedOldest = true))
        var identity = ownerA
        val store = SharedThreadStore(dataSource(storeScope = backgroundScope, api, reader, identity = { identity }, logs = logs), scope = scope)
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

    private class FakeReader(private val frame: ThreadDurableLastKnownFrame) : ThreadDurableLastKnownReader {
        val reads = mutableListOf<Pair<DurablePartitionKey, SyncScopeId>>()

        override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? {
            reads += partition to channelId
            return frame.takeIf { it.scope.partition == partition && it.scope.scopeId == channelId }
        }
    }

    private fun dataSource(
        storeScope: CoroutineScope,
        api: SharedThreadStoreTest.FakeThreadApi,
        reader: ThreadDurableLastKnownReader,
        identity: () -> ThreadReplyCacheIdentitySnapshot = { ownerA },
        logs: MutableList<String> = mutableListOf(),
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            threadParseDispatcher = storeScope.testDispatcher(),
            messageStore = SharedThreadMessageStore(scope = storeScope),
            payloadStore = SharedThreadPayloadStore(scope = storeScope),
            networkApi = api,
            sessionStore = InMemoryRaftAuthSessionStore(identity().session),
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
    private fun frame(
        count: Int,
        hasLoadedOldest: Boolean,
        actionMetadataBySeq: Map<Int, CanonicalActionMetadataV2> = emptyMap(),
    ): ThreadDurableLastKnownFrame =
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
                        actionMetadata = actionMetadataBySeq[index],
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

    private fun basePayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )
}
