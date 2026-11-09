package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The App's client for the Raft server's `/api/product-feedback` ticket routes: the requests the
 * Settings › Feedback web page makes (createHandsFeedbackTransport.ts), with the App's client kind,
 * and the web page's reading of the answers and errors.
 */
class SettingsFeedbackTicketsApiTest {
    private val ticketId = "11111111-2222-4333-8444-555555555555"
    private val attachmentId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    private data class Call(
        val path: String,
        val method: String,
        val query: List<Pair<String, String>>,
        val headers: Map<String, String>,
        val body: String?,
    )

    private class FakeNetwork(private val answer: (String) -> HomeNetworkResponse) : RaftNetworkClient {
        val calls = mutableListOf<Call>()
        val byteReads = mutableListOf<Pair<String, Map<String, String>>>()
        var bytesAnswer: HomeNetworkBytesResponse = HomeNetworkBytesResponse(200, byteArrayOf(1, 2, 3), "image/png")

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse = error("the feedback client always sends its client kind: $path")

        override suspend fun requestWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            calls += Call(path, method, query, headers, bodyText)
            return answer(path)
        }

        override suspend fun requestBytesWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            serverIdOverride: ServerId?,
            maxBytes: Int,
        ): HomeNetworkBytesResponse {
            byteReads += path to headers
            return bytesAnswer
        }
    }

    private fun ticketJson(
        id: String = ticketId,
        status: String = "open",
        unread: Boolean = false,
        unreadCount: Int = 0,
        latest: String = "",
    ) = """{"id":"$id","kind":"bug","status":"$status","closure_reason":null,"duplicate_of_ticket_id":null,
        "message":"Crash on launch\nSecond line","version_name":"1.22.0","channel":"main","created_at":1000,"updated_at":2000,
        "attachment_count":1,"comment_count":2,"latest_comment_at":1500,"unread":$unread,"unread_count":$unreadCount$latest}"""

    @Test
    fun listReadsTheFirstPageWithTheClientKindAndDegradedOptIn() = runTest {
        val network =
            FakeNetwork {
                HomeNetworkResponse(
                    200,
                    """{"tickets":[${ticketJson(unread = true, unreadCount = 2, latest = ""","latest_comment":{"author_type":"staff","body_preview":"**Fixed** in 1.23","created_at":1500}""")}],
                        "next_cursor":"c2","unread_total":2}""",
                )
            }
        val repository = NetworkSettingsFeedbackTicketsRepository(network, clientKind = "android")

        val result = repository.listTickets(cursor = null)

        val call = network.calls.single()
        assertEquals("product-feedback/tickets", call.path)
        assertEquals("GET", call.method)
        assertEquals(listOf("limit" to "20", "degraded_ok" to "1"), call.query)
        assertEquals(mapOf("X-Raft-Client-Kind" to "android"), call.headers)
        val page = assertIs<FeedbackTicketListRead.Page>(assertIs<FeedbackResult.Ok<FeedbackTicketListRead>>(result).value).page
        assertEquals("c2", page.nextCursor)
        assertEquals(2, page.unreadTotal)
        val ticket = page.tickets.single()
        assertEquals(FeedbackTicketKind.Bug, ticket.kind)
        assertEquals(FeedbackTicketStatus.Open, ticket.status)
        assertEquals("Crash on launch", ticket.title)
        assertEquals(2, ticket.newCount)
        assertEquals(1500L, ticket.activityAt)
        assertEquals(FeedbackLatestComment(FeedbackAuthorType.Staff, "**Fixed** in 1.23", 1500), ticket.latestComment)
    }

    @Test
    fun aLaterPageSendsItsCursorAndNoDegradedOptIn() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(200, """{"tickets":[],"next_cursor":null,"unread_total":0}""") }
        NetworkSettingsFeedbackTicketsRepository(network, clientKind = "ios").listTickets(cursor = "c2")
        assertEquals(listOf("limit" to "20", "cursor" to "c2"), network.calls.single().query)
        assertEquals(mapOf("X-Raft-Client-Kind" to "ios"), network.calls.single().headers)
    }

    @Test
    fun aDegradedFirstPageIsReadAsRetryLaterNotAsNoFeedback() = runTest {
        val network =
            FakeNetwork {
                HomeNetworkResponse(200, """{"tickets":[],"next_cursor":null,"unread_total":null,"degraded":true,"retry_after_seconds":5}""")
            }
        val result = NetworkSettingsFeedbackTicketsRepository(network, "android").listTickets(cursor = null)
        assertEquals(FeedbackResult.Ok(FeedbackTicketListRead.Degraded(5)), result)
    }

    @Test
    fun detailReadsCommentsInOrderAndTheTicketsAttachments() = runTest {
        val network =
            FakeNetwork {
                HomeNetworkResponse(
                    200,
                    """{"ticket":${ticketJson(status = "resolved")},
                        "comments":[{"id":"c-b","author_type":"staff","body":"Fixed","created_at":3000},
                                    {"id":"c-a","author_type":"reporter","body":"Still?","created_at":2000},
                                    {"id":"c-x","author_type":"bot","body":"unknown author","created_at":2500}],
                        "attachments":[{"id":"$attachmentId","filename":"shot.png","content_type":"image/png","size_bytes":2048,"created_at":1000}],
                        "next_comment_cursor":"n1","unread_total":0}""",
                )
            }
        val result = NetworkSettingsFeedbackTicketsRepository(network, "android").ticket(ticketId)

        val call = network.calls.single()
        assertEquals("product-feedback/tickets/$ticketId", call.path)
        assertEquals(listOf("comment_limit" to "100"), call.query)
        val detail = assertIs<FeedbackResult.Ok<FeedbackTicketDetail>>(result).value
        assertEquals(FeedbackTicketStatus.Resolved, detail.ticket.status)
        // Oldest first; a comment from an author the App does not know is left out.
        assertEquals(listOf("c-a", "c-b"), detail.comments.map { it.id })
        assertEquals("n1", detail.nextCommentCursor)
        val attachment = detail.attachments.single()
        assertTrue(attachment.isImage)
        assertEquals("shot.png", attachment.filename)
        assertEquals(2048L, attachment.sizeBytes)
    }

    @Test
    fun aLaterCommentPageSendsTheCommentCursor() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(200, """{"ticket":${ticketJson()},"comments":[],"attachments":[],"next_comment_cursor":null,"unread_total":0}""") }
        NetworkSettingsFeedbackTicketsRepository(network, "android").ticket(ticketId, commentCursor = "n1")
        assertEquals(listOf("comment_limit" to "100", "comment_cursor" to "n1"), network.calls.single().query)
    }

    @Test
    fun replyPostsTheTrimmedBodyAndSubmissionIdAsJson() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(201, """{"id":"c-new","author_type":"reporter","body":"Thanks","created_at":4000}""") }
        val result = NetworkSettingsFeedbackTicketsRepository(network, "android").reply(ticketId, "  Thanks \n", "sub-1")

        val call = network.calls.single()
        assertEquals("product-feedback/tickets/$ticketId/comments", call.path)
        assertEquals("POST", call.method)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("Thanks", body["body"]!!.jsonPrimitive.content)
        assertEquals("sub-1", body["submission_id"]!!.jsonPrimitive.content)
        assertEquals(FeedbackResult.Ok(FeedbackComment("c-new", FeedbackAuthorType.Reporter, "Thanks", 4000)), result)
    }

    @Test
    fun aBlankOrOverlongReplyIsRefusedWithoutARequest() = runTest {
        val network = FakeNetwork { error("no request") }
        val repository = NetworkSettingsFeedbackTicketsRepository(network, "android")
        assertEquals(FeedbackErrorKind.Invalid, (repository.reply(ticketId, "   ", "s") as FeedbackResult.Failed).failure.kind)
        assertEquals(FeedbackErrorKind.Invalid, (repository.reply(ticketId, "x".repeat(FEEDBACK_REPLY_MAX_CHARS + 1), "s") as FeedbackResult.Failed).failure.kind)
        assertTrue(network.calls.isEmpty())
    }

    @Test
    fun closePostsTheReporterReason() = runTest {
        val network =
            FakeNetwork {
                HomeNetworkResponse(200, """{"id":"$ticketId","status":"closed","closure_reason":"completed","duplicate_of_ticket_id":null,"updated_at":5000,"changed":true}""")
            }
        val result = NetworkSettingsFeedbackTicketsRepository(network, "android").close(ticketId, FeedbackReporterCloseReason.Completed)
        val call = network.calls.single()
        assertEquals("product-feedback/tickets/$ticketId/close", call.path)
        assertEquals("POST", call.method)
        assertEquals("completed", Json.parseToJsonElement(call.body!!).jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(FeedbackResult.Ok(Unit), result)
    }

    @Test
    fun attachmentBytesAreReadWithTheClientKind() = runTest {
        val network = FakeNetwork { error("no json request") }
        val result = NetworkSettingsFeedbackTicketsRepository(network, "ios").attachment(ticketId, attachmentId)
        assertEquals(listOf("product-feedback/tickets/$ticketId/attachments/$attachmentId" to mapOf("X-Raft-Client-Kind" to "ios")), network.byteReads)
        val bytes = assertIs<FeedbackResult.Ok<FeedbackAttachmentBytes>>(result).value
        assertContentEquals(byteArrayOf(1, 2, 3), bytes.bytes)
        assertEquals("image/png", bytes.contentType)
    }

    @Test
    fun idsThatAreNotUuidsAreNotFoundWithoutARequest() = runTest {
        val network = FakeNetwork { error("no request") }
        val repository = NetworkSettingsFeedbackTicketsRepository(network, "android")
        assertEquals(FeedbackErrorKind.NotFound, (repository.ticket("../tickets") as FeedbackResult.Failed).failure.kind)
        assertEquals(FeedbackErrorKind.NotFound, (repository.attachment(ticketId, "x/y") as FeedbackResult.Failed).failure.kind)
        assertTrue(network.calls.isEmpty() && network.byteReads.isEmpty())
    }

    @Test
    fun failuresReadAsTheWebPageReadsThem() {
        fun kind(status: Int, body: String? = null) = feedbackFailureOf(status, body).kind
        assertEquals(FeedbackErrorKind.Conflict, kind(409, """{"code":"feedback_comment_conflict"}"""))
        assertEquals(FeedbackErrorKind.NotFound, kind(404, """{"error":"Feedback ticket not found","code":"feedback_not_found"}"""))
        assertEquals(FeedbackErrorKind.RateLimited, kind(429, """{"code":"feedback_rate_limited"}"""))
        assertEquals(FeedbackErrorKind.RateLimited, kind(429, """{"code":"RATE_LIMITED"}"""))
        assertEquals(FeedbackErrorKind.Invalid, kind(400, """{"code":"feedback_invalid"}"""))
        assertEquals(FeedbackErrorKind.Unauthorized, kind(401))
        assertEquals(FeedbackErrorKind.Unauthorized, kind(403, """{"code":"forbidden"}"""))
        assertEquals(FeedbackErrorKind.Unavailable, kind(503, """{"code":"feedback_integration_unavailable"}"""))
        assertEquals(FeedbackErrorKind.Unavailable, kind(0))
        // The route itself missing (an older server): a 404 that names no feedback code.
        assertEquals(FeedbackErrorKind.NotSupported, kind(404, "<html>Not Found</html>"))
        assertEquals(FeedbackErrorKind.NotSupported, kind(501))
        assertEquals("feedback_integration_unavailable", feedbackFailureOf(503, """{"code":"feedback_integration_unavailable"}""").code)
    }

    @Test
    fun aSuccessThatIsNotTheContractIsUnavailable() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(200, """{"unexpected":true}""") }
        val result = NetworkSettingsFeedbackTicketsRepository(network, "android").listTickets(cursor = null)
        assertEquals(FeedbackErrorKind.Unavailable, (result as FeedbackResult.Failed).failure.kind)
    }

    @Test
    fun theListPreviewIsOnePlainLine() {
        assertEquals(
            "Fixed in 1.23 see notes",
            feedbackPreviewLine("**Fixed** in `1.23`\n\n```\ncode\n```\nsee [notes](https://x.test) ![s](https://x.test/a.png)"),
        )
    }

    @Test
    fun closedAsCompletedReadsResolved() {
        val ticket =
            FeedbackTicket(ticketId, FeedbackTicketKind.Feedback, FeedbackTicketStatus.Closed, FeedbackClosureReason.Completed, "m", 1, 1, false, 0, 0, 0)
        assertEquals(FeedbackTicketStatus.Resolved, ticket.displayStatus)
        assertEquals(FeedbackTicketStatus.Closed, ticket.copy(closureReason = FeedbackClosureReason.NoLongerNeeded).displayStatus)
        assertNull(ticket.latestComment)
    }
}
