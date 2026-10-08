package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.PerReceiverReadStateScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ThreadReadStateProjectionTest {
    private fun payload(count: Int = 4, replies: Int = 8) = KuiklyThreadScreenPayload(
        sourceThreadId = "parent-channel", instanceId = "parent", status = "content",
        messages = listOf(KuiklyThreadMessageItem(id = "parent", authorId = "u2", authorName = "Peer", threadChannelId = "reply-channel",
            replyCount = replies, unreadReplyCount = count, firstUnreadReplyMessageId = "unread-1")))
    private fun read(count: Int?, version: Long = 2, seq: Long = 10) =
        PerReceiverReadStateScope(ChannelId("reply-channel"), seq, version, count)

    @Test fun unloadedRepliesLowerBadgeAndClearJumpOnlyAtZero() {
        val projection = ThreadReadStateProjection()
        val initial = payload()
        projection.observe(initial, "owner", listOf(read(2)))
        val partial = projection.project(initial, "owner").messages.single()
        assertEquals(2, partial.unreadReplyCount)
        assertEquals("unread-1", partial.firstUnreadReplyMessageId)
        projection.observe(initial, "owner", listOf(read(0, 3)))
        val complete = projection.project(initial, "owner").messages.single()
        assertEquals(0, complete.unreadReplyCount)
        assertNull(complete.firstUnreadReplyMessageId)
    }

    @Test fun zeroHintClearsJumpEvenWhenBadgeAlreadyZero() {
        val projection = ThreadReadStateProjection()
        val initial = payload(count = 0)
        projection.observe(initial, "owner", listOf(read(0)))
        assertNull(projection.project(initial, "owner").messages.single().firstUnreadReplyMessageId)
    }

    @Test fun staleRefreshCannotResurrectBadgeButNewRepliesRemainUnread() {
        val projection = ThreadReadStateProjection()
        val initial = payload()
        projection.observe(initial, "owner", listOf(read(0)))
        assertEquals(0, projection.project(initial.copy(title = "refreshed"), "owner").messages.single().unreadReplyCount)
        assertEquals(1, projection.project(payload(count = 1, replies = 9), "owner").messages.single().unreadReplyCount)
    }

    @Test fun contentEventCanReplaceReadHintWithoutForgettingVersion() {
        val projection = ThreadReadStateProjection()
        val initial = payload()
        projection.observe(initial, "owner", listOf(read(0)))
        projection.contentChanged("reply-channel", latestReplySeq = 11)
        // A deletion followed by a new reply may leave the total unchanged.
        val newer = payload(count = 1)
        assertSame(newer, projection.project(newer, "owner"))
        projection.observe(newer, "owner", listOf(read(0)))
        assertSame(newer, projection.project(newer, "owner"))
    }

    @Test fun duplicateOrLateContentEventsCannotResurrectClearedBadge() {
        val projection = ThreadReadStateProjection()
        val initial = payload()
        projection.observe(initial, "owner", listOf(read(0)))
        for (seq in listOf(null, 9L, 10L)) {
            projection.contentChanged("reply-channel", latestReplySeq = seq)
            assertEquals(0, projection.project(initial, "owner").messages.single().unreadReplyCount)
        }
        projection.contentChanged("reply-channel", latestReplySeq = null)
        assertEquals(1, projection.project(payload(count = 1, replies = 9), "owner").messages.single().unreadReplyCount)
    }

    @Test fun staleDuplicateAndRegressingWatermarksCannotLowerNewerBadge() {
        val projection = ThreadReadStateProjection()
        val initial = payload()
        projection.observe(initial, "owner", listOf(read(2)))
        for (stale in listOf(read(0, 1), read(0, 2), read(0, 3, 9))) {
            projection.observe(initial, "owner", listOf(stale))
            assertEquals(2, projection.project(initial, "owner").messages.single().unreadReplyCount)
        }
    }

    @Test fun countMissingOrHigherDoesNotInventUnreadAndOwnerRouteAreIsolated() {
        val projection = ThreadReadStateProjection()
        val initial = payload()
        projection.observe(initial, "owner", listOf(read(null)))
        assertSame(initial, projection.project(initial, "owner"))
        projection.observe(initial, "owner", listOf(read(20, 3)))
        assertSame(initial, projection.project(initial, "owner"))
        projection.observe(initial, "owner", listOf(read(0, 4)))
        assertSame(initial, projection.project(initial, "another-account"))
        val otherRoute = initial.copy(sourceThreadId = "other-channel", instanceId = "other")
        assertSame(otherRoute, projection.project(otherRoute, "owner"))
    }
}
