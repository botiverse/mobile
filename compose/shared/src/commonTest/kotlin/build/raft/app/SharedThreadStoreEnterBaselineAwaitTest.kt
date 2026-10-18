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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #390: entering a channel from an unread-activity item used to show a loading shell, then
 * fetch `messages/channel/` once for the durable baseline and AGAIN for the refresh — and if the
 * refresh lost the race (offline, slow network) the thread rendered "cannot load" over messages
 * the baseline load had already committed, i.e. the conversation looked lost. The store now
 * launches the entered-scope load before the offline first-frame read and waits (bounded by
 * `SharedThreadEnterBaselineAwaitMillis`) for it on that path only, so a committed baseline is the
 * first frame itself. The refresh still runs afterwards (it resolves access + canonical fields);
 * the "fetch exactly once" claim is that the baseline writer is the single fetch that produced
 * content, and that first frame never renders empty/loading over committed data.
 */
class SharedThreadStoreEnterBaselineAwaitTest {
    @Test
    fun baselineCommittedInsideTheAwaitWindowIsTheFirstFrameAndTheChannelIsFetchedOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reader = MutableFakeReader()
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        // Stands in for CanonicalMessageV2EnteredScopeLoader.loadAndCommit: the real one writes the
        // snapshot into the durable partition through the generation engine; here the same effect is
        // a frame the last-known reader will return when the store reads right after the wait.
        val loader =
            SharedThreadStoreEnteredScopeTest.FakeLoader { partition, _ ->
                reader.frames[partition] = frame("baseline body")
            }
        val logs = mutableListOf<String>()
        val store =
            SharedThreadStore(
                dataSource = dataSource(api = api, reader = reader, loader = loader),
                scope = scope,
                diagnosticsLogger = logs::add,
            )
        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("content", payload.status)
        assertTrue(payload.durableLastKnown)
        assertEquals(listOf("baseline body"), payload.messages.map { it.body })
        assertEquals(listOf(partition(ownerA) to "channel-1"), loader.loads)
        assertEquals(listOf(partition(ownerA) to "channel-1"), reader.reads)
        assertTrue(logs.any { "thread_enter_baseline_await" in it && "completed=true" in it }, logs.toString())
        // The refresh ran too and failed offline — that is the one `messages/channel/` call the
        // refresh path makes; the baseline load itself fetches through the loader, which the fake
        // replaces with an in-memory commit (the real one is exercised in
        // CanonicalMessageV2EnteredScopeLoaderTest).
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }

    @Test
    fun loaderThatCommitsNothingLeavesTheOrdinaryLoadingShell() = runBlocking {
        // Awaited, finished, wrote nothing (flag off / not a member / failed): the disk read then
        // misses as it does today and the first frame is the plain loading shell — the await must
        // not surface anything the baseline never committed.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reader = MutableFakeReader()
        val loader = SharedThreadStoreEnteredScopeTest.FakeLoader()
        val logs = mutableListOf<String>()
        val store =
            SharedThreadStore(
                dataSource = dataSource(reader = reader, loader = loader),
                scope = scope,
                diagnosticsLogger = logs::add,
            )
        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals(listOf(partition(ownerA) to "channel-1"), loader.loads)
        assertEquals(listOf(partition(ownerA) to "channel-1"), reader.reads)
        assertTrue(logs.any { "thread_enter_baseline_await" in it && "completed=true" in it }, logs.toString())
        assertFalse(payload.durableLastKnown)
        assertTrue(payload.status != "content" || payload.messages.isEmpty(), payload.status)
        scope.cancel()
    }

    @Test
    fun memoryCachedEntryNeverReadsDiskEvenWhenTheLoaderCommits() = runBlocking {
        // The await + disk read live strictly inside the no-memory-candidate branch: a cached
        // payload is newer by construction and the loader's write must not be observed this entry.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reader = MutableFakeReader()
        val loader =
            SharedThreadStoreEnteredScopeTest.FakeLoader { partition, _ ->
                reader.frames[partition] = frame("baseline body")
            }
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val dataSource = dataSource(api = api, reader = reader, loader = loader)
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
    ): SharedThreadDataSource =
        SharedThreadStoreEnteredScopeTest().dataSource(
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

    private fun basePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )

    private fun memoryContent(body: String): KuiklyThreadScreenPayload =
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
