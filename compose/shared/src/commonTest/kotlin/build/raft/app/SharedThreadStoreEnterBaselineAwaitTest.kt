package build.raft.app

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
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #390 / #18, since message storage Phase 2b: the entry's own page request is the durable write, so there is no
 * separate baseline load to wait for. The first frame is the disk frame when there is one (read once), the plain loading
 * shell when there is none (read once, no wait), and a memory candidate never reads disk.
 */
class SharedThreadStoreEnterBaselineAwaitTest {
    @Test
    fun anEmptyDiskIsReadOnceAndTheEntryNeverWaits() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val reader = MutableFakeReader()
        val loader = SharedThreadStoreEnteredScopeTest.FakeLoader()
        val logs = mutableListOf<String>()
        val store =
            SharedThreadStore(
                dataSource = dataSource(reader = reader, loader = loader, storeScope = backgroundScope),
                scope = scope,
                diagnosticsLogger = logs::add,
            )
        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals(listOf(partition(ownerA) to "channel-1"), loader.loads)
        assertEquals(listOf(partition(ownerA) to "channel-1"), reader.reads)
        assertFalse(logs.any { "thread_enter_baseline_await" in it }, logs.toString())
        assertFalse(payload.durableLastKnown)
        scope.cancel()
    }

    @Test
    fun aFrameAlreadyOnDiskIsTheFirstFrameWithoutWaitingForTheLoad() = runTest {
        // Task #18 (Hands e342666b): with a frame on disk the entry must not wait for the baseline
        // load (on a real network it never finished inside the window). The load here never ends; the
        // first frame is still the disk frame, nothing waited, and the load is left running.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val reader = MutableFakeReader()
        reader.frames[partition(ownerA)] = frame("disk body")
        val loader = SharedThreadStoreEnteredScopeTest.FakeLoader()
        val logs = mutableListOf<String>()
        val store =
            SharedThreadStore(
                dataSource = dataSource(reader = reader, loader = loader, storeScope = backgroundScope),
                scope = scope,
                diagnosticsLogger = logs::add,
            )
        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertTrue(payload.durableLastKnown)
        assertEquals(listOf("disk body"), payload.messages.map { it.body })
        assertEquals(listOf(partition(ownerA) to "channel-1"), reader.reads)
        assertFalse(logs.any { "thread_enter_baseline_await" in it }, logs.toString())
        assertEquals(listOf(partition(ownerA) to "channel-1"), loader.loads)
        scope.cancel()
    }

    @Test
    fun memoryCachedEntryNeverReadsDiskEvenWhenTheLoaderCommits() = runTest {
        // The disk read lives strictly inside the no-memory-candidate branch: a cached payload is newer by
        // construction, so a frame on disk is not read this entry.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val reader = MutableFakeReader()
        reader.frames[partition(ownerA)] = frame("baseline body")
        val loader = SharedThreadStoreEnteredScopeTest.FakeLoader()
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val dataSource = dataSource(api = api, reader = reader, loader = loader, storeScope = backgroundScope)
        assertNotNull(dataSource.readSession())
        assertTrue(dataSource.cachePayload(memoryContent("memory cached body"), serverId = "server-1"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals(listOf("memory cached body"), store.state.value.payload.messages.map { it.body })
        assertFalse(store.state.value.payload.durableLastKnown)
        assertTrue(reader.reads.isEmpty(), reader.reads.toString())
        scope.cancel()
    }

    private class MutableFakeReader : ThreadDurableLastKnownReader {
        val frames = mutableMapOf<DurablePartitionKey, ThreadDurableLastKnownFrame>()
        val reads = mutableListOf<Pair<DurablePartitionKey, SyncScopeId>>()

        override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? {
            reads += partition to channelId
            return frames[partition]?.takeIf { it.scope.scopeId == channelId }
        }

        override suspend fun readReplyThread(
            partition: DurablePartitionKey,
            threadChannelId: SyncScopeId,
            replyParent: ThreadDurableReplyParent,
        ): ThreadDurableLastKnownFrame? = null
    }

    private fun dataSource(
        api: SharedThreadStoreTest.FakeThreadApi = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
        reader: MutableFakeReader,
        loader: SharedThreadStoreEnteredScopeTest.FakeLoader,
        storeScope: CoroutineScope,
    ): SharedThreadDataSource =
        SharedThreadStoreEnteredScopeTest().dataSource(
            storeScope = storeScope,
            api = api,
            loader = loader,
            reader = reader,
            identity = { ownerA },
        )

    private fun partition(identity: ThreadReplyCacheIdentitySnapshot): DurablePartitionKey =
        DurablePartitionKey(
            serverId = identity.serverId,
            principalId = identity.principalId,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = identity.environmentId,
        )

    private fun frame(body: String): ThreadDurableLastKnownFrame =
        ThreadDurableLastKnownFrame(
            scope = SyncScopeKey(partition(ownerA), "channel-1", "channel"),
            envelopes = listOf(
                CanonicalMessageEnvelopeV2(
                    fact = CanonicalMessageNodeV2(
                        id = "v2-1",
                        seq = 5,
                        senderType = "user",
                        senderId = "u-a",
                        content = body,
                        messageType = "chat",
                        createdAt = "2026-05-19T16:00:00Z",
                        senderName = "Owner A",
                    ),
                    context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", "channel-1")),
                ),
            ),
            cursor = PersistedCursor(5, 5, 5, hasLoadedOldest = true, epoch = "epoch-v2"),
        )

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

    private fun basePayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )

    private fun memoryContent(body: String): RaftThreadScreenPayload =
        build.raft.app.data.buildSharedThreadPayloadFromMessagesPage(
            basePayload = basePayload(),
            page = build.raft.app.data.ThreadMessagesPage(
                messages = listOf(
                    build.raft.app.data.WireMessage(
                        id = "m1",
                        channelId = "channel-1",
                        senderType = "user",
                        senderId = "u-a",
                        senderName = "Owner A",
                        messageType = "chat",
                        content = body,
                    ),
                ),
                canLoadMoreBefore = false,
                canLoadMoreAfter = null,
            ),
            session = ownerA.session,
        )
}
