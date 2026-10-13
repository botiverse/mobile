package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeRowRestoredHint
import build.raft.app.model.ChannelId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedHomeRowHintProjectionTest {
    private val hint =
        HomeRowRestoredHint(
            unreadCount = 4,
            lastMessagePreview = "ship it",
            lastMessageAt = "2026-10-12T08:00:00Z",
            lastMessageSenderName = "Ada"
        )
    private val projector = SharedHomeConversationRowProjector(restoredRowHints = mapOf("c1" to hint))
    private val cachedRow = HomeChannelRow(threadId = ThreadId("c1"), channelId = ChannelId("c1"), name = "general")

    @Test
    fun cachedRowShowsTheLastKnownUnreadCountAndPreview() {
        val item = projector.project(cachedRow)

        assertEquals(4, item.unreadCount)
        assertEquals("ship it", item.lastMessagePreview)
        assertEquals("2026-10-12T08:00:00Z", item.lastMessageAt)
        assertEquals("Ada", item.lastMessageSenderName)
    }

    @Test
    fun aKnownCountWinsOverTheHint() {
        // e.g. the conversation was opened (read prediction) before the network Home arrived.
        val item = projector.project(cachedRow.copy(unreadCount = 0, unreadCountKnown = true))

        assertEquals(0, item.unreadCount)
        assertEquals(null, item.lastMessagePreview)
    }

    @Test
    fun rowsWithoutAHintAreUnchanged() {
        val item = projector.project(cachedRow.copy(threadId = ThreadId("c2"), channelId = ChannelId("c2")))

        assertEquals(0, item.unreadCount)
        assertEquals(null, item.lastMessagePreview)
    }
}
