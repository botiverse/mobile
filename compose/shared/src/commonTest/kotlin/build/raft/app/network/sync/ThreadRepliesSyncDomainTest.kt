package build.raft.app.network.sync

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadUpdated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreadRepliesSyncDomainTest {
    private val page =
        ThreadRepliesPageIdentity(
            serverId = "server-1",
            principalId = "principal-1",
            parentScopeKind = "channel",
            parentScopeId = "channel-1",
        )
    private val scope =
        ThreadRepliesScopeIdentity(
            page = page,
            parentMessageId = "parent-1",
            threadChannelId = "thread-1",
        )

    @Test
    fun authoritativeRebaselineMayLowerAcceptedCountWithoutParkedCreate() {
        val host = ThreadRepliesSyncHost()
        assertEquals(true, host.beginAuthoritativeRebaseline(page, 1))
        accepted(host, 1, replies = 1L..3L, count = 5)

        assertEquals(true, host.beginAuthoritativeRebaseline(page, 2))
        val lowered = accepted(host, 2, replies = 2L..4L, count = 4)

        assertEquals(4, lowered.model.replyCount)
        assertEquals(listOf("reply-2", "reply-3", "reply-4"), lowered.model.replies.map { it.messageId })
    }

    @Test
    fun parkedNewCreateReplaysAfterSnapshotWithoutRollback() {
        val host = ThreadRepliesSyncHost()
        assertEquals(true, host.beginAuthoritativeRebaseline(page, 1))
        accepted(host, 1, replies = 1L..3L, count = 3)

        assertEquals(true, host.beginAuthoritativeRebaseline(page, 2))
        val live = assertNotNull(host.ingestFrame(page, frame(seq = 5, count = 5)))
        assertEquals(listOf("reply-2", "reply-3", "reply-5"), live.model.replies.map { it.messageId })
        val merged = accepted(host, 2, replies = 2L..4L, count = 4)

        assertEquals(5, merged.model.replyCount)
        assertEquals(listOf("reply-3", "reply-4", "reply-5"), merged.model.replies.map { it.messageId })
    }

    @Test
    fun parkedDuplicateDoesNotInflateSnapshotButNewCreateDoes() {
        val host = ThreadRepliesSyncHost()
        assertEquals(true, host.beginAuthoritativeRebaseline(page, 1))
        assertNotNull(host.ingestFrame(page, frame(seq = 4, count = 4)))
        val duplicate = accepted(host, 1, replies = 2L..4L, count = 4)
        assertEquals(4, duplicate.model.replyCount)
        assertEquals(listOf("reply-2", "reply-3", "reply-4"), duplicate.model.replies.map { it.messageId })

        assertEquals(true, host.beginAuthoritativeRebaseline(page, 2))
        assertNotNull(host.ingestFrame(page, frame(seq = 5, count = 5)))
        val advanced = accepted(host, 2, replies = 2L..4L, count = 4)
        assertEquals(5, advanced.model.replyCount)
        assertEquals(listOf("reply-3", "reply-4", "reply-5"), advanced.model.replies.map { it.messageId })
    }

    @Test
    fun systemCreateAdvancesCountWithoutConsumingConversationSlot() {
        val host = ThreadRepliesSyncHost()
        assertEquals(true, host.beginAuthoritativeRebaseline(page, 1))
        assertNotNull(host.ingestFrame(page, frame(seq = 4, count = 4, senderType = "system")))
        val accepted = accepted(host, 1, replies = 1L..3L, count = 3)

        assertEquals(4, accepted.model.replyCount)
        assertEquals(listOf("reply-1", "reply-2", "reply-3"), accepted.model.replies.map { it.messageId })
    }

    @Test
    fun firstFrameOnUnseenScopeFoldsIntoRenderedBaselineButNeverOverridesAcceptedScope() {
        val host = ThreadRepliesSyncHost()
        val baseline = initialThreadRepliesReadModel((1L..3L).map { reply(it) }, 3, InlineThreadReplyPreviewLimit)
        // Cold cache: the Store renders 3 rows the host has never accepted.
        val live = assertNotNull(host.ingestFrame(page, frame(seq = 5, count = 5), baseline = baseline))
        assertEquals(listOf("reply-2", "reply-3", "reply-5"), live.model.replies.map { it.messageId })
        assertEquals(5, live.model.replyCount)
        // A reconnect replay of a baseline reply is a no-op, not a spurious row.
        val replay = assertNotNull(host.ingestFrame(page, frame(seq = 3, count = 5), baseline = baseline))
        assertEquals(live.model, replay.model)
        // Once accepted, a (stale) baseline is ignored.
        val stale = initialThreadRepliesReadModel((7L..9L).map { reply(it) }, 9, InlineThreadReplyPreviewLimit)
        val next = assertNotNull(host.ingestFrame(page, frame(seq = 6, count = 6), baseline = stale))
        assertEquals(listOf("reply-3", "reply-5", "reply-6"), next.model.replies.map { it.messageId })
    }

    @Test
    fun invalidEnvelopeFailsClosed() {
        val host = ThreadRepliesSyncHost()
        assertNull(host.ingestFrame(page, frame(seq = 4, count = 4, producer = "other")))
    }

    @Test
    fun staleGenerationCannotSwapAcceptedProjection() {
        val host = ThreadRepliesSyncHost()
        assertEquals(true, host.beginAuthoritativeRebaseline(page, 2))
        assertNull(
            host.ingestAuthoritativeSnapshots(
                page = page,
                generation = 1,
                snapshots = listOf(snapshot(1L..3L, 3)),
            )
        )
    }

    private fun accepted(
        host: ThreadRepliesSyncHost,
        generation: Long,
        replies: LongRange,
        count: Int,
    ): ThreadRepliesAcceptedProjection =
        assertNotNull(
            host.ingestAuthoritativeSnapshots(
                page = page,
                generation = generation,
                snapshots = listOf(snapshot(replies, count)),
            )
        ).getValue("parent-1")

    private fun snapshot(replies: LongRange, count: Int): ThreadRepliesAuthoritativeSnapshot =
        ThreadRepliesAuthoritativeSnapshot(
            identity = scope,
            replies = replies.map { reply(it) },
            replyCount = count,
        )

    private fun frame(
        seq: Long,
        count: Int,
        senderType: String = "user",
        producer: String = ThreadRepliesSyncWindowProducer,
    ): ThreadUpdated =
        ThreadUpdated(
            parentMessageId = MessageId("parent-1"),
            threadChannelId = ChannelId("thread-1"),
            replyCount = count,
            lastReplyAt = null,
            participantIds = emptyList<MemberId>(),
            latestReply = reply(seq, senderType),
            syncCoreReplyWindow =
                ThreadRepliesSyncWindowEnvelope(
                    producer = producer,
                    rootKind = "message",
                    rootServerId = "server-1",
                    rootMessageId = "parent-1",
                    relationKind = "replies",
                    parentScopeServerId = "server-1",
                    parentScopeKind = "channel",
                    parentScopeId = "channel-1",
                    backing = "sync-scope",
                    windowKind = "sync-scope-window",
                    scopeCursor = null,
                    epoch = null,
                ),
        )

    private fun reply(seq: Long, senderType: String = "user"): ReplyPreview =
        ReplyPreview(
            messageId = "reply-$seq",
            seq = seq,
            content = "reply $seq",
            senderId = "sender-$seq",
            senderType = senderType,
            senderName = "sender-$seq",
            createdAt = "2026-07-25T00:00:00Z",
        )
}
