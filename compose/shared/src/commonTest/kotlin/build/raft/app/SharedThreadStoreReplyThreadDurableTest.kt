package build.raft.app

import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.RawMessagePage
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Every store runs on the test's own `runBlocking` event loop (see `SharedThreadStoreDurableOlderPagingTest`).
 */
class SharedThreadStoreReplyThreadDurableTest {
    @Test
    fun offlineReplyThreadOpenStandsInFromDiskAndPagesOlderFromIt() = runBlocking {
        val logs = mutableListOf<String>()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader = FakeReader(replyFrame(count = 40))
        val store = SharedThreadStore(dataSource(api, reader = reader, logs = logs), scope = scope)
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
    fun offlineReplyThreadOpenWithoutAStoredWindowTakesTheNetworkPath() = runBlocking {
        // Twin of the test above: identical route and failing network, but nothing on disk.
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader = FakeReader(frame = null)
        val store = SharedThreadStore(dataSource(api, reader = reader), scope = scope)
        store.accept(SharedThreadIntent.Start(replyRoute()))

        val payload = store.state.value.payload
        assertFalse(payload.durableLastKnown)
        assertTrue(payload.messages.isEmpty())
        assertTrue(payload.status != "content", payload.status)
        assertEquals(1, api.channelMessageCalls, "the ordinary network load ran")
        assertEquals(listOf(ReplyRead(partition(ownerA), "thread-1", parent)), reader.replyReads)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun replyRouteWithoutItsTypedParentNeverConsultsTheDurableReader() = runBlocking {
        // Twin of the offline stand-in: identical stored frame and stream id, but the route lost its
        // parent channel, so it cannot name the thread scope's parent.
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val reader = FakeReader(replyFrame(count = 40))
        val store =
            SharedThreadStore(
                dataSource(SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true), reader = reader),
                scope = scope,
            )
        store.accept(SharedThreadIntent.Start(replyRoute().copy(parentChannelId = null)))
        assertFalse(store.state.value.payload.durableLastKnown)
        assertTrue(reader.replyReads.isEmpty(), reader.replyReads.toString())
        assertTrue(reader.topLevelReads.isEmpty(), reader.topLevelReads.toString())
        scopeJob.cancelAndJoin()
    }

    @Test
    fun replyThreadEntryRequestsTheReplyThreadSnapshotWithItsParent() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val loader = FakeLoader()
        val store = SharedThreadStore(dataSource(SharedThreadStoreTest.FakeThreadApi(), loader = loader), scope = scope)
        store.accept(SharedThreadIntent.Start(replyRoute()))
        withTimeout(5_000L) { while (loader.replyLoads.isEmpty()) yield() }
        assertEquals(listOf(ReplyRead(partition(ownerA), "thread-1", parent)), loader.replyLoads)
        assertEquals(listOf(true), loader.stillCurrentAtCall)
        assertTrue(loader.topLevelLoads.isEmpty(), "a reply route is never loaded as a top-level window")
        scopeJob.cancelAndJoin()

        // Twin: identical route without its parent channel requests nothing.
        val twinJob = SupervisorJob()
        val twinScope = CoroutineScope(coroutineContext + twinJob)
        val twinLoader = FakeLoader()
        SharedThreadStore(dataSource(SharedThreadStoreTest.FakeThreadApi(), loader = twinLoader), scope = twinScope)
            .accept(SharedThreadIntent.Start(replyRoute().copy(parentChannelId = null)))
        repeat(10) { yield() }
        assertTrue(twinLoader.replyLoads.isEmpty() && twinLoader.topLevelLoads.isEmpty(), twinLoader.replyLoads.toString())
        twinJob.cancelAndJoin()
    }

    @Test
    fun replyThreadPagesAreOfferedToTheExtenderWithTheRoutesParent() = runBlocking {
        val extender = RecordingExtender()
        val dataSource = dataSource(SharedThreadStoreTest.FakeThreadApi(), extender = extender)
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
                replyRoute().copy(surfaceKind = KuiklyThreadSurfaceKind.Channel, sourceThreadId = "channel-1", streamChannelId = null, parentChannelId = null, parentMessageId = null, originParentMessageId = null)
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

    private class FakeReader(private val frame: ThreadDurableLastKnownFrame?) : ThreadDurableLastKnownReader {
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

    private fun dataSource(
        api: SharedThreadStoreTest.FakeThreadApi,
        reader: ThreadDurableLastKnownReader? = null,
        loader: ThreadEnteredScopeSnapshotLoader? = null,
        extender: ThreadDurablePageExtender? = null,
        logs: MutableList<String> = mutableListOf(),
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            networkApi = api,
            sessionStore = InMemorySlockAuthSessionStore(ownerA.session),
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

    private fun replyRoute(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-thread-1",
            title = "Thread",
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-1",
            streamChannelId = "thread-1",
            parentChannelId = "channel-1",
            parentMessageId = "root-1",
            originParentMessageId = "root-1",
            status = "loading",
            canSend = true,
        )
}
