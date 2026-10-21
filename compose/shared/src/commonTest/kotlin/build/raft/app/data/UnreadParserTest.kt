package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnreadParserTest {
    // `channels/unread?summary=1` parsing lives in ChannelUnreadSummaryParserTest.

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
