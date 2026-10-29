package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelId
import build.raft.app.model.PerReceiverReadStateChanged
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The "marked unread" flag on the wire (botiverse/slock #11795 at 1442d689c7, stacked on #11792 at
 * 3b030eeff1). Canonical payloads are the ones the server's own regression test asserts
 * (`packages/server/src/routes/channels.markedUnread.api.test.ts`) and emits
 * (`routes/channels.ts` `/:id/unread`, `emitReadStateUpdated[Bulk]`;
 * `packages/shared/src/inboxScopeReadFrontier.ts` `InboxScopeReadFrontier.markedUnread?: true`).
 */
class MarkedUnreadContractTest {

    @Test
    fun unreadAnswerTellsCountedMarkedAndNothingToMarkApart() {
        // (case, `POST /channels/:id/unread` body, expected outcome, expected markedUnread)
        val cases =
            listOf(
                Triple(
                    "marked (T3: nothing countable)",
                    """{"ok":true,"unreadCount":0,"maxReadSeq":12,"readStateVersion":9,"markedUnread":true}""",
                    MarkUnreadOutcome.Marked to true
                ),
                Triple(
                    "refused (empty conversation or not a member)",
                    """{"ok":true,"unreadCount":0,"maxReadSeq":0,"readStateVersion":0,"markedUnread":false}""",
                    MarkUnreadOutcome.NothingToMark to false
                ),
                Triple(
                    "server older than the mark",
                    """{"ok":true,"unreadCount":0,"maxReadSeq":0,"readStateVersion":0}""",
                    MarkUnreadOutcome.NothingToMark to null
                ),
                Triple(
                    "rewind over something countable",
                    """{"ok":true,"unreadCount":2,"maxReadSeq":10,"readStateVersion":9,"markedUnread":false}""",
                    MarkUnreadOutcome.Counted to false
                )
            )
        cases.forEach { (name, body, expected) ->
            val result = parseMarkConversationUnreadResult(body, ChannelId("thread-1")) ?: error("dropped: $name")
            assertEquals(expected.first, result.markUnreadOutcome(), name)
            assertEquals(expected.second, result.markedUnread, name)
        }
        val marked = parseMarkConversationUnreadResult(cases.first().second, ChannelId("thread-1"))!!
        assertEquals(9L, marked.readStateVersion)
        assertEquals(12L, marked.maxReadSeq)
    }

    @Test
    fun serverOutcomeWinsAndAnUnknownOneFallsBackToTheInference() {
        // botiverse/slock #11854 (6893779e): `outcome` decided in the read-state transaction, as
        // `channels.markedUnread.api.test.ts` asserts it. (case, body, expected outcome)
        val cases =
            listOf(
                Triple(
                    "marked",
                    """{"ok":true,"unreadCount":0,"maxReadSeq":12,"readStateVersion":9,"markedUnread":true,"outcome":"marked"}""",
                    MarkUnreadOutcome.Marked
                ),
                Triple(
                    "counted",
                    """{"ok":true,"unreadCount":2,"maxReadSeq":10,"readStateVersion":9,"markedUnread":false,"outcome":"counted"}""",
                    MarkUnreadOutcome.Counted
                ),
                Triple(
                    "nothing",
                    """{"ok":true,"unreadCount":0,"maxReadSeq":0,"readStateVersion":0,"markedUnread":false,"outcome":"nothing"}""",
                    MarkUnreadOutcome.NothingToMark
                ),
                // The server's decision is taken as given, not re-derived from the other fields.
                Triple(
                    "outcome disagrees with the inference",
                    """{"ok":true,"unreadCount":3,"maxReadSeq":10,"readStateVersion":9,"markedUnread":false,"outcome":"nothing"}""",
                    MarkUnreadOutcome.NothingToMark
                ),
                Triple(
                    "unknown outcome: inferred from unreadCount",
                    """{"ok":true,"unreadCount":2,"maxReadSeq":10,"readStateVersion":9,"markedUnread":false,"outcome":"snoozed"}""",
                    MarkUnreadOutcome.Counted
                ),
                Triple(
                    "unknown outcome: inferred from markedUnread",
                    """{"ok":true,"unreadCount":0,"maxReadSeq":12,"readStateVersion":9,"markedUnread":true,"outcome":"snoozed"}""",
                    MarkUnreadOutcome.Marked
                )
            )
        cases.forEach { (name, body, expected) ->
            val result = parseMarkConversationUnreadResult(body, ChannelId("thread-1")) ?: error("dropped: $name")
            assertEquals(expected, result.markUnreadOutcome(), name)
        }
    }

    @Test
    fun sidebarSummaryReportsMarkedScopesWithoutCountingThem() {
        val summary =
            parseChannelUnreadSummary(
                """
                {"channels":{
                  "thread-1":{"unreadCount":0,"hasMention":false,"hasAnyMention":false,
                    "readState":{"kind":"present","readStateVersion":9,"maxReadSeq":"12","latestActivity":null,"markedUnread":true}},
                  "dm-1":{"unreadCount":0,"hasMention":false,"hasAnyMention":false,
                    "readState":{"kind":"present","readStateVersion":4,"maxReadSeq":"30","latestActivity":{"messageId":"m-30","seq":"30"},"markedUnread":true}},
                  "channel-1":{"unreadCount":3,"hasMention":false,"hasAnyMention":false,
                    "readState":{"kind":"present","readStateVersion":2,"maxReadSeq":"40","latestActivity":{"messageId":"m-43","seq":"43"}}},
                  "channel-2":{"unreadCount":0,"hasMention":false,"hasAnyMention":false,"readState":{"kind":"absent"}}
                },"threadUnread":{}}
                """.trimIndent()
            ) ?: error("summary dropped")

        assertEquals(setOf("thread-1", "dm-1"), summary.markedUnreadIds)
        assertEquals(3, summary.unreadCounts.values.sum(), "a mark never adds to a count")
    }

