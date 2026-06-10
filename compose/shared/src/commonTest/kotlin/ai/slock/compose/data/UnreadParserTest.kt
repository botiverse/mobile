package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class UnreadParserTest {
    @Test
    fun parsesChannelUnreadCountsFromChannelsRouteContract() {
        val snapshot =
            parseChannelUnreadSnapshot(
                bodyText = """
                    {
                      "channel-1": 7,
                      "dm-1": 3
                    }
                """.trimIndent(),
                server = ServerSummary(id = ServerId("server-1"), name = "Slock")
            )

        requireNotNull(snapshot)
        assertEquals(10, snapshot.totalUnreadCount)
        assertEquals(10, snapshot.server.unreadCount)
        assertEquals(7, snapshot.unreadCounts[ChannelId("channel-1")])
        assertEquals(3, snapshot.unreadCounts[ChannelId("dm-1")])
    }

    @Test
    fun parsesReadAllReceiptFromChannelsRouteContract() {
        val result =
            parseReadReceiptResult(
                bodyText = """{"ok":true,"seq":42}""",
                fallbackChannelId = ChannelId("channel-1")
            )

        requireNotNull(result)
        assertEquals(ChannelId("channel-1"), result.channelId)
        assertEquals(42, result.seq)
        assertEquals(0, result.unreadCount)
        assertEquals(true, result.changed)
    }
}
