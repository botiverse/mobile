package build.raft.app

import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #1124: what the thread store does around the enter-snapshot loader — for which routes it
 * asks, with which partition, and that the first frame never depends on it. The loader itself is
 * exercised in `CanonicalMessageV2EnteredScopeLoaderTest` and on SQLite in the round-trip test.
 */
class SharedThreadStoreEnteredScopeTest {
    @Test
    fun channelEntryRequestsOneLoadForTheCurrentOwnersPartition() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val loader = FakeLoader()
        val store = SharedThreadStore(dataSource = dataSource(storeScope = backgroundScope, loader = loader, identity = { ownerA }), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(listOf(partition(ownerA) to "channel-1"), loader.loads)
        assertEquals(listOf(true), loader.stillCurrentAtCall)
        scope.cancel()
    }

    @Test
    fun directMessageEntryRequestsTheLoadToo() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val loader = FakeLoader()
        val store = SharedThreadStore(dataSource = dataSource(storeScope = backgroundScope, loader = loader, identity = { ownerA }), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(surfaceKind = RaftThreadSurfaceKind.DirectMessage)))
        assertEquals(listOf(partition(ownerA) to "channel-1"), loader.loads)
        scope.cancel()
    }

    @Test
    fun memoryCachedEntryStillRefreshesTheBaseline() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val loader = FakeLoader()
        val dataSource = dataSource(
            storeScope = backgroundScope,
            api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
            loader = loader,
            identity = { ownerA },
        )
        assertNotNull(dataSource.readSession())
        assertTrue(dataSource.cachePayload(contentPayload("memory cached body"), serverId = "server-1"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(listOf("memory cached body"), store.state.value.payload.messages.map { it.body })
        assertEquals(listOf(partition(ownerA) to "channel-1"), loader.loads)
        scope.cancel()
    }

    @Test
    fun replyRoutesAndRoutesWithoutAnOwnerNeverRequestTheLoad() = runTest {
        // A reply route is never loaded as a top-level window (task #168 step 3: its own entry is
        // `loadReplyThreadOnEnter`, covered in SharedThreadStoreReplyThreadDurableTest).
        val replyScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val replyLoader = FakeLoader()
        SharedThreadStore(dataSource = dataSource(storeScope = backgroundScope, loader = replyLoader, identity = { ownerA }), scope = replyScope)
            .accept(SharedThreadIntent.Start(replyRoute()))
        assertTrue(replyLoader.loads.isEmpty(), replyLoader.loads.toString())
        replyScope.cancel()

        // Auth generation 0 is the registry's "no session" sentinel: no owner ⇒ no partition ⇒ no write.
        val noOwnerScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val noOwnerLoader = FakeLoader()
        SharedThreadStore(
            dataSource = dataSource(storeScope = backgroundScope, loader = noOwnerLoader, identity = { ownerA.copy(authGeneration = 0L) }),
            scope = noOwnerScope,
        ).accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(noOwnerLoader.loads.isEmpty(), noOwnerLoader.loads.toString())
        noOwnerScope.cancel()
    }

    internal class FakeLoader(
        private val effect: suspend (partition: DurablePartitionKey, channelId: SyncScopeId) -> Unit = { _, _ -> },
    ) : ThreadEnteredScopeSnapshotLoader {
        val loads = mutableListOf<Pair<DurablePartitionKey, SyncScopeId>>()
        val stillCurrentAtCall = mutableListOf<Boolean>()

        override suspend fun loadOnEnter(partition: DurablePartitionKey, channelId: SyncScopeId, stillCurrent: () -> Boolean) {
            loads += partition to channelId
            stillCurrentAtCall += stillCurrent()
            effect(partition, channelId)
        }
    }

    internal fun dataSource(
        storeScope: CoroutineScope,
        api: SharedThreadStoreTest.FakeThreadApi = SharedThreadStoreTest.FakeThreadApi(),
        loader: ThreadEnteredScopeSnapshotLoader,
        reader: ThreadDurableLastKnownReader? = null,
        identity: () -> ThreadReplyCacheIdentitySnapshot,
        diagnosticsLogger: (String) -> Unit = {},
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
            enteredScopeSnapshotLoader = loader,
            diagnosticsLogger = diagnosticsLogger,
        )

    private fun partition(identity: ThreadReplyCacheIdentitySnapshot): DurablePartitionKey =
        DurablePartitionKey(
            serverId = identity.serverId,
            principalId = identity.principalId,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = identity.environmentId,
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

    private fun replyRoute(): RaftThreadScreenPayload =
        basePayload().copy(
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-parent-1",
            streamChannelId = "thread-parent-1",
            parentChannelId = "channel-1",
            parentMessageId = "parent-1",
            originParentMessageId = "parent-1",
            originStatus = "loading",
        )

    private fun contentPayload(body: String): RaftThreadScreenPayload =
        buildSharedThreadPayloadFromMessagesPage(
            basePayload = basePayload(),
            page = ThreadMessagesPage(
                messages = listOf(
                    WireMessage(
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
