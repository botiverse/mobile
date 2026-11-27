package build.raft.app.data

import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A quote reply's `resolvedQuotes` as botiverse/slock sends it (staging
 * b0b65a4d1263c9bd39adf28a2f2553c5bacb1913: `services/quoteReplyService.ts`
 * `resolveInlineQuotesMapForViewer`, the shapes `routes/quoteReply.api.test.ts` asserts): on the
 * rows of the message reads and of the sender's own send, and from
 * `GET /messages/:messageId/resolved-quotes` for a copy that came without them.
 */
class ResolvedQuotesTest {
    private val quotedId = "54a733de-0001-4000-8000-000000000001"
    private val replyId = "91c2b8aa-2222-4222-8222-222222222222"
    private val content = "> keep quote replies in the same conversation\n> msg=54a733de\n\nAgreed on point 1."
    private val quoteEntry =
        """{"messageId":"$quotedId","shortId":"54a733de","author":{"name":"Alice","type":"user"},""" +
            """"createdAt":"2026-11-22T08:00:00.000Z","isPartial":true}"""

    private fun row(id: String, resolvedQuotes: String? = null): String =
        """{"id":"$id","seq":7,"channelId":"channel-1","senderType":"user","senderId":"u-bob","senderName":"Bob",""" +
            """"messageType":"chat","content":"> keep quote replies in the same conversation\n> msg=54a733de\n\nAgreed on point 1.",""" +
            """"createdAt":"2026-11-22T08:01:00.000Z"""" +
            (resolvedQuotes?.let { ""","resolvedQuotes":$it""" } ?: "") +
            "}"

    @Test
    fun aPageRowCarriesItsQuotesAndARowWithoutThemResolvedNone() {
        val quotedRow = row(replyId, """{"0":$quoteEntry,"x":{"messageId":"$quotedId"},"1":{"shortId":"deadbeef"}}""")
        val page =
            parseSharedChannelMessages(
                """{"messages":[$quotedRow,${row("m-degraded")}]}""",
                fallbackThreadId = ThreadId("channel-1"),
                diagnostics = {},
            )
        val quotes = assertNotNull(page.messages[0].resolvedQuotes)
        // An entry that doesn't read (no block index, no quoted message) drops alone, never the row.
        assertEquals(setOf("0"), quotes.keys)
        val quote = quotes.getValue("0")
        assertEquals(quotedId, quote.messageId)
        assertEquals("54a733de", quote.shortId)
        assertEquals("Alice", quote.authorName)
        assertEquals("user", quote.authorType)
        assertEquals("2026-11-22T08:00:00.000Z", quote.createdAt)
        assertEquals(true, quote.isPartial)
        // The read attaches them whenever one resolved: without them, none did.
        assertEquals(0, page.messages[1].resolvedQuotes?.size)
        // The sync log's copy never carries them: unknown, read on their own.
        val synced = assertNotNull(parseSharedMessageRow(Json.parseToJsonElement(row(replyId)).jsonObject, diagnostics = {}))
        assertNull(synced.resolvedQuotes)
    }

    @Test
    fun aThreadPageRowKeepsItsQuotesOnTheRenderedMessage() {
        val wire = Json { ignoreUnknownKeys = true }.decodeFromString(WireMessage.serializer(), row(replyId, """{"0":$quoteEntry}"""))
        val rendered =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = RaftThreadScreenPayload(instanceId = "thread-channel-1", sourceThreadId = "channel-1", status = "loading"),
                page = ThreadMessagesPage(messages = listOf(wire), canLoadMoreBefore = false, canLoadMoreAfter = false),
                session = null,
            )
        assertEquals(quotedId, rendered.messages.single().resolvedQuotes?.get("0")?.messageId)
    }

    private class FakeApi(private val answer: HomeNetworkResponse) : RaftResolvedQuotesApi {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun resolvedQuotes(serverId: String, messageId: String): HomeNetworkResponse {
            calls += serverId to messageId
            return answer
        }
    }

    @Test
    fun aLiveQuoteReplyReadsItsQuotesAndOneTheViewerCannotSeeStaysPlain() =
        runTest {
            val api = FakeApi(HomeNetworkResponse(200, """{"resolvedQuotes":{"0":$quoteEntry}}"""))
            val store = SharedResolvedQuotesStore(api = api, scope = backgroundScope)
            store.request("server-1", replyId, content)
            runCurrent()
            assertEquals(listOf("server-1" to replyId), api.calls)
            assertEquals(quotedId, store.quotesFor("server-1", replyId, content)?.get("0")?.messageId)

            // 404: not a message this viewer can see; its quotes stay plain blockquotes.
            val hidden = SharedResolvedQuotesStore(api = FakeApi(HomeNetworkResponse(404, """{"error":"Message not found"}""")), scope = backgroundScope)
            hidden.request("server-1", replyId, content)
            runCurrent()
            assertEquals(0, hidden.quotesFor("server-1", replyId, content)?.size)
        }
}
