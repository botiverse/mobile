package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The signed-in person's own Hands feedback tickets, read and answered through the Raft server's
 * proxy (slock packages/server/src/routes/productFeedback.ts, mounted at `/api/product-feedback`
 * behind requireAuth + requireVerified). The same routes the Settings › Feedback web page calls
 * (packages/web/src/feedback/createHandsFeedbackTransport.ts): the App's own access token as
 * Bearer, and `X-Raft-Client-Kind` (`android` / `ios`) so the server reads the Hands app the App
 * files into (productFeedbackApps.ts; no header reads the web app).
 *
 * - GET  tickets?sources=all&limit=&cursor=|degraded_ok=1  one list across every app (first page may
 *                                                     answer `degraded`)
 * - GET  tickets/:id?comment_limit=&comment_cursor= detail: ticket, comments, attachments
 * - POST tickets/:id/comments {body, submission_id} reply (JSON; the route's multer skips it)
 * - POST tickets/:id/close {reason}                 close: completed | no_longer_needed
 * - GET  tickets/:id/attachments/:attachmentId      attachment bytes
 *
 * One list across apps (slock #14722, `docs/development/feedback-native-entry.md` "One list across
 * apps", `packages/server/src/services/productFeedbackMergedList.ts`): `sources=all` lists the
 * person's tickets from every Hands app (web, which Raft Desktop shares, android, ios, ohos) in one
 * order. Each ticket carries `source` (the app it lives in) and `client` (the client that filed it,
 * a label: web | desktop | android | ios | ohos); the detail's `ticket` carries both too. A ticket of
 * another app is read, answered, closed and its files opened with `?platform=<source>`
 * (`ticketFeedbackSource`). `unread_total` is the sum over the apps on the first page and null on a
 * cursor page or while an app is unavailable ("not known": keep the value held). A page may hold
 * fewer tickets than `limit`, even none, and still have a `next_cursor`: the list ends only at
 * `next_cursor: null`. `unavailable_sources` is not read (the design: list the others, say nothing).
 */

/** First page size the web page asks for (FeedbackInbox `pageSize`). */
const val FEEDBACK_TICKETS_PAGE_SIZE: Int = 20

/** Comments per detail read (the web page's `commentLimit`; the server's max). */
const val FEEDBACK_TICKET_COMMENT_LIMIT: Int = 100

/** Server cap on a reply (productFeedbackConversationService `commentOnProductFeedbackTicket`). */
const val FEEDBACK_REPLY_MAX_CHARS: Int = 10_000

/** Server cap on one attachment (`PRODUCT_FEEDBACK_MAX_ATTACHMENT_BYTES`), with room for headers. */
const val FEEDBACK_ATTACHMENT_MAX_BYTES: Int = 10 * 1024 * 1024 + 64 * 1024

internal const val FEEDBACK_CLIENT_KIND_HEADER = "X-Raft-Client-Kind"

/** The Hands apps a ticket can live in (slock productFeedbackApps.ts `PRODUCT_FEEDBACK_SOURCES`). */
internal val FeedbackTicketSources: Set<String> = setOf("web", "ios", "android", "ohos")

/** This app's own Hands app: its client kind, or the web's when it sends none (desktop files as web). */
internal fun feedbackOwnSource(clientKind: String): String = clientKind.trim().ifEmpty { "web" }

/**
 * The `?platform=` a call about a ticket of [source] needs from an app whose client kind is
 * [clientKind]: none for its own app (or an unknown one), else the ticket's app.
 */
internal fun feedbackTicketPlatform(source: String?, clientKind: String): String? =
    source?.trim()?.takeIf { it in FeedbackTicketSources && it != feedbackOwnSource(clientKind) }

/**
 * The client a ticket was filed from (slock productFeedbackMergedList.ts `PRODUCT_FEEDBACK_CLIENTS`):
 * a label only. Raft Desktop files into the web app, so it is [Desktop] with source `web`.
 */
enum class FeedbackClient(val wire: String) {
    Web("web"),
    Desktop("desktop"),
    Android("android"),
    Ios("ios"),
    Ohos("ohos");

    companion object {
        fun fromWire(value: String?): FeedbackClient? = entries.firstOrNull { it.wire == value?.trim() }
    }
}

/**
 * The client a ticket filed in the app with client kind [clientKind] has: `android` / `ios` (and
 * `ohos`) name themselves; the desktop app files into the web app with no desktop version, so `web`.
 */
fun feedbackOwnClient(clientKind: String): FeedbackClient? = FeedbackClient.fromWire(feedbackOwnSource(clientKind))

/**
 * The client to name on a ticket's first message (design settings.md "Feedback", option F): the
 * ticket's [FeedbackTicket.client] when it is not the app the person is in ([ownClient]); null
 * (nothing shown) for a ticket filed here, or when the server named no client.
 */
fun feedbackForeignClient(ticket: FeedbackTicket?, ownClient: FeedbackClient?): FeedbackClient? =
    ticket?.client?.takeIf { it != ownClient }

enum class FeedbackTicketKind { Feedback, Bug }

enum class FeedbackTicketStatus(val wire: String) {
    Open("open"),
    InProgress("in_progress"),
    Resolved("resolved"),
    Closed("closed");

    /** Not yet ended: the web page's "Active" filter. */
    val isActive: Boolean get() = this == Open || this == InProgress

    companion object {
        fun fromWire(value: String?): FeedbackTicketStatus? = entries.firstOrNull { it.wire == value }
    }
}

enum class FeedbackClosureReason(val wire: String) {
    Completed("completed"),
    NoLongerNeeded("no_longer_needed"),
    NotPlanned("not_planned"),
    CannotReproduce("cannot_reproduce"),
    Duplicate("duplicate");

    companion object {
        fun fromWire(value: String?): FeedbackClosureReason? = entries.firstOrNull { it.wire == value }
    }
}

/** The two reasons a reporter may close with (the close route accepts only these). */
enum class FeedbackReporterCloseReason(val wire: String) {
    Completed("completed"),
    NoLongerNeeded("no_longer_needed"),
}

enum class FeedbackAuthorType(val wire: String) {
    Reporter("reporter"),
    Staff("staff"),
    System("system");

    companion object {
        fun fromWire(value: String?): FeedbackAuthorType? = entries.firstOrNull { it.wire == value }
    }
}

data class FeedbackLatestComment(
    val authorType: FeedbackAuthorType,
    val bodyPreview: String,
    val createdAt: Long,
)

data class FeedbackTicket(
    val id: String,
    val kind: FeedbackTicketKind,
    val status: FeedbackTicketStatus,
    val closureReason: FeedbackClosureReason?,
    val message: String,
    val createdAt: Long,
    val updatedAt: Long,
    val unread: Boolean,
    val unreadCount: Int,
    val attachmentCount: Int,
    val commentCount: Int,
    /** List pages only; null on a detail read or with no comment yet. */
    val latestComment: FeedbackLatestComment? = null,
    /** The Hands app it lives in (`web` / `android` / `ios` / `ohos`); null: the server named none (this app's). */
    val source: String? = null,
    /** The client that filed it; null: the server named none. */
    val client: FeedbackClient? = null,
) {
    /** The first line, as the web list and ticket header show it. */
    val title: String get() = message.lineSequence().firstOrNull()?.trim().orEmpty()

    /** The status the chip shows: closed as completed reads as resolved (web FeedbackStatusChip). */
    val displayStatus: FeedbackTicketStatus
        get() = if (status == FeedbackTicketStatus.Closed && closureReason == FeedbackClosureReason.Completed) {
            FeedbackTicketStatus.Resolved
        } else {
            status
        }

    /** The list's activity time: the latest comment, else when it was sent (web FeedbackTicketRow). */
    val activityAt: Long get() = latestComment?.createdAt ?: createdAt

    /** The "N new" count: at least 1 while unread. */
    val newCount: Int get() = if (unread) maxOf(1, unreadCount) else 0
}

data class FeedbackComment(
    val id: String,
    val authorType: FeedbackAuthorType,
    val body: String,
    val createdAt: Long,
)

data class FeedbackAttachment(
    val id: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Long,
) {
    val isImage: Boolean get() = contentType.lowercase().startsWith("image/")
}

data class FeedbackTicketPage(
    val tickets: List<FeedbackTicket>,
    val nextCursor: String?,
    /** The sum over the apps on a first page; null ("not known now") on a cursor page or with an app unavailable. */
    val unreadTotal: Int?,
)

/** A list read: a page, or the server's "can't read right now" answer to retry after a while. */
sealed interface FeedbackTicketListRead {
    data class Page(val page: FeedbackTicketPage) : FeedbackTicketListRead

    data class Degraded(val retryAfterSeconds: Int) : FeedbackTicketListRead
}

data class FeedbackTicketDetail(
    val ticket: FeedbackTicket,
    val comments: List<FeedbackComment>,
    val attachments: List<FeedbackAttachment>,
    val nextCommentCursor: String?,
    val unreadTotal: Int,
)

data class FeedbackAttachmentBytes(val bytes: ByteArray, val contentType: String?)

/** Web `FeedbackTransportErrorCode`, plus [NotSupported]: this server has no ticket routes. */
enum class FeedbackErrorKind { Conflict, Invalid, NotFound, RateLimited, Unauthorized, Unavailable, NotSupported }

data class FeedbackFailure(
    val kind: FeedbackErrorKind,
    /** The server's `code` (`feedback_integration_unavailable`), or a local one; for logs. */
    val code: String,
    val httpStatus: Int = 0,
    /** A 429's `Retry-After` (delta-seconds), when it named one. */
    val retryAfterSeconds: Long? = null,
    /** The failed response's server trace id (as [ScreenError.traceId]): shown under the error. */
    val traceId: String? = build.raft.app.network.RaftFailedRequestTraceIds.recent(),
)

sealed interface FeedbackResult<out T> {
    data class Ok<T>(val value: T) : FeedbackResult<T>

    data class Failed(val failure: FeedbackFailure) : FeedbackResult<Nothing>
}

interface SettingsFeedbackTicketsRepository {
    suspend fun listTickets(cursor: String?, limit: Int = FEEDBACK_TICKETS_PAGE_SIZE): FeedbackResult<FeedbackTicketListRead>

    /** [source]: the ticket's app ([FeedbackTicket.source]); another app's is asked with `?platform=`. */
    suspend fun ticket(
        ticketId: String,
        commentCursor: String? = null,
        commentLimit: Int = FEEDBACK_TICKET_COMMENT_LIMIT,
        source: String? = null,
    ): FeedbackResult<FeedbackTicketDetail>

    /** Safe to retry with the same [submissionId]: Hands dedupes on it. */
    suspend fun reply(ticketId: String, body: String, submissionId: String, source: String? = null): FeedbackResult<FeedbackComment>

    suspend fun close(ticketId: String, reason: FeedbackReporterCloseReason, source: String? = null): FeedbackResult<Unit>

    suspend fun attachment(ticketId: String, attachmentId: String, source: String? = null): FeedbackResult<FeedbackAttachmentBytes>
}

class NetworkSettingsFeedbackTicketsRepository(
    private val networkClient: RaftNetworkClient,
    /** `android` / `ios` (raftFeedbackWebViewClientKind); blank reads the web app. */
    private val clientKind: String,
    private val logger: (String) -> Unit = {},
) : SettingsFeedbackTicketsRepository {
    private val headers: Map<String, String>
        get() = clientKind.trim().takeIf { it.isNotEmpty() }?.let { mapOf(FEEDBACK_CLIENT_KIND_HEADER to it) }.orEmpty()

    override suspend fun listTickets(cursor: String?, limit: Int): FeedbackResult<FeedbackTicketListRead> {
        val query =
            buildList {
                // One list across every app the person files into (slock #14722).
                add("sources" to "all")
                add("limit" to limit.toString())
                // Only the first page may degrade (server #12566); a later page keeps its 503.
                if (cursor != null) add("cursor" to cursor) else add("degraded_ok" to "1")
            }
        return call("list") {
            networkClient.requestWithHeaders(DataRequestPriority.Foreground, "$FEEDBACK_ROUTE/tickets", headers, query = query)
        }.decode("list", ::parseFeedbackTicketListRead)
    }

    /**
     * The unread total across every app for the Settings / Feedback dot: the merged list's first
     * page `unread_total` with `limit=1` (slock #14722 "Read the total from the first page"). Null:
     * not known now (an app unavailable, or Hands slow or refusing: a `degraded` answer), never 0.
     */
    suspend fun unreadTotal(): FeedbackResult<Int?> =
        call("unread_total") {
            networkClient.requestWithHeaders(
                DataRequestPriority.Background,
                "$FEEDBACK_ROUTE/tickets",
                headers,
                query = listOf("sources" to "all", "limit" to "1", "degraded_ok" to "1"),
            )
        }.decode("unread_total", ::parseFeedbackTicketListRead).let { result ->
            when (result) {
                is FeedbackResult.Failed -> result
                is FeedbackResult.Ok ->
                    when (val read = result.value) {
                        is FeedbackTicketListRead.Page -> FeedbackResult.Ok(read.page.unreadTotal)
                        is FeedbackTicketListRead.Degraded -> FeedbackResult.Ok(null)
                    }
            }
        }

    /** `?platform=<source>` for a ticket of another app (slock productFeedback.ts `ticketFeedbackSource`). */
    private fun platformQuery(source: String?): List<Pair<String, String>> =
        feedbackTicketPlatform(source, clientKind)?.let { listOf("platform" to it) }.orEmpty()

    override suspend fun ticket(ticketId: String, commentCursor: String?, commentLimit: Int, source: String?): FeedbackResult<FeedbackTicketDetail> {
        val id = ticketId.feedbackPathSegmentOrNull() ?: return notFound()
        val query =
            buildList {
                add("comment_limit" to commentLimit.toString())
                commentCursor?.let { add("comment_cursor" to it) }
                addAll(platformQuery(source))
            }
        return call("detail") {
            networkClient.requestWithHeaders(DataRequestPriority.Foreground, "$FEEDBACK_ROUTE/tickets/$id", headers, query = query)
        }.decode("detail", ::parseFeedbackTicketDetail)
    }

    override suspend fun reply(ticketId: String, body: String, submissionId: String, source: String?): FeedbackResult<FeedbackComment> {
        val id = ticketId.feedbackPathSegmentOrNull() ?: return notFound()
        val text = body.trim()
        if (text.isEmpty() || text.length > FEEDBACK_REPLY_MAX_CHARS) {
            return FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.Invalid, "feedback_reply_invalid"))
        }
        val payload = feedbackJson.encodeToString(FeedbackReplyWire.serializer(), FeedbackReplyWire(text, submissionId))
        return call("reply") {
            networkClient.requestWithHeaders(
                DataRequestPriority.Foreground,
                "$FEEDBACK_ROUTE/tickets/$id/comments",
                headers,
                method = "POST",
                query = platformQuery(source),
                bodyText = payload,
            )
        }.decode("reply") { parseFeedbackComment(it, fallbackBody = text) }
    }

    override suspend fun close(ticketId: String, reason: FeedbackReporterCloseReason, source: String?): FeedbackResult<Unit> {
        val id = ticketId.feedbackPathSegmentOrNull() ?: return notFound()
        val payload = feedbackJson.encodeToString(FeedbackCloseWire.serializer(), FeedbackCloseWire(reason.wire))
        return call("close") {
            networkClient.requestWithHeaders(
                DataRequestPriority.Foreground,
                "$FEEDBACK_ROUTE/tickets/$id/close",
                headers,
                method = "POST",
                query = platformQuery(source),
                bodyText = payload,
            )
        }.decode("close") { body -> if (parseFeedbackCloseStatus(body) == "closed") Unit else null }
    }

    override suspend fun attachment(ticketId: String, attachmentId: String, source: String?): FeedbackResult<FeedbackAttachmentBytes> {
        val id = ticketId.feedbackPathSegmentOrNull() ?: return notFound()
        val attachment = attachmentId.feedbackPathSegmentOrNull() ?: return notFound()
        // The bytes read takes no query list: the platform (one of [FeedbackTicketSources]) goes on the path.
        val platform = feedbackTicketPlatform(source, clientKind)?.let { "?platform=$it" }.orEmpty()
        val response =
            try {
                networkClient.requestBytesWithHeaders(
                    DataRequestPriority.Foreground,
                    "$FEEDBACK_ROUTE/tickets/$id/attachments/$attachment$platform",
                    headers,
                    maxBytes = FEEDBACK_ATTACHMENT_MAX_BYTES,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logger("settings_feedback_tickets_attachment_exception type=${error::class.simpleName}")
                return FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.Unavailable, "feedback_transport"))
            }
        logger("settings_feedback_tickets_attachment code=${response.code}")
        val bytes = response.bytes
        return if (response.isSuccessful && bytes != null) {
            FeedbackResult.Ok(FeedbackAttachmentBytes(bytes, response.contentType))
        } else {
            FeedbackResult.Failed(feedbackFailureOf(response.code, null))
        }
    }

    private suspend fun call(operation: String, block: suspend () -> HomeNetworkResponse): HomeNetworkResponse =
        try {
            block().also { logger("settings_feedback_tickets_$operation code=${it.code}") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger("settings_feedback_tickets_${operation}_exception type=${error::class.simpleName}")
            HomeNetworkResponse(code = 0, bodyText = null)
        }

    private fun <T> HomeNetworkResponse.decode(operation: String, parse: (String) -> T?): FeedbackResult<T> {
        if (!isSuccessful) return FeedbackResult.Failed(feedbackFailureOf(code, errorBodyText ?: bodyText))
        val value = bodyText?.let { runCatching { parse(it) }.getOrNull() }
        if (value == null) {
            logger("settings_feedback_tickets_${operation}_contract_mismatch")
            return FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.Unavailable, "feedback_contract_mismatch", code))
        }
        return FeedbackResult.Ok(value)
    }

    private fun notFound(): FeedbackResult<Nothing> =
        FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.NotFound, "feedback_not_found"))
}

