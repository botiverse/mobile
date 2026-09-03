package build.raft.app

import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #1123: the thread store's offline first frame from the durable V2 partition. The reader is
 * faked here (the real one is exercised on SQLite in `CanonicalMessageV2DurableLastKnownReaderTest`);
 * these tests pin what the store does around it: which partition it asks for, when it asks at all,
 * and how the frame yields to the network answer.
 */
class SharedThreadStoreDurableLastKnownTest {
    @Test
    fun startRendersDurableFrameOnMemoryMissAndNetworkContentReplacesIt() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val reader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body")))
        val loadingStateApplied = CompletableDeferred<KuiklyThreadScreenPayload>()
        val releaseLoadingState = CompletableDeferred<Unit>()
        val dataSource = dataSource(reader = reader, identity = { ownerA })
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                afterRefreshLoadingStateApplied = { payload ->
                    loadingStateApplied.complete(payload)
                    releaseLoadingState.await()
                },
            )

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val firstFrame = loadingStateApplied.await()

            assertEquals(listOf(partition(ownerA) to "channel-1"), reader.reads)
            assertEquals("content", firstFrame.status)
            assertTrue(firstFrame.durableLastKnown)
            assertTrue(firstFrame.backgroundRefreshing)
            assertEquals(listOf("durable body"), firstFrame.messages.map { it.body })
            assertEquals("v2-1", firstFrame.messages.single().id)
            assertTrue(firstFrame.messages.single().isOwn)
            assertFalse(firstFrame.canLoadMoreBefore)
            assertFalse(firstFrame.canLoadMoreAfter)
            assertNull(firstFrame.errorCode)
            assertEquals("content", store.state.value.timelineSlice.status)

            releaseLoadingState.complete(Unit)
            val settled = withTimeout(5_000L) {
                store.state.first { !it.payload.backgroundRefreshing }.payload
            }
            assertEquals("content", settled.status)
            assertFalse(settled.durableLastKnown)
            assertFalse(settled.backgroundRefreshing)
            assertEquals(listOf("network body"), settled.messages.map { it.body })
        } finally {
            releaseLoadingState.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun durableFrameSurvivesTransientFetchFailureButYieldsToCanonicalEmpty() = runBlocking {
        val failingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failingApi = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val failingStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = failingApi,
                    reader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body"))),
                    identity = { ownerA },
                ),
                scope = failingScope,
            )
        failingStore.accept(SharedThreadIntent.Start(basePayload()))
        val afterFailure = failingStore.state.value.payload
        assertEquals(1, failingApi.channelMessageCalls)
        assertEquals("content", afterFailure.status)
        assertTrue(afterFailure.durableLastKnown)
        assertFalse(afterFailure.backgroundRefreshing)
        assertEquals(listOf("durable body"), afterFailure.messages.map { it.body })
        failingScope.cancel()

        val emptyScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val emptyStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = SharedThreadStoreTest.FakeThreadApi(bodyText = """{"historyLimited": false, "messages": []}"""),
                    reader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body"))),
                    identity = { ownerA },
                ),
                scope = emptyScope,
            )
        emptyStore.accept(SharedThreadIntent.Start(basePayload()))
        val afterEmpty = emptyStore.state.value.payload
        assertEquals("empty", afterEmpty.status)
        assertFalse(afterEmpty.durableLastKnown)
        assertTrue(afterEmpty.messages.isEmpty())
        emptyScope.cancel()
    }

    @Test
    fun accountSwitchAsksForTheCurrentOwnersPartitionAndNeverRendersThePreviousOne() = runBlocking {
        // Positive control: owner A's frame renders for owner A.
        val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controlReader = FakeReader(frames = mapOf(partition(ownerA) to frame("owner-a durable body")))
        val controlStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                    reader = controlReader,
                    identity = { ownerA },
                ),
                scope = controlScope,
            )
        controlStore.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(listOf("owner-a durable body"), controlStore.state.value.payload.messages.map { it.body })
        controlScope.cancel()

        // Same server, same device, owner B is now current: only B's partition is consulted, and
        // since B has no baseline the ordinary loading shell is shown — never A's frame.
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val reader = FakeReader(frames = mapOf(partition(ownerA) to frame("owner-a durable body")))
        val loadingStateApplied = CompletableDeferred<KuiklyThreadScreenPayload>()
        val releaseLoadingState = CompletableDeferred<Unit>()
        val store =
            SharedThreadStore(
                dataSource = dataSource(reader = reader, identity = { ownerB }),
                scope = scope,
                afterRefreshLoadingStateApplied = { payload ->
                    loadingStateApplied.complete(payload)
                    releaseLoadingState.await()
                },
            )
        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val loadingPayload = loadingStateApplied.await()
            assertEquals(listOf(partition(ownerB) to "channel-1"), reader.reads)
            assertEquals("loading", loadingPayload.status)
            assertFalse(loadingPayload.durableLastKnown)
            assertTrue(loadingPayload.messages.isEmpty())
            assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        } finally {
            releaseLoadingState.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun noOwnerMemoryCacheAndReplyRoutesNeverConsultTheDurableReader() = runBlocking {
        // Auth generation 0 is the registry's "no session" sentinel: no owner ⇒ no partition ⇒ no read.
        val noOwnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val noOwnerReader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body")))
        val noOwnerStore =
            SharedThreadStore(
                dataSource = dataSource(reader = noOwnerReader, identity = { ownerA.copy(authGeneration = 0L) }),
                scope = noOwnerScope,
            )
        noOwnerStore.accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(noOwnerReader.reads.isEmpty(), noOwnerReader.reads.toString())
        assertFalse(noOwnerStore.state.value.payload.durableLastKnown)
        noOwnerScope.cancel()

        // A memory-cached payload for the same owner wins; disk is never asked.
        val cachedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cachedReader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body")))
        val cachedDataSource = dataSource(
            api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
            reader = cachedReader,
            identity = { ownerA },
        )
        assertNotNull(cachedDataSource.readSession())
        assertTrue(cachedDataSource.cachePayload(contentPayload("memory cached body"), serverId = "server-1"))
        val cachedStore = SharedThreadStore(dataSource = cachedDataSource, scope = cachedScope)
        cachedStore.accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(cachedReader.reads.isEmpty(), cachedReader.reads.toString())
        assertEquals(listOf("memory cached body"), cachedStore.state.value.payload.messages.map { it.body })
        assertFalse(cachedStore.state.value.payload.durableLastKnown)
        cachedScope.cancel()

        // Reply threads have no V2 writer yet; the reader is not their surface.
        val replyScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val replyReader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body")))
        val replyStore =
            SharedThreadStore(
                dataSource = dataSource(reader = replyReader, identity = { ownerA }),
                scope = replyScope,
            )
        replyStore.accept(SharedThreadIntent.Start(replyRoute()))
        assertTrue(replyReader.reads.isEmpty(), replyReader.reads.toString())
        replyScope.cancel()
    }

    private class FakeReader(
        private val frames: Map<DurablePartitionKey, ThreadDurableLastKnownFrame>,
    ) : ThreadDurableLastKnownReader {
        val reads = mutableListOf<Pair<DurablePartitionKey, SyncScopeId>>()

        override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? {
            reads += partition to channelId
            val frame = frames[partition] ?: return null
            return frame.takeIf { it.scope.scopeId == channelId }
        }
    }

    private fun dataSource(
        api: SharedThreadStoreTest.FakeThreadApi = SharedThreadStoreTest.FakeThreadApi(),
        reader: ThreadDurableLastKnownReader,
        identity: () -> ThreadReplyCacheIdentitySnapshot,
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            networkApi = api,
            sessionStore = InMemorySlockAuthSessionStore(identity().session),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = { identity() },
            durableLastKnownReader = reader,
        )

    private fun partition(identity: ThreadReplyCacheIdentitySnapshot): DurablePartitionKey =
        DurablePartitionKey(
            serverId = identity.serverId,
            principalId = identity.principalId,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = identity.environmentId,
        )

    private fun frame(body: String): ThreadDurableLastKnownFrame {
        val scope = SyncScopeKey(partition(ownerA), "channel-1", "channel")
        return ThreadDurableLastKnownFrame(
            scope = scope,
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
    }

    private val ownerA =
        ThreadReplyCacheIdentitySnapshot(
            session = session("u-a", "Owner A"),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-a",
            serverId = "server-1",
            authGeneration = 1L,
            selectionGeneration = 1L,
        )

    private val ownerB =
        ThreadReplyCacheIdentitySnapshot(
            session = session("u-b", "Owner B"),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-b",
            serverId = "server-1",
            authGeneration = 2L,
            selectionGeneration = 1L,
        )

    private fun session(userId: String, name: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@example.test", displayName = name),
            accessToken = "access-$userId",
            refreshToken = "refresh-$userId",
            selectedServerId = "server-1",
        )

    private fun basePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )

    private fun replyRoute(): KuiklyThreadScreenPayload =
        basePayload().copy(
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-parent-1",
            streamChannelId = "thread-parent-1",
            parentChannelId = "channel-1",
            parentMessageId = "parent-1",
            originParentMessageId = "parent-1",
            originStatus = "loading",
        )

    private fun contentPayload(body: String): KuiklyThreadScreenPayload =
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