    @Test
    fun activityRowsCarryTheMarkOnlyFromAPresentFrontier() {
        // (case, readState JSON or null for "field omitted", expected mark)
        val cases =
            listOf(
                Triple("marked", """{"kind":"present","readStateVersion":9,"maxReadSeq":"12","latestActivity":null,"markedUnread":true}""", true),
                Triple(
                    "marked with a malformed latestActivity",
                    """{"kind":"present","readStateVersion":9,"maxReadSeq":"12","latestActivity":"x","markedUnread":true}""",
                    true
                ),
                Triple("not marked (absent field)", """{"kind":"present","readStateVersion":9,"maxReadSeq":"12","latestActivity":null}""", false),
                Triple("explicit false", """{"kind":"present","readStateVersion":9,"maxReadSeq":"12","latestActivity":null,"markedUnread":false}""", false),
                Triple("absent frontier", """{"kind":"absent","markedUnread":true}""", false),
                Triple("malformed mark", """{"kind":"present","readStateVersion":9,"maxReadSeq":"12","markedUnread":{}}""", false),
                Triple("omitted readState", null, false)
            )
        cases.forEach { (name, readState, expected) ->
            val parsed = parseSharedInboxSnapshot(inboxBody(threadRow(readState))) ?: error("response dropped: $name")
            val row = parsed.items.singleOrNull() ?: error("row dropped: $name")
            assertEquals(expected, row.markedUnread, name)
            assertEquals(expected, row.showsUnread, name)
            assertEquals(expected, row.showsMarkedUnreadDot, name)
        }
    }

    @Test
    fun readStateEventsCarryTheMarkOnlyAsLiteralTrue() {
        // botiverse/slock #11795 T3: the mark's own event, verbatim.
        val markScope = """"scopeId":"thread-1","maxReadSeq":12,"readStateVersion":9,"unreadCount":0"""
        // (case, extra field or empty, expected mark)
        val cases =
            listOf(
                Triple("mark", ""","markedUnread":true""", true),
                Triple("a read omits it", "", false),
                Triple("false", ""","markedUnread":false""", false),
                Triple("string", ""","markedUnread":"true"""", false),
                Triple("number", ""","markedUnread":1""", false)
            )
        cases.forEach { (name, extra, expected) ->
            val single =
                assertIs<PerReceiverReadStateChanged>(
                    RealtimeEventMapper().map(RealtimeSocketEvents.ReadStateUpdated, """{"serverId":"server-1",$markScope$extra}"""),
                    name
                )
            assertEquals(expected, single.scopes.single().markedUnread, name)
            assertEquals(12L, single.scopes.single().maxReadSeq, "$name keeps the watermark")
            val bulk =
                assertIs<PerReceiverReadStateChanged>(
                    RealtimeEventMapper().map(
                        RealtimeSocketEvents.ReadStateUpdatedBulk,
                        """{"serverId":"server-1","scopes":[{$markScope$extra}]}"""
                    ),
                    name
                )
            assertEquals(expected, bulk.scopes.single().markedUnread, "bulk $name")
        }
    }

    @Test
    fun activityToggleAndOpenTreatAMarkedRowAsUnread() {
        val marked = threadItem("thread-1").copy(markedUnread = true)
        val read = threadItem("thread-1")
        val counted = threadItem("thread-1", unreadCount = 2).copy(markedUnread = true)

        assertTrue(marked.showsUnread)
        assertTrue(marked.showsMarkedUnreadDot)
        assertFalse(counted.showsMarkedUnreadDot, "a count wins over the dot")
        assertFalse(read.showsUnread)
    }

    private fun threadRow(readState: String?): String =
        """
        {"kind":"thread","threadChannelId":"t-1","parentMessageId":"p-1","parentChannelId":"c-1",
         "parentChannelName":"general","parentChannelType":"channel","parentMessagePreview":"a question from the peer",
         "parentMessageSenderType":"user","parentMessageSenderId":"u-2","latestActivityPreview":"my second answer",
         "latestActivitySenderType":"user","latestActivitySenderId":"user-1","latestActivitySenderName":null,
         "latestActivityMessageId":"r-2","latestActivitySeq":"12","doneFrontierSeq":"12",
         "firstUnreadMessageId":null,"firstMentionMessageId":null,"lastActivityAt":"2026-10-28T00:00:00Z",
         "lastReplyAt":null,"replyCount":2,"unreadCount":0,"hasMention":false,"taskNumber":null,
         "taskStatus":null,"taskClaimedByName":null${readState?.let { ""","readState":$it""" }.orEmpty()}}
        """.trimIndent()

    private fun inboxBody(vararg rows: String): String =
        """{"items":[${rows.joinToString(",")}],"hasMore":false,"totalCount":${rows.size},"totalUnreadCount":0}"""
}