private const val FEEDBACK_ROUTE = "product-feedback"

/** Ticket and attachment ids are UUIDs; anything else is answered "not found" without a request. */
private val FeedbackIdRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun String.feedbackPathSegmentOrNull(): String? = trim().takeIf { FeedbackIdRegex.matches(it) }

/**
 * What a failed answer means, as the web page's `feedbackTransportError` reads it. A 404 that
 * names no feedback code (or a 405 / 501) is the route itself missing: [FeedbackErrorKind.NotSupported].
 */
internal fun feedbackFailureOf(status: Int, bodyText: String?): FeedbackFailure {
    val code = feedbackErrorCode(bodyText)
    val kind =
        when {
            code == "feedback_submission_conflict" || code == "feedback_comment_conflict" -> FeedbackErrorKind.Conflict
            code == "feedback_not_found" -> FeedbackErrorKind.NotFound
            code == "feedback_rate_limited" || status == 429 -> FeedbackErrorKind.RateLimited
            code in FeedbackInvalidCodes -> FeedbackErrorKind.Invalid
            status == 401 || status == 403 -> FeedbackErrorKind.Unauthorized
            status == 404 || status == 405 || status == 501 -> FeedbackErrorKind.NotSupported
            else -> FeedbackErrorKind.Unavailable
        }
    return FeedbackFailure(kind, code ?: if (status == 0) "feedback_transport" else "feedback_http_$status", status)
}

