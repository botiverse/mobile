package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnreadParserTest {
    @Test
    fun parsesServerUnreadSummaryFromServersRouteContract() {
        val snapshot =
            parseServerUnreadSummarySnapshot(
                bodyText = """
                    [
                      {"serverId":"server-1","unreadCount":7},
                      {"serverId":"server-2","unreadCount":3}
                    ]
                """.trimIndent(),
                selectedServer = ServerSummary(id = ServerId("server-1"), name = "Slock")
            )

        requireNotNull(snapshot)
        assertEquals(2, snapshot.summaries.size)
        assertEquals(10, snapshot.totalUnreadCount)
        assertEquals(7, snapshot.selectedServerUnreadCount)
        assertEquals(7, snapshot.server.unreadCount)
        assertEquals(0, snapshot.mentionCount)
    }

    @Test
    fun rejectsLegacyServerUnreadSummaryEnvelope() {
        val snapshot =
            parseServerUnreadSummarySnapshot(
                bodyText = """
                    {
                      "servers": [{"serverId":"server-1","totalUnreadCount":7}],
                      "totalUnreadCount": 7
                    }
                """.trimIndent(),
                selectedServer = ServerSummary(id = ServerId("server-1"), name = "Slock")
            )

        assertNull(snapshot)
    }
}
