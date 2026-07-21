package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun rejectsWrappedChannelUnreadCounts() {
        val snapshot =
            parseChannelUnreadSnapshot(
                bodyText = """{"channels":{"channel-1":7}}""",
                server = ServerSummary(id = ServerId("server-1"), name = "Slock")
            )

        assertNull(snapshot)
    }

    @Test
    fun parsesReadAllReceiptFromChannelsRouteContract() {
        val result =
            parseReadReceiptResult(
                bodyText = """{"ok":true,"seq":42,"readStateVersion":7}""",
                fallbackChannelId = ChannelId("channel-1")
            )

        requireNotNull(result)
        assertEquals(ChannelId("channel-1"), result.channelId)
        assertEquals(42, result.seq)
        assertEquals(42, result.maxReadSeq)
        assertEquals(7, result.readStateVersion)
        assertEquals(0, result.unreadCount)
        assertEquals(true, result.changed)
    }

    @Test
    fun preservesReadToSeqFullPairWithoutSynthesizingMissingMember() {
        val full = parseReadReceiptResult("""{"ok":true,"maxReadSeq":41,"readStateVersion":8}""", ChannelId("c"), 40)
        val incomplete = parseReadReceiptResult("""{"ok":true,"maxReadSeq":41}""", ChannelId("c"), 40)

        requireNotNull(full)
        assertEquals(41, full.maxReadSeq)
        assertEquals(8, full.readStateVersion)
        requireNotNull(incomplete)
        assertEquals(41, incomplete.maxReadSeq)
        assertNull(incomplete.readStateVersion)
    }

    @Test
    fun preservesInboxReadAllScopePairsIndependently() {
        val result =
            parseReadReceiptResult(
                """{"ok":true,"markedCount":2,"scopes":[{"scopeId":"c1","maxReadSeq":4,"readStateVersion":2},{"scopeId":"c2","maxReadSeq":5}]}"""
            )

        requireNotNull(result)
        assertEquals(
            listOf(
                ReadStateV1AckScope(ChannelId("c1"), readStateVersion = 2, maxReadSeq = 4),
                ReadStateV1AckScope(ChannelId("c2"), readStateVersion = null, maxReadSeq = 5),
            ),
            result.scopes,
        )
    }
}