private val FeedbackInvalidCodes =
    setOf(
        "feedback_invalid",
        "feedback_attachment_too_large",
        "feedback_attachment_type_invalid",
        "feedback_attachment_count_invalid",
        "feedback_upload_invalid",
    )

private fun feedbackErrorCode(bodyText: String?): String? {
    val body = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val element = runCatching { feedbackJson.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
    return (element["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}

private val feedbackJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable
private data class FeedbackReplyWire(val body: String, @SerialName("submission_id") val submissionId: String)

@Serializable
private data class FeedbackCloseWire(val reason: String)

@Serializable
private data class FeedbackLatestCommentDto(
    @SerialName("author_type") val authorType: String? = null,
    @SerialName("body_preview") val bodyPreview: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
private data class FeedbackTicketDto(
    val id: String? = null,
    val kind: String? = null,
    val status: String? = null,
    @SerialName("closure_reason") val closureReason: String? = null,
    val message: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    val unread: Boolean? = null,
    @SerialName("unread_count") val unreadCount: Int? = null,
    @SerialName("attachment_count") val attachmentCount: Int? = null,
    @SerialName("comment_count") val commentCount: Int? = null,
    @SerialName("latest_comment") val latestComment: FeedbackLatestCommentDto? = null,
    /** slock productFeedbackMergedList.ts `ProductFeedbackMergedTicket.source` (merged list and detail). */
    val source: String? = null,
    /** `ProductFeedbackMergedTicket.client`: web | desktop | android | ios | ohos. */
    val client: String? = null,
)

@Serializable
private data class FeedbackCommentDto(
    val id: String? = null,
    @SerialName("author_type") val authorType: String? = null,
    val body: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
private data class FeedbackAttachmentDto(
    val id: String? = null,
    val filename: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
private data class FeedbackTicketPageDto(
    val tickets: List<FeedbackTicketDto>? = null,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("unread_total") val unreadTotal: Int? = null,
    val degraded: Boolean? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
)

@Serializable
private data class FeedbackTicketDetailDto(
    val ticket: FeedbackTicketDto? = null,
    val comments: List<FeedbackCommentDto>? = null,
    val attachments: List<FeedbackAttachmentDto>? = null,
    @SerialName("next_comment_cursor") val nextCommentCursor: String? = null,
    @SerialName("unread_total") val unreadTotal: Int? = null,
)

/** A list answer; null when it is not one. A row the App can't read is left out, not the page. */
internal fun parseFeedbackTicketListRead(body: String): FeedbackTicketListRead? {
    val dto = feedbackJson.decodeFromString(FeedbackTicketPageDto.serializer(), body)
    if (dto.degraded == true) return FeedbackTicketListRead.Degraded((dto.retryAfterSeconds ?: 5).coerceIn(1, 60))
    val tickets = dto.tickets ?: return null
    return FeedbackTicketListRead.Page(
        FeedbackTicketPage(
            tickets = tickets.mapNotNull { it.toModel() },
            nextCursor = dto.nextCursor?.takeIf { it.isNotBlank() },
            // Null: not known (a cursor page, or an app unavailable): never read as 0.
            unreadTotal = dto.unreadTotal?.coerceAtLeast(0),
        )
    )
}

internal fun parseFeedbackTicketDetail(body: String): FeedbackTicketDetail? {
    val dto = feedbackJson.decodeFromString(FeedbackTicketDetailDto.serializer(), body)
    val ticket = dto.ticket?.toModel() ?: return null
    return FeedbackTicketDetail(
        ticket = ticket,
        comments = dto.comments.orEmpty().mapNotNull { it.toModel() }.sortedWith(FeedbackCommentOrder),
        attachments = dto.attachments.orEmpty().mapNotNull { it.toModel() },
        nextCommentCursor = dto.nextCommentCursor?.takeIf { it.isNotBlank() },
        unreadTotal = (dto.unreadTotal ?: 0).coerceAtLeast(0),
    )
}

/** The reply route answers the new comment (`{id, author_type, body, created_at}`). */
private fun parseFeedbackComment(body: String, fallbackBody: String): FeedbackComment? {
    val dto = feedbackJson.decodeFromString(FeedbackCommentDto.serializer(), body)
    return dto.copy(authorType = dto.authorType ?: FeedbackAuthorType.Reporter.wire, body = dto.body ?: fallbackBody).toModel()
}

private fun parseFeedbackCloseStatus(body: String): String? =
    runCatching { feedbackJson.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content }.getOrNull()

/** Web `mergeCommentPages`: oldest first, ties by id. */
internal val FeedbackCommentOrder: Comparator<FeedbackComment> = compareBy<FeedbackComment>({ it.createdAt }, { it.id })

/** Web `mergeCommentPages`: a later page's comments replace same-id ones, then sorted. */
internal fun mergeFeedbackComments(current: List<FeedbackComment>, incoming: List<FeedbackComment>): List<FeedbackComment> {
    val merged = LinkedHashMap<String, FeedbackComment>()
    current.forEach { merged[it.id] = it }
    incoming.forEach { merged[it.id] = it }
    return merged.values.sortedWith(FeedbackCommentOrder)
}

/** Web `mergeTicketPages`: later pages update rows already listed and append the rest. */
internal fun mergeFeedbackTicketPages(current: List<FeedbackTicket>, incoming: List<FeedbackTicket>): List<FeedbackTicket> {
    val merged = LinkedHashMap<String, FeedbackTicket>()
    current.forEach { merged[it.id] = it }
    incoming.forEach { merged[it.id] = it }
    return merged.values.toList()
}

private fun FeedbackTicketDto.toModel(): FeedbackTicket? {
    val id = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val status = FeedbackTicketStatus.fromWire(status) ?: return null
    val createdAt = createdAt ?: return null
    val unreadCount = (unreadCount ?: 0).coerceAtLeast(0)
    return FeedbackTicket(
        id = id,
        // `crash` reads as a bug, as on the web (ticketKind).
        kind = if (kind == "feedback") FeedbackTicketKind.Feedback else FeedbackTicketKind.Bug,
        status = status,
        closureReason = FeedbackClosureReason.fromWire(closureReason),
        message = message.orEmpty(),
        createdAt = createdAt,
        updatedAt = updatedAt ?: createdAt,
        unread = unread ?: (unreadCount > 0),
        unreadCount = unreadCount,
        attachmentCount = (attachmentCount ?: 0).coerceAtLeast(0),
        commentCount = (commentCount ?: 0).coerceAtLeast(0),
        latestComment = latestComment?.toModel(),
        source = source?.trim()?.takeIf { it in FeedbackTicketSources },
        client = FeedbackClient.fromWire(client),
    )
}

private fun FeedbackLatestCommentDto.toModel(): FeedbackLatestComment? {
    val author = FeedbackAuthorType.fromWire(authorType) ?: return null
    return FeedbackLatestComment(author, bodyPreview.orEmpty(), createdAt ?: return null)
}

private fun FeedbackCommentDto.toModel(): FeedbackComment? {
    val id = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val author = FeedbackAuthorType.fromWire(authorType) ?: return null
    return FeedbackComment(id, author, body.orEmpty(), createdAt ?: return null)
}

private fun FeedbackAttachmentDto.toModel(): FeedbackAttachment? {
    val id = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return FeedbackAttachment(
        id = id,
        filename = filename?.takeIf { it.isNotBlank() } ?: id,
        contentType = contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
        sizeBytes = (sizeBytes ?: 0L).coerceAtLeast(0L),
        createdAt = createdAt ?: 0L,
    )
}

/**
 * The list preview of a comment as one plain line (web `previewLine`): code blocks and images
 * dropped, links to their text, markdown marks removed, whitespace collapsed.
 */
fun feedbackPreviewLine(body: String): String =
    body
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("[`*_~>#]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
