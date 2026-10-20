package build.raft.app

import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.InlineThreadReplyPreviewLimit
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.RaftInMemoryThreadReplyWindowStorage
import build.raft.app.network.sync.RaftThreadReplyWindowCache
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownFrame
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesReadModel
import build.raft.app.network.sync.ThreadRepliesScopeIdentity
import build.raft.app.network.sync.TopLevelScopeRefV2
import build.raft.app.network.sync.initialThreadRepliesReadModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hands dd79da45 ("reply 才会慢慢加载"): after a cold start the thread screen's first frame (the
 * durable last-known V2 frame) must already carry the replies block persisted by the previous
 * process, instead of waiting for the network page and the secondary summary fetch.
 */
class SharedThreadStoreReplyWindowColdStartTest {
    @Test
    fun durableFirstFrameCarriesThePersistedRepliesBlock() = runBlocking {
        val storage = RaftInMemoryThreadReplyWindowStorage()
        val persisted = repliesModel(replyCount = 3)
        // Previous process: the summaries were accepted and written behind.
        val previousProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        RaftThreadReplyWindowCache(storage, previousProcessScope).record(
            listOf(
                ThreadRepliesAcceptedProjection(
                    identity = ThreadRepliesScopeIdentity(page(), parentMessageId = "v2-1", threadChannelId = "thread-1"),
                    model = persisted,
                    revision = 1L,
                )
            )
        )
        assertTrue(storage.values.isNotEmpty(), "the window was written behind")

        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        // Cold start: a fresh cache over the same durable storage.
        val reader = ReplyWindowReader(RaftThreadReplyWindowCache(storage, scope))
        val firstFrameApplied = CompletableDeferred<RaftThreadScreenPayload>()
        val releaseFirstFrame = CompletableDeferred<Unit>()
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = SharedThreadStoreTest.FakeThreadApi(),
                        sessionStore = InMemoryRaftAuthSessionStore(owner.session),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        replyCacheIdentityAuthority = { owner },
                        durableLastKnownReader = reader,
                    ),
                scope = scope,
                afterRefreshLoadingStateApplied = { payload ->
                    firstFrameApplied.complete(payload)
                    releaseFirstFrame.await()
                },
            )
        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val firstFrame = firstFrameApplied.await()

            assertTrue(firstFrame.durableLastKnown)
            val parent = firstFrame.messages.single()
            assertEquals("v2-1", parent.id)
            assertEquals(persisted, parent.inlineReplies, "replies block is rendered from the local cache")
            assertEquals("thread-1", parent.threadChannelId)
            assertEquals(3, parent.replyCount, "the cached count is a lower bound over the stored fact")
            val presentation =
                threadInlineReplyPresentation(
                    model = parent.inlineReplies,
                    replyMetadata =
                        ThreadReplyMetadata(
                            parentMessageId = parent.id,
                            threadChannelId = parent.threadChannelId,
                            replyCount = parent.replyCount,
                            unreadReplyCount = 0,
                            firstUnreadReplyMessageId = null,
                            isThreadFollowed = false,
                        ),
                    hasDraft = false,
                )
            assertEquals(listOf("reply-1", "reply-2"), presentation?.replies?.map { it.messageId })
        } finally {
            releaseFirstFrame.complete(Unit)
            scopeJob.cancelAndJoin()
            previousProcessScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test
    fun anotherPrincipalsWindowsAreNeverShown() = runBlocking {
        val storage = RaftInMemoryThreadReplyWindowStorage()
        val previousProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        RaftThreadReplyWindowCache(storage, previousProcessScope).record(
            listOf(
                ThreadRepliesAcceptedProjection(
                    identity =
                        ThreadRepliesScopeIdentity(
                            page(principalId = "u-b"),
                            parentMessageId = "v2-1",
                            threadChannelId = "thread-1",
                        ),
                    model = repliesModel(replyCount = 3),
                    revision = 1L,
                )
            )
        )
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val firstFrameApplied = CompletableDeferred<RaftThreadScreenPayload>()
        val releaseFirstFrame = CompletableDeferred<Unit>()
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = SharedThreadStoreTest.FakeThreadApi(),
                        sessionStore = InMemoryRaftAuthSessionStore(owner.session),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        replyCacheIdentityAuthority = { owner },
                        durableLastKnownReader = ReplyWindowReader(RaftThreadReplyWindowCache(storage, scope)),
                    ),
                scope = scope,
                afterRefreshLoadingStateApplied = { payload ->
                    firstFrameApplied.complete(payload)
                    releaseFirstFrame.await()
                },
            )
        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val parent = firstFrameApplied.await().messages.single()
            assertNull(parent.inlineReplies)
            assertEquals(2, parent.replyCount)
        } finally {
            releaseFirstFrame.complete(Unit)
            scopeJob.cancelAndJoin()
            previousProcessScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    private class ReplyWindowReader(
        override val replyWindowCache: RaftThreadReplyWindowCache,
    ) : ThreadDurableLastKnownReader {
        override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? =
            if (channelId == "channel-1") frame(partition) else null
    }

    private val owner =
        ThreadReplyCacheIdentitySnapshot(
            session =
                StoredAuthSession(
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

    private companion object {
        fun page(principalId: String = "u-a"): ThreadRepliesPageIdentity =
            ThreadRepliesPageIdentity(
                serverId = "server-1",
                principalId = principalId,
                parentScopeKind = "channel",
                parentScopeId = "channel-1",
            )

        fun repliesModel(replyCount: Int): ThreadRepliesReadModel =
            initialThreadRepliesReadModel(
                replies =
                    (1..2).map { index ->
                        ReplyPreview(
                            messageId = "reply-$index",
                            seq = 10L + index,
                            content = "reply $index",
                            senderId = "u-$index",
                            senderType = "user",
                            senderName = "User $index",
                            createdAt = "2026-10-20T08:0$index:00Z",
                        )
                    },
                replyCount = replyCount,
                limit = InlineThreadReplyPreviewLimit,
            )

        fun frame(partition: DurablePartitionKey): ThreadDurableLastKnownFrame {
            require(partition.consumerId == SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
            return ThreadDurableLastKnownFrame(
                scope = SyncScopeKey(partition, "channel-1", "channel"),
                envelopes =
                    listOf(
                        CanonicalMessageEnvelopeV2(
                            fact =
                                CanonicalMessageNodeV2(
                                    id = "v2-1",
                                    seq = 5,
                                    senderType = "user",
                                    senderId = "u-a",
                                    content = "parent body",
                                    messageType = "chat",
                                    createdAt = "2026-05-19T16:00:00Z",
                                    senderName = "Owner A",
                                    replyCount = 2,
                                ),
                            context =
                                CanonicalConversationContextV2.TopLevel(
                                    TopLevelScopeRefV2.Channel("server-1", "channel-1")
                                ),
                        ),
                    ),
                cursor = PersistedCursor(5, 5, 5, hasLoadedOldest = true, epoch = "epoch-v2"),
            )
        }
    }
}
