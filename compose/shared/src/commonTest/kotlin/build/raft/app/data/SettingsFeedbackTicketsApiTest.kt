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
        val priority: DataRequestPriority = DataRequestPriority.Foreground,
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
            calls += Call(path, method, query, headers, bodyText, priority)
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
    fun listReadsTheFirstPageOfEveryAppWithTheClientKindAndDegradedOptIn() = runTest {
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
        assertEquals(listOf("sources" to "all", "limit" to "20", "degraded_ok" to "1"), call.query)
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
    fun unreadTotalReadsTheSumOverEveryAppFromOneTicketInTheBackground() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(200, """{"tickets":[${ticketJson(unread = true, unreadCount = 3)}],"next_cursor":"c2","unread_total":4}""") }

        val result = NetworkSettingsFeedbackTicketsRepository(network, clientKind = "ios").unreadTotal()

        assertEquals(4, assertIs<FeedbackResult.Ok<Int?>>(result).value)
        val call = network.calls.single()
        assertEquals("product-feedback/tickets", call.path)
        assertEquals(listOf("sources" to "all", "limit" to "1", "degraded_ok" to "1"), call.query)
        assertEquals(mapOf("X-Raft-Client-Kind" to "ios"), call.headers)
        assertEquals(DataRequestPriority.Background, call.priority)
    }

    @Test
    fun unreadTotalIsUnknownWhenTheListIsDegradedAndAFailureWhenRefused() = runTest {
        // slock productFeedback.ts: `unread_total: null` with `degraded: true` is "not known", never 0.
        val degraded =
            FakeNetwork {
                HomeNetworkResponse(200, """{"tickets":[],"next_cursor":null,"unread_total":null,"degraded":true,"retry_after_seconds":5}""")
            }
        assertNull(assertIs<FeedbackResult.Ok<Int?>>(NetworkSettingsFeedbackTicketsRepository(degraded, "android").unreadTotal()).value)

        val refused = FakeNetwork { HomeNetworkResponse(503, """{"code":"feedback_service_unavailable"}""") }
        val failure = assertIs<FeedbackResult.Failed>(NetworkSettingsFeedbackTicketsRepository(refused, "android").unreadTotal()).failure
        assertEquals(FeedbackErrorKind.Unavailable, failure.kind)
    }

    /**
     * The merged list's first page as slock #14722 answers it (head 6a2841c296146278072c2a89455cc33d951f4167,
     * packages/server/src/routes/productFeedback.mergedList.api.test.ts "sources=all reads every
     * configured app": THREE_APPS, Hands' `client_kind` / `client_version` folded into `client`).
     */
    @Test
    fun theMergedListCarriesEachTicketsAppAndClientAndTheSummedUnreadTotal() = runTest {
        fun merged(n: Int, activity: Long, source: String, client: String): String {
            val updatedAt = 1_762_000_000_000L + activity + 1_000
            return """{"id":"${n.toString().padStart(8, '0')}-0000-4000-8000-000000000000","kind":"feedback","status":"open",
                "closure_reason":null,"duplicate_of_ticket_id":null,"message":"ticket $n","version_name":null,"channel":null,
                "created_at":${updatedAt - 1_000},"updated_at":$updatedAt,"attachment_count":0,"comment_count":0,
                "latest_comment_at":null,"unread":false,"unread_count":0,"source":"$source","client":"$client"}"""
        }
        val body =
            """{"tickets":[${merged(1, 900, "web", "desktop")},${merged(4, 800, "android", "android")},${merged(6, 700, "ios", "ios")},
                ${merged(2, 500, "web", "web")},${merged(5, 200, "android", "android")},${merged(3, 100, "web", "web")}],
                "next_cursor":null,"unread_total":3,"unavailable_sources":[]}"""
        val network = FakeNetwork { HomeNetworkResponse(200, body) }

        val result = NetworkSettingsFeedbackTicketsRepository(network, "android").listTickets(cursor = null)

        val page = assertIs<FeedbackTicketListRead.Page>(assertIs<FeedbackResult.Ok<FeedbackTicketListRead>>(result).value).page
        assertEquals(
            listOf(
                Triple("ticket 1", "web", FeedbackClient.Desktop),
                Triple("ticket 4", "android", FeedbackClient.Android),
                Triple("ticket 6", "ios", FeedbackClient.Ios),
                Triple("ticket 2", "web", FeedbackClient.Web),
                Triple("ticket 5", "android", FeedbackClient.Android),
                Triple("ticket 3", "web", FeedbackClient.Web),
            ),
            page.tickets.map { Triple(it.title, it.source, it.client) },
        )
        assertEquals(3, page.unreadTotal)
        assertNull(page.nextCursor)
    }

    @Test
    fun aCursorPageOrAnUnavailableAppHasNoTotalAndAnEmptyPageMayStillHaveMore() = runTest {
        // slock #14722: `unread_total` is null on a cursor page and while an app is unavailable
        // ("not known": keep the value held, never 0); a page may hold no tickets and a next cursor.
        val network =
            FakeNetwork { HomeNetworkResponse(200, """{"tickets":[],"next_cursor":"c3","unread_total":null,"unavailable_sources":["ios"]}""") }
        val repository = NetworkSettingsFeedbackTicketsRepository(network, "ios")

        val read = assertIs<FeedbackResult.Ok<FeedbackTicketListRead>>(repository.listTickets(cursor = "c2")).value
        val page = assertIs<FeedbackTicketListRead.Page>(read).page
        assertTrue(page.tickets.isEmpty())
        assertEquals("c3", page.nextCursor)
        assertNull(page.unreadTotal)
        // The dot's read: unknown, so the dot keeps what it shows.
        assertNull(assertIs<FeedbackResult.Ok<Int?>>(repository.unreadTotal()).value)
    }

    @Test
    fun theSingleAppAnswerWithoutSourceOrClientStillReads() = runTest {
        // A server before #14722 ignores `sources` and answers the caller's own app: no source, no client.
        val network = FakeNetwork { HomeNetworkResponse(200, """{"tickets":[${ticketJson()}],"next_cursor":null,"unread_total":0}""") }
        val read = assertIs<FeedbackResult.Ok<FeedbackTicketListRead>>(NetworkSettingsFeedbackTicketsRepository(network, "android").listTickets(cursor = null)).value
        val page = assertIs<FeedbackTicketListRead.Page>(read).page
        val ticket = page.tickets.single()
        assertNull(ticket.source)
        assertNull(ticket.client)
        assertEquals(0, page.unreadTotal)
    }

    @Test
    fun theDetailsTicketCarriesItsAppAndClient() = runTest {
        // slock #14722 test "the detail's ticket gains source and client": `{source: "web", client: "desktop"}`.
        val withSource = ticketJson().trimEnd().removeSuffix("}") + ""","source":"web","client":"desktop"}"""
        val network =
            FakeNetwork { HomeNetworkResponse(200, """{"ticket":$withSource,"comments":[],"attachments":[],"next_comment_cursor":null,"unread_total":0}""") }
        val result = NetworkSettingsFeedbackTicketsRepository(network, "android").ticket(ticketId, source = "web")
        val detail = assertIs<FeedbackResult.Ok<FeedbackTicketDetail>>(result).value
        assertEquals("web", detail.ticket.source)
        assertEquals(FeedbackClient.Desktop, detail.ticket.client)
    }

    @Test
    fun aTicketOfAnotherAppIsAskedForWithItsPlatformOnEveryCall() = runTest {
        // slock productFeedback.ts `ticketFeedbackSource`: `?platform=` names the ticket's app on the
        // detail, comment, close and attachment routes.
        val network =
            FakeNetwork { path ->
                when {
                    path.endsWith("/comments") -> HomeNetworkResponse(201, """{"id":"c-new","author_type":"reporter","body":"Thanks","created_at":4000}""")
                    path.endsWith("/close") -> HomeNetworkResponse(200, """{"id":"$ticketId","status":"closed"}""")
                    else -> HomeNetworkResponse(200, """{"ticket":${ticketJson()},"comments":[],"attachments":[],"next_comment_cursor":null,"unread_total":0}""")
                }
            }
        val repository = NetworkSettingsFeedbackTicketsRepository(network, "android")

        repository.ticket(ticketId, source = "web")
        repository.reply(ticketId, "Thanks", "sub-1", source = "ios")
        repository.close(ticketId, FeedbackReporterCloseReason.Completed, source = "ohos")
        repository.attachment(ticketId, attachmentId, source = "web")

        assertEquals(
            listOf(
                listOf("comment_limit" to "100", "platform" to "web"),
                listOf("platform" to "ios"),
                listOf("platform" to "ohos"),
            ),
            network.calls.map { it.query },
        )
        assertEquals("product-feedback/tickets/$ticketId/attachments/$attachmentId?platform=web", network.byteReads.single().first)
        // The client kind header stays: the server still knows which app is asking.
        assertTrue(network.calls.all { it.headers == mapOf("X-Raft-Client-Kind" to "android") })
    }

    @Test
    fun aTicketOfThisAppOrOfNoNamedAppSendsNoPlatform() = runTest {
        val network =
            FakeNetwork { HomeNetworkResponse(200, """{"ticket":${ticketJson()},"comments":[],"attachments":[],"next_comment_cursor":null,"unread_total":0}""") }
        val repository = NetworkSettingsFeedbackTicketsRepository(network, "ios")
        repository.ticket(ticketId, source = "ios")
        repository.ticket(ticketId, source = null)
        // Not a Hands app the server knows: not sent (the server would answer 400).
        repository.ticket(ticketId, source = "desktop")
        repository.attachment(ticketId, attachmentId, source = "ios")
        assertTrue(network.calls.all { it.query == listOf("comment_limit" to "100") })
        assertEquals("product-feedback/tickets/$ticketId/attachments/$attachmentId", network.byteReads.single().first)

        // The desktop app files into the web app: a web ticket is its own, an Android one is not.
        assertNull(feedbackTicketPlatform("web", ""))
        assertNull(feedbackTicketPlatform("web", "web"))
        assertEquals("android", feedbackTicketPlatform("android", "web"))
    }

    @Test
    fun onlyATicketFiledOnAnotherClientNamesIt() {
        val ticket = FeedbackTicket(ticketId, FeedbackTicketKind.Feedback, FeedbackTicketStatus.Open, null, "m", 1, 1, false, 0, 0, 0)
        val android = feedbackOwnClient("android")
        assertEquals(FeedbackClient.Android, android)
        assertEquals(FeedbackClient.Ios, feedbackOwnClient("ios"))
        assertNull(feedbackForeignClient(ticket.copy(source = "android", client = FeedbackClient.Android), android), "own client: nothing")
        assertNull(feedbackForeignClient(ticket, android), "no client named: nothing")
        assertNull(feedbackForeignClient(null, android))
        assertEquals(FeedbackClient.Ios, feedbackForeignClient(ticket.copy(source = "ios", client = FeedbackClient.Ios), android))
        assertEquals(FeedbackClient.Web, feedbackForeignClient(ticket.copy(source = "web", client = FeedbackClient.Web), android))
        // Raft Desktop files into the web app: still another client.
        assertEquals(FeedbackClient.Desktop, feedbackForeignClient(ticket.copy(source = "web", client = FeedbackClient.Desktop), android))
        assertEquals(FeedbackClient.Android, feedbackForeignClient(ticket.copy(source = "android", client = FeedbackClient.Android), feedbackOwnClient("ios")))
        assertNull(FeedbackClient.fromWire("windows"))
    }

    @Test
    fun aLaterPageSendsItsCursorAndNoDegradedOptIn() = runTest {
        val network = FakeNetwork { HomeNetworkResponse(200, """{"tickets":[],"next_cursor":null,"unread_total":0}""") }
        NetworkSettingsFeedbackTicketsRepository(network, clientKind = "ios").listTickets(cursor = "c2")
        assertEquals(listOf("sources" to "all", "limit" to "20", "cursor" to "c2"), network.calls.single().query)
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
