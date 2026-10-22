package build.raft.app

import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.ThreadRepliesSyncWindowEnvelope
import build.raft.app.network.sync.ThreadRepliesSyncWindowProducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hands bc6e392e: a reply in the thread of a DM message arrived while another thread was open. The
 * `thread:updated` event (sent to `user:<id>` for DM parents) reached the open page, which keyed the
 * reply-count fact by itself, so the DM kept its old count and preview until a refetch.
 */
class SharedThreadStoreReplyCountParentScopeTest {
    @Test
    fun aReplyArrivingWhileAnotherThreadIsOpenShowsWhenTheDmIsReopened() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        // Every message fetch fails: whatever the reopened DM shows comes from the shared cache.
        val dataSource = dataSource()
        try {
            seedCache(dataSource, dmPayload())
            val openThread = openPage(dataSource, scope, otherThreadRoute(), otherThreadPayload())

            openThread.dispatch(SharedThreadIntent.ApplyRealtimeEvent(dmReplyUpdated()))
            advanceUntilIdle()
            assertEquals(
                listOf("t1"),
                openThread.state.value.payload.messages.map { it.id },
                "the open thread does not take the DM parent",
            )

            val reopenedDm = openPage(dataSource, scope, dmRoute(), payload = null)
            val parent = reopenedDm.state.value.payload.messages.single { it.id == DmParentId }
            assertEquals(1, parent.replyCount)
            assertEquals(DmThreadId, parent.threadChannelId)
            assertEquals(listOf("reply-1"), parent.inlineReplies?.replies?.map { it.messageId })
            assertEquals(1, parent.inlineReplies?.replyCount)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun aReplyToAParentOnTheOpenPageIsAppliedThereAsBefore() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val dataSource = dataSource()
        val diagnostics = mutableListOf<String>()
        try {
            val openDm = openPage(dataSource, scope, dmRoute(), dmPayload(), diagnostics)

            openDm.dispatch(SharedThreadIntent.ApplyRealtimeEvent(dmReplyUpdated()))
            advanceUntilIdle()

            val parent = openDm.state.value.payload.messages.single { it.id == DmParentId }
            assertEquals(1, parent.replyCount)
            assertEquals(listOf("reply-1"), parent.inlineReplies?.replies?.map { it.messageId })
            assertTrue(
                diagnostics.none { it.startsWith("thread_reply_parent_scope") },
                "the open page owns its own parent: no second write to another conversation, log=$diagnostics",
            )
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    private fun dataSource(): SharedThreadDataSource {
        val sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = UserId, selectedServerId = ServerId))
        return SharedThreadDataSource(
            networkApi = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
            sessionStore = sessionStore,
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = replyThreadAccessIdentityAuthority(sessionStore, TestThreadEnvironmentId),
        )
    }

    private suspend fun TestScope.seedCache(dataSource: SharedThreadDataSource, payload: RaftThreadScreenPayload) {
        dataSource.readSession()
        assertTrue(dataSource.cachePayload(payload, serverId = ServerId), "seeded ${payload.sourceThreadId}")
    }

    /** Opens a page the way the shell does; a seeded [payload] is the page's cached content. */
    private suspend fun TestScope.openPage(
        dataSource: SharedThreadDataSource,
        scope: CoroutineScope,
        route: RaftThreadScreenPayload,
        payload: RaftThreadScreenPayload?,
        diagnostics: MutableList<String>? = null,
    ): SharedThreadStore {
        if (payload != null) seedCache(dataSource, payload)
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = diagnostics?.let { log -> { line: String -> log += line } },
            )
        store.dispatch(SharedThreadIntent.Start(route))
        advanceUntilIdle()
        val opened = store.state.value.payload
        assertTrue(
            opened.messages.isNotEmpty(),
            "waited for ${route.sourceThreadId} to open from the cache, state=${opened.status}",
        )
        return store
    }

    private fun dmRoute(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-$DmId",
            title = "Lagoon",
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            sourceThreadId = DmId,
            streamChannelId = DmId,
            status = "loading",
            canSend = true,
            dmPeerName = "Lagoon",
        )

    private fun dmPayload(): RaftThreadScreenPayload =
        dmRoute().copy(
            status = "content",
            messages =
                listOf(
                    RaftThreadMessageItem(id = DmParentId, authorId = UserId, authorName = "Artin", seq = 10L, body = "parent"),
                ),
        )

    private fun otherThreadRoute(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-$OtherChannelId",
            title = "general",
            sourceThreadId = OtherChannelId,
            streamChannelId = OtherChannelId,
            status = "loading",
            canSend = true,
        )

    private fun otherThreadPayload(): RaftThreadScreenPayload =
        otherThreadRoute().copy(
            status = "content",
            messages =
                listOf(RaftThreadMessageItem(id = "t1", authorId = "u2", authorName = "Lagoon", seq = 5L, body = "other")),
        )

    /** The server's DM-parent frame (botiverse/slock messageService.ts, `parentScopeId: parentMsgForThread.channelId`). */
    private fun dmReplyUpdated(): ThreadUpdated =
        ThreadUpdated(
            parentMessageId = MessageId(DmParentId),
            threadChannelId = ChannelId(DmThreadId),
            replyCount = 1,
            lastReplyAt = "2026-10-21T09:00:00Z",
            participantIds = emptyList(),
            latestReply =
                ReplyPreview(
                    messageId = "reply-1",
                    seq = 1L,
                    content = "on it",
                    senderId = "u2",
                    senderType = "user",
                    senderName = "Lagoon",
                    createdAt = "2026-10-21T09:00:00Z",
                ),
            syncCoreReplyWindow =
                ThreadRepliesSyncWindowEnvelope(
                    producer = ThreadRepliesSyncWindowProducer,
                    rootKind = "message",
                    rootServerId = ServerId,
                    rootMessageId = DmParentId,
                    relationKind = "replies",
                    parentScopeServerId = ServerId,
                    parentScopeKind = "dm",
                    parentScopeId = DmId,
                    backing = "sync-scope",
                    windowKind = "sync-scope-window",
                ),
        )

    private companion object {
        const val ServerId = "server-1"
        const val UserId = "u1"
        const val DmId = "dm-1"
        const val DmParentId = "dm-parent-1"
        const val DmThreadId = "dm-thread-1"
        const val OtherChannelId = "channel-1"
    }
}
