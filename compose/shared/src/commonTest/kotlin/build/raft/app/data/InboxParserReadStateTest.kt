package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `readState.latestActivity.seq` (the Done/read overlay marker) is read per row
 * and fails closed: a malformed frontier drops only that row's marker, never the
 * row or the response. Field shapes: InboxPolicyApiItem + InboxScopeReadFrontier
 * (botiverse/slock packages/server/src/services/inboxPolicyModel.ts,
 * packages/shared/src/inboxScopeReadFrontier.ts @ 1bb70cbeb).
 */
class InboxParserReadStateTest {

    @Test
    fun channelRowReadsTheFrontierOnlyFromAWellFormedPresentReadState() {
        // (case, readState JSON or null for "field omitted", expected marker)
        val cases =
            listOf(
                Triple("present", present("""{"messageId":"m-1","seq":"9007199254740993"}"""), "9007199254740993"),
                Triple("omitted", null, null),
                Triple("json null", "null", null),
                Triple("absent", """{"kind":"absent"}""", null),
                Triple("corrupt", """{"kind":"corrupt"}""", null),
                Triple("latestActivity null", present("null"), null),
                Triple("numeric seq", present("""{"messageId":"m-1","seq":12}"""), null),
                Triple("string latestActivity", present("\"m-1:12\""), null),
                Triple("array latestActivity", present("""["m-1","12"]"""), null),
                Triple("missing messageId", present("""{"seq":"12"}"""), null),
                Triple("blank messageId", present("""{"messageId":" ","seq":"12"}"""), null),
                Triple("numeric messageId", present("""{"messageId":7,"seq":"12"}"""), null),
                Triple("non-canonical seq", present("""{"messageId":"m-1","seq":"007"}"""), null),
                Triple("numeric kind", """{"kind":1,"latestActivity":{"messageId":"m-1","seq":"12"}}""", null),
                Triple("string readState", "\"present\"", null),
                Triple("array readState", "[]", null)
            )
        cases.forEach { (name, readState, expected) ->
            val parsed = parseSharedInboxSnapshot(body(channelRow("c-1", readState))) ?: error("response dropped for case=$name")
            val item = parsed.items.singleOrNull() ?: error("row dropped for case=$name items=${parsed.items}")
            assertEquals("channel:c-1", item.key, name)
            assertEquals(expected, item.readStateLatestActivitySeq, name)
        }
    }

    @Test
    fun threadRowReadsTheFrontierAndAMalformedSiblingKeepsItsRow() {
        val parsed =
            parseSharedInboxSnapshot(
                body(
                    threadRow(present("""{"messageId":"r-1","seq":"7"}""")),
                    channelRow("c-2", present("""{"messageId":"m-2","seq":5}"""))
                )
            ) ?: error("inbox payload did not parse")

        assertEquals(listOf("7", null), parsed.items.map { it.readStateLatestActivitySeq })
        assertEquals(2, parsed.items.size)
    }

    @Test
    fun rowsKeepTheirWireChannelTypeThatTheKindFolds() {
        // channelType / parentChannelType: "channel" | "private" | "joint" | "dm" (slock web
        // inboxStore.ts @ 7a5a03a5a). channelKind folds private into Channel; the raw type keeps it.
        val parsed =
            parseSharedInboxSnapshot(
                body(
                    channelRow("c-1", null).replace("\"channelType\":\"channel\"", "\"channelType\":\"private\""),
                    threadRow(null).replace("\"parentChannelType\":\"channel\"", "\"parentChannelType\":\"joint\""),
                )
            ) ?: error("inbox payload did not parse")
        val channel = parsed.items.single { it.key == "channel:c-1" }
        assertEquals("private", channel.channelType)
        assertEquals(build.raft.app.model.ConversationKind.Channel, channel.channelKind)
        val thread = parsed.items.single { it.key == "thread:t-1" }
        assertEquals("joint", thread.parentChannelType)
        assertEquals(build.raft.app.model.ConversationKind.Joint, thread.parentChannelKind)
    }

    private fun present(latestActivity: String): String =
        """{"kind":"present","readStateVersion":3,"maxReadSeq":"41","latestActivity":$latestActivity}"""

    private fun readStateField(readState: String?): String = readState?.let { ""","readState":$it""" }.orEmpty()

    private fun channelRow(channelId: String, readState: String?): String =
        """
        {"kind":"channel","channelId":"$channelId","channelName":"general","channelType":"channel",
         "lastMessageId":"m-1","latestActivitySeq":"41","doneFrontierSeq":"41","firstUnreadMessageId":null,
         "firstMentionMessageId":null,"lastMessageAt":"2026-10-20T00:00:00Z","lastMessagePreview":"hi",
         "lastMessageSenderType":"user","lastMessageSenderId":"u-2","lastMessageSenderName":"U",
         "unreadCount":0,"hasMention":false${readStateField(readState)}}
        """.trimIndent()

    private fun threadRow(readState: String?): String =
        """
        {"kind":"thread","threadChannelId":"t-1","parentMessageId":"p-1","parentChannelId":"c-1",
         "parentChannelName":"general","parentChannelType":"channel","parentMessagePreview":"p",
         "parentMessageSenderType":"user","parentMessageSenderId":"u-2","latestActivityPreview":"r",
         "latestActivitySenderType":"user","latestActivitySenderId":"u-2","latestActivitySenderName":null,
         "latestActivityMessageId":"r-1","latestActivitySeq":"7","doneFrontierSeq":"7",
         "firstUnreadMessageId":null,"firstMentionMessageId":null,"lastActivityAt":"2026-10-20T00:00:00Z",
         "lastReplyAt":null,"replyCount":1,"unreadCount":0,"hasMention":false,"taskNumber":null,
         "taskStatus":null,"taskClaimedByName":null${readStateField(readState)}}
        """.trimIndent()

    private fun body(vararg rows: String): String =
        """{"items":[${rows.joinToString(",")}],"hasMore":false,"totalCount":${rows.size},"totalUnreadCount":0}"""
}
