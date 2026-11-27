package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.authSessionRequiredResult
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.ThreadDurableAccessEvictor
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.dispatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Data-layer plan step 2: the in-memory channel / thread timeline cache is keyed by environment +
 * account + server + conversation only. Server switches and token rotations keep it reachable,
 * the write fence only rejects another account or environment (Tenny rule 1), lost access evicts
 * and leaves (rule 2), and the cache is a per-server LRU (rule 3).
 */
class SharedThreadTimelineCacheRekeyTest {
    @Test
    fun lateResponseIsWrittenToItsServerUnlessTheAccountChanged() {
        val cases =
            listOf(
                // Tenny rule 1: still server-A data, written to A's key.
                Triple("switched to server B", identity(server = "server-b", selection = 2L), true),
                // Twin: same server, another account -> discarded.
                Triple("account changed", identity(principal = "u-other", auth = 2L), false),
            )
        cases.forEach { (label, next, written) ->
            runTest {
                val payloadStore = SharedThreadPayloadStore(backgroundScope)
                val api = SharedThreadStoreTest.FakeThreadApi()
                api.channelMessagesStarted = CompletableDeferred()
                api.releaseChannelMessages = CompletableDeferred()
                var current = identity()
                val dataSource = dataSource(api, payloadStore) { current }
                val ownerA = assertNotNull(identity().ownerScopeId())

                val late = async { dataSource.fetchThreadPayload(channelPayload()) }
                api.channelMessagesStarted?.await()
                current = next
                assertNotNull(dataSource.captureReplyCacheProducerContext())
                api.releaseChannelMessages?.complete(Unit)

                val result = assertIs<DataResult.Error>(late.await(), label)
                assertEquals("thread_reply_cache_provenance_stale", result.error.code, "$label: the page drops it")
                val cachedForA =
                    payloadStore.get("server-a", "channel-1", TestThreadEnvironmentId, "u-a", ownerA)
                if (written) {
                    assertEquals(listOf("network body"), cachedForA?.messages?.map { it.body }, label)
                } else {
                    assertNull(cachedForA, label)
                }
                assertNull(
                    payloadStore.get(next.serverId, "channel-1", TestThreadEnvironmentId, next.principalId, assertNotNull(next.ownerScopeId())),
                    "$label: never written under the new identity's key",
                )
            }
        }
    }

    @Test
    fun forbiddenBackgroundRefreshEvictsMemoryAndDiskAndAsksThePageToLeave() = runTest {
        val api = SharedThreadStoreTest.FakeThreadApi()
        val evictor = RecordingEvictor()
        val dataSource = dataSource(api, SharedThreadPayloadStore(backgroundScope), evictor = evictor) { identity() }
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(dataSource.fetchThreadPayload(channelPayload()))
        assertNotNull(dataSource.cachedPayload("channel-1"))

        api.channelMessagesResponseCode = 403
        api.channelMessagesStarted = CompletableDeferred()
        api.releaseChannelMessages = CompletableDeferred()
        val store = SharedThreadStore(dataSource, scope = backgroundScope)
        store.dispatch(SharedThreadIntent.Start(channelPayload()))
        api.channelMessagesStarted?.await()
        assertFalse(store.state.value.conversationAccessLost, "painted from cache first")
        assertEquals(listOf("network body"), store.state.value.payload.messages.map { it.body })

        api.releaseChannelMessages?.complete(Unit)
        evictor.evicted.await()

        assertTrue(store.state.value.conversationAccessLost, "the page is asked to leave")
        assertNull(dataSource.cachedPayload("channel-1"), "memory timeline evicted")
        assertEquals(
            listOf(partition("server-a", "u-a") to "channel-1"),
            evictor.conversations,
            "durable window evicted for exactly this conversation",
        )
    }

    @Test
    fun onlyAccessFactsCountAsLostAccess() {
        val cases =
            listOf(
                DataResult.NoPermission(PermissionBlocker(code = "thread_forbidden")) to true,
                DataResult.Error(ScreenError(code = "http_404", retryable = false)) to true,
                // 401 is the auth route's problem; an edge block is a network fact.
                authSessionRequiredResult() to false,
                DataResult.NoPermission(PermissionBlocker(code = "thread_forbidden", serverMessage = "edge_blocked")) to false,
                DataResult.Error(ScreenError(code = "server_unavailable", retryable = true)) to false,
                DataResult.Content(Unit) to false,
            )
        cases.forEach { (result, lost) -> assertEquals(lost, result.isConversationAccessLost(), result.toString()) }
    }

    private class RecordingEvictor : ThreadDurableAccessEvictor {
        val conversations = mutableListOf<Pair<DurablePartitionKey, SyncScopeId>>()
        val evicted = CompletableDeferred<Unit>()

        override suspend fun evictConversation(partition: DurablePartitionKey, channelId: SyncScopeId): Int {
            conversations += partition to channelId
            evicted.complete(Unit)
            return 1
        }

        override suspend fun evictServerPartition(partition: DurablePartitionKey) = Unit
    }

    private fun dataSource(
        api: SharedThreadStoreTest.FakeThreadApi,
        payloadStore: SharedThreadPayloadStore,
        evictor: ThreadDurableAccessEvictor? = null,
        identity: () -> ThreadReplyCacheIdentitySnapshot,
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            networkApi = api,
            sessionStore = InMemoryRaftAuthSessionStore(identity().session),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = { identity() },
            payloadStore = payloadStore,
            durableAccessEvictor = evictor,
        )

    private fun identity(
        principal: String = "u-a",
        server: String = "server-a",
        auth: Long = 1L,
        selection: Long = 1L,
    ): ThreadReplyCacheIdentitySnapshot =
        ThreadReplyCacheIdentitySnapshot(
            session =
                StoredAuthSession(
                    user = StoredAuthUser(id = principal, email = "$principal@example.test", displayName = principal),
                    accessToken = "access-$principal-$auth",
                    refreshToken = "refresh-$principal",
                    selectedServerId = server,
                ),
            environmentId = TestThreadEnvironmentId,
            principalId = principal,
            serverId = server,
            authGeneration = auth,
            selectionGeneration = selection,
        )

    private fun ThreadReplyCacheIdentitySnapshot.ownerScopeId(): String? =
        ReplyCacheProvenance(
            environmentId = environmentId,
            principalId = principalId,
            serverId = serverId,
            generation = 1L,
            authGeneration = authGeneration,
            selectionGeneration = selectionGeneration,
        ).threadPayloadOwnerScopeId

    private fun partition(server: String, principal: String): DurablePartitionKey =
        DurablePartitionKey(
            serverId = server,
            principalId = principal,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = TestThreadEnvironmentId,
        )

    private fun channelPayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )
}
