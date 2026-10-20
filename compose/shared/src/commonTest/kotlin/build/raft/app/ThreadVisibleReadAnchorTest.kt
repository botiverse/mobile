package build.raft.app

import build.raft.app.data.ThreadReadAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadVisibleReadAnchorTest {
    @Test
    fun visibleFrontierUsesHighestCanonicalSeqAndIgnoresPendingRows() {
        val timeline =
            RaftThreadScreenPayload(
                status = "content",
                messages =
                    listOf(
                        message("m10", 10L),
                        message("pending", null),
                        message("m12", 12L),
                    ),
            ).toThreadTimelineSlice()
        val first = timeline.firstMessageListItemIndex()

        assertEquals(
            ThreadReadAnchor(seq = 12L, messageId = "m12"),
            timeline.visibleThreadReadAnchor(listOf(first, first + 1, first + 2)),
        )
        assertNull(timeline.visibleThreadReadAnchor(listOf(first + 1)))
    }

    private fun message(id: String, seq: Long?): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "author",
            authorName = "Author",
            seq = seq,
            body = id,
        )
}
