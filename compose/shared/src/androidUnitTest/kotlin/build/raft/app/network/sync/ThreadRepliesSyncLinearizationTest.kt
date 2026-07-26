package build.raft.app.network.sync

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadUpdated
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThreadRepliesSyncLinearizationTest {
    private val page = ThreadRepliesPageIdentity("server-1", "principal-1", "channel", "channel-1")
    private val identity = ThreadRepliesScopeIdentity(page, "parent-1", "thread-1")

    @Test
    fun frameAttemptedAfterPendingConsumeLinearizesAfterSnapshotSwap() {
        val atSwap = CountDownLatch(1)
        val releaseSwap = CountDownLatch(1)
        val frameAttempted = CountDownLatch(1)
        val host =
            ThreadRepliesSyncHost(
                beforeAuthoritativeSwap = {
                    atSwap.countDown()
                    assertTrue(releaseSwap.await(5, TimeUnit.SECONDS))
                },
            )
        assertTrue(host.beginAuthoritativeRebaseline(page, 1))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val snapshot =
                pool.submit<Map<String, ThreadRepliesAcceptedProjection>?> {
                    host.ingestAuthoritativeSnapshots(
                        page,
                        1,
                        listOf(ThreadRepliesAuthoritativeSnapshot(identity, (2L..4L).map(::reply), 4)),
                    )
                }
            assertTrue(atSwap.await(5, TimeUnit.SECONDS))
            val frame =
                pool.submit<ThreadRepliesAcceptedProjection?> {
                    frameAttempted.countDown()
                    host.ingestFrame(page, frame(5, 5))
                }
            assertTrue(frameAttempted.await(5, TimeUnit.SECONDS))
            releaseSwap.countDown()
            assertEquals(1L, assertNotNull(snapshot.get(5, TimeUnit.SECONDS)).getValue("parent-1").revision)
            val acceptedFrame = assertNotNull(frame.get(5, TimeUnit.SECONDS))
            assertEquals(2L, acceptedFrame.revision)
            assertEquals(listOf("reply-3", "reply-4", "reply-5"), acceptedFrame.model.replies.map { it.messageId })
            host.withCurrentAcceptedProjections(listOf(identity)) { current ->
                assertEquals(2L, current.getValue("parent-1").revision)
                assertEquals(5, current.getValue("parent-1").model.replyCount)
                assertEquals(listOf("reply-3", "reply-4", "reply-5"), current.getValue("parent-1").model.replies.map { it.messageId })
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun frameAttemptedBetweenHostReturnAndStoreCommitPublishesAfterLockedCommit() {
        val atCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val frameAttempted = CountDownLatch(1)
        val commitOrder = Collections.synchronizedList(mutableListOf<String>())
        val host =
            ThreadRepliesSyncHost(
                beforeProjectionCommit = {
                    atCommit.countDown()
                    assertTrue(releaseCommit.await(5, TimeUnit.SECONDS))
                },
            )
        assertTrue(host.beginAuthoritativeRebaseline(page, 1))
        assertNotNull(
            host.ingestAuthoritativeSnapshots(
                page,
                1,
                listOf(ThreadRepliesAuthoritativeSnapshot(identity, (2L..4L).map(::reply), 4)),
            )
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val commit =
                pool.submit<Unit> {
                    host.withCurrentAcceptedProjections(listOf(identity)) { current ->
                        commitOrder += "http:${current.getValue("parent-1").model.replyCount}"
                    }
                }
            assertTrue(atCommit.await(5, TimeUnit.SECONDS))
            val frame =
                pool.submit<ThreadRepliesAcceptedProjection?> {
                    frameAttempted.countDown()
                    host.ingestFrame(page, frame(5, 5)).also { projection ->
                        commitOrder += "realtime:${projection?.model?.replyCount}"
                    }
                }
            assertTrue(frameAttempted.await(5, TimeUnit.SECONDS))
            releaseCommit.countDown()
            commit.get(5, TimeUnit.SECONDS)
            assertEquals(2L, assertNotNull(frame.get(5, TimeUnit.SECONDS)).revision)
            assertEquals(listOf("http:4", "realtime:5"), commitOrder)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun frame(seq: Long, count: Int): ThreadUpdated =
        ThreadUpdated(
            parentMessageId = MessageId("parent-1"),
            threadChannelId = ChannelId("thread-1"),
            replyCount = count,
            lastReplyAt = null,
            participantIds = emptyList(),
            latestReply = reply(seq),
            syncCoreReplyWindow =
                ThreadRepliesSyncWindowEnvelope(
                    producer = ThreadRepliesSyncWindowProducer,
                    rootKind = "message",
                    rootServerId = "server-1",
                    rootMessageId = "parent-1",
                    relationKind = "replies",
                    parentScopeServerId = "server-1",
                    parentScopeKind = "channel",
                    parentScopeId = "channel-1",
                    backing = "sync-scope",
                    windowKind = "sync-scope-window",
                ),
        )

    private fun reply(seq: Long): ReplyPreview =
        ReplyPreview(
            messageId = "reply-$seq",
            seq = seq,
            content = "reply $seq",
            senderId = "sender-$seq",
            senderType = "user",
            senderName = "sender-$seq",
            createdAt = "2026-07-25T00:00:00Z",
        )
}
