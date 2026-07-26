package build.raft.app.data

import build.raft.app.slockSha256Hex
import build.raft.app.network.MultipartFile
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal const val PRODUCT_FEEDBACK_MAX_ATTACHMENTS = 3
internal const val PRODUCT_FEEDBACK_MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L
internal const val PRODUCT_FEEDBACK_MAX_MESSAGE_CODE_POINTS = 10_000

private val ProductFeedbackJson = Json { ignoreUnknownKeys = true }
private val ProductFeedbackUuidRegex =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
private val ProductFeedbackImageMimeTypes =
    setOf("image/gif", "image/jpeg", "image/png", "image/webp")

internal enum class ProductFeedbackTicketKind {
    Feedback,
    Bug,
    Crash
}

internal enum class ProductFeedbackTicketStatus {
    Open,
    InProgress,
    Resolved,
    Closed
}

internal enum class ProductFeedbackAuthorType {
    Reporter,
    Staff,
    System
}

internal data class ProductFeedbackTicketSummary(
    val id: String,
    val kind: ProductFeedbackTicketKind,
    val status: ProductFeedbackTicketStatus,
    val message: String,
    val versionName: String?,
    val channel: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val attachmentCount: Int,
    val commentCount: Int,
    val latestCommentAt: Long?,
    val unread: Boolean,
    val unreadCount: Int
)

internal data class ProductFeedbackTicketPage(
    val tickets: List<ProductFeedbackTicketSummary>,
    val nextCursor: String?,
    val unreadTotal: Int
)

internal data class ProductFeedbackComment(
    val id: String,
    val authorType: ProductFeedbackAuthorType,
    val body: String,
    val createdAt: Long
)

internal data class ProductFeedbackAttachmentMetadata(
    val id: String,
    val filename: String,
    val contentType: String?,
    val sizeBytes: Long,
    val createdAt: Long
)

internal data class ProductFeedbackTicketDetail(
    val ticket: ProductFeedbackTicketSummary,
    val comments: List<ProductFeedbackComment>,
    val attachments: List<ProductFeedbackAttachmentMetadata>,
    val nextCommentCursor: String?,
    val unreadTotal: Int
)

internal data class ProductFeedbackDownloadedAttachment(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray
)

internal class ProductFeedbackAttachmentSnapshot private constructor(
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentSha256: String,
    val durableFileRef: String?,
    bytes: ByteArray
) {
    private val frozenBytes = bytes.copyOf()
    val bytes: ByteArray
        get() = frozenBytes.copyOf()

    companion object {
        fun create(
            filename: String,
            mimeType: String,
            bytes: ByteArray,
            durableFileRef: String? = null
        ): ProductFeedbackAttachmentSnapshot =
            ProductFeedbackAttachmentSnapshot(
                filename = filename,
                mimeType = mimeType.lowercase(),
                sizeBytes = bytes.size.toLong(),
                contentSha256 = slockSha256Hex(bytes),
                durableFileRef = durableFileRef,
                bytes = bytes.copyOf()
            )
    }

    fun immutableCopy(): ProductFeedbackAttachmentSnapshot =
        ProductFeedbackAttachmentSnapshot(filename, mimeType, sizeBytes, contentSha256, durableFileRef, frozenBytes)

    fun toRetryPlatformFile(
        index: Int,
        immutableOwnerKey: String?,
        immutableScopeKey: String?
    ): PlatformFile =
        PlatformFile(
            id = "feedback-retry-$index-$contentSha256",
            fileName = filename,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            previewUri = durableFileRef,
            fileRef = durableFileRef,
            source = "product_feedback_frozen",
            contentSha256 = contentSha256,
            immutableOwnerKey = immutableOwnerKey,
            immutableScopeKey = immutableScopeKey
        )
}

internal class ProductFeedbackSubmissionSnapshot private constructor(
    val submissionId: String,
    val body: String,
    val attachments: List<ProductFeedbackAttachmentSnapshot>,
    val immutableOwnerKey: String?,
    val immutableScopeKey: String?
) {
    companion object {
        fun create(
            body: String,
            attachments: List<ProductFeedbackAttachmentSnapshot>,
            submissionId: String = productFeedbackUuidV4(),
            immutableOwnerKey: String? = null,
            immutableScopeKey: String? = null
        ): ProductFeedbackSubmissionSnapshot =
            ProductFeedbackSubmissionSnapshot(
                submissionId = submissionId,
                body = body,
                attachments = attachments.map(ProductFeedbackAttachmentSnapshot::immutableCopy),
                immutableOwnerKey = immutableOwnerKey,
                immutableScopeKey = immutableScopeKey
            )
    }

    fun toRetryPlatformFiles(): List<PlatformFile> =
        attachments.mapIndexed { index, attachment ->
            attachment.toRetryPlatformFile(index, immutableOwnerKey, immutableScopeKey)
        }
}

internal sealed interface ProductFeedbackFailure {
    val retryable: Boolean

    data class Invalid(val reason: String) : ProductFeedbackFailure {
        override val retryable: Boolean = false
    }

    data object NotFound : ProductFeedbackFailure {
        override val retryable: Boolean = false
    }

    data object Conflict : ProductFeedbackFailure {
        override val retryable: Boolean = false
    }

    data class RateLimited(val retryAfterSeconds: Int?) : ProductFeedbackFailure {
        override val retryable: Boolean = true
    }

    data object Unavailable : ProductFeedbackFailure {
        override val retryable: Boolean = true
    }

    data object Unauthorized : ProductFeedbackFailure {
        override val retryable: Boolean = true
    }

    data object Offline : ProductFeedbackFailure {
        override val retryable: Boolean = true
    }

    data object Cancelled : ProductFeedbackFailure {
        override val retryable: Boolean = false
    }
}

internal sealed interface ProductFeedbackResult<out T> {
    data class Success<T>(val value: T) : ProductFeedbackResult<T>
    data class Failure(val error: ProductFeedbackFailure) : ProductFeedbackResult<Nothing>
}

internal class ProductFeedbackRepository(
    private val accountId: String,
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient,
    private val cleanupStore: ProductFeedbackCleanupStore = productFeedbackCleanupStore()
) {
    private val immutableOwnerKey = productFeedbackImmutableOwnerKey(accountId)

    suspend fun prepareSubmission(
        body: String,
        files: List<PlatformFile>,
        submissionId: String = productFeedbackUuidV4()
    ): ProductFeedbackResult<ProductFeedbackSubmissionSnapshot> {
        if (sessionStore.readSession()?.user?.id != accountId) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unauthorized)
        }
        val multipartFiles =
            files.map { file ->
                MultipartFile(
                    name = "attachments",
                    fileName = file.fileName,
                    mimeType = file.mimeType.orEmpty().lowercase(),
                    bytes = file.bytes,
                    fileRef = file.fileRef,
                    sizeBytes = file.sizeBytes
                )
            }
        val scopeKey = productFeedbackImmutableScopeKey(submissionId)
        val resolved =
            networkClient.snapshotMultipartFiles(
                priority = DataRequestPriority.Foreground,
                ownerKey = immutableOwnerKey,
                scopeKey = scopeKey,
                files = multipartFiles
            )
        if (resolved !is DataResult.Content) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
        }
        if (sessionStore.readSession()?.user?.id != accountId) {
            retireScope(immutableOwnerKey, scopeKey)
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unauthorized)
        }
        val snapshot =
            ProductFeedbackSubmissionSnapshot.create(
                body = body,
                submissionId = submissionId,
                immutableOwnerKey = immutableOwnerKey,
                immutableScopeKey = scopeKey,
                attachments = resolved.data.mapIndexed { index, file ->
                    val expectedHash = files[index].contentSha256
                    val actualHash = slockSha256Hex(file.bytes)
                    if (expectedHash != null && expectedHash != actualHash) {
                        val retired = retireScope(immutableOwnerKey, scopeKey)
                        return ProductFeedbackResult.Failure(
                            if (retired) ProductFeedbackFailure.Conflict else ProductFeedbackFailure.Unavailable
                        )
                    }
                    ProductFeedbackAttachmentSnapshot.create(
                        filename = file.fileName,
                        mimeType = file.mimeType,
                        bytes = file.bytes,
                        durableFileRef = file.fileRef
                    )
                }
            )
        val validationFailure = validateProductFeedbackSubmission(snapshot)
        if (validationFailure != null) {
            return ProductFeedbackResult.Failure(
                if (retireSubmission(snapshot)) validationFailure else ProductFeedbackFailure.Unavailable
            )
        }
        return ProductFeedbackResult.Success(snapshot)
    }

    suspend fun retireSubmission(snapshot: ProductFeedbackSubmissionSnapshot): Boolean {
        val ownerKey = snapshot.immutableOwnerKey ?: return true
        val scopeKey = snapshot.immutableScopeKey ?: return true
        if (ownerKey != immutableOwnerKey) return false
        return retireScope(ownerKey, scopeKey)
    }

    suspend fun retireCachedAttachments(
        files: List<PlatformFile>,
        keepingScopeKey: String? = null
    ): Boolean {
        val results = files.mapNotNull { file ->
            val ownerKey = file.immutableOwnerKey ?: return@mapNotNull null
            val scopeKey = file.immutableScopeKey ?: return@mapNotNull null
            (ownerKey to scopeKey).takeIf { ownerKey == immutableOwnerKey && scopeKey != keepingScopeKey }
        }.distinct().map { (ownerKey, scopeKey) ->
            retireScope(ownerKey, scopeKey)
        }
        return results.all { it }
    }

    suspend fun retireAccount(): Boolean =
        cleanupStore.retireOwner(accountId, immutableOwnerKey) {
            networkClient.retireMultipartFileOwner(DataRequestPriority.Background, immutableOwnerKey)
        }

    private suspend fun retireScope(ownerKey: String, scopeKey: String): Boolean =
        cleanupStore.retireScope(ownerKey, scopeKey) {
            networkClient.retireMultipartFileScope(DataRequestPriority.Background, ownerKey, scopeKey)
        }

    suspend fun listTickets(
        cursor: String? = null,
        limit: Int = 20
    ): ProductFeedbackResult<ProductFeedbackTicketPage> =
        executeForAccount {
            networkClient.request(
                priority = DataRequestPriority.Foreground,
                path = "product-feedback/tickets",
                query = buildList {
                    add("limit" to limit.toString())
                    cursor?.let { add("cursor" to it) }
                }
            )
        }.parseTicketPageSuccess()

    suspend fun getTicket(
        ticketId: String,
        commentCursor: String? = null,
        commentLimit: Int = 50
    ): ProductFeedbackResult<ProductFeedbackTicketDetail> {
        if (!ticketId.isCanonicalProductFeedbackUuid()) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.NotFound)
        }
        return executeForAccount {
            networkClient.request(
                priority = DataRequestPriority.Foreground,
                path = "product-feedback/tickets/$ticketId",
                query = buildList {
                    add("comment_limit" to commentLimit.toString())
                    commentCursor?.let { add("comment_cursor" to it) }
                }
            )
        }.parseTicketDetailSuccess()
    }

    suspend fun create(
        snapshot: ProductFeedbackSubmissionSnapshot
    ): ProductFeedbackResult<ProductFeedbackTicketDetail> =
        finalizeSubmission(snapshot, createUnretired(snapshot))

    private suspend fun createUnretired(
        snapshot: ProductFeedbackSubmissionSnapshot
    ): ProductFeedbackResult<ProductFeedbackTicketDetail> {
        validateProductFeedbackSubmission(snapshot)?.let {
            return ProductFeedbackResult.Failure(it)
        }
        val response = executeForAccount {
            networkClient.requestMultipart(
                priority = DataRequestPriority.Foreground,
                path = "product-feedback",
                fields = mapOf(
                    "type" to "problem",
                    "message" to snapshot.body,
                    "submission_id" to snapshot.submissionId,
                    "may_contact" to "false"
                ),
                files = snapshot.toMultipartFiles()
            )
        }
        if (response.code != 201) {
            return if (response.isSuccessful) {
                ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
            } else {
                ProductFeedbackResult.Failure(response.toProductFeedbackFailure())
            }
        }
        val ticketId = response.bodyText?.let(::parseProductFeedbackCreateTicketId)
            ?: return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
        return getTicket(ticketId)
    }

    suspend fun reply(
        ticketId: String,
        snapshot: ProductFeedbackSubmissionSnapshot
    ): ProductFeedbackResult<ProductFeedbackTicketDetail> =
        finalizeSubmission(snapshot, replyUnretired(ticketId, snapshot))

    private suspend fun replyUnretired(
        ticketId: String,
        snapshot: ProductFeedbackSubmissionSnapshot
    ): ProductFeedbackResult<ProductFeedbackTicketDetail> {
        if (!ticketId.isCanonicalProductFeedbackUuid()) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.NotFound)
        }
        validateProductFeedbackSubmission(snapshot)?.let {
            return ProductFeedbackResult.Failure(it)
        }
        val response = executeForAccount {
            networkClient.requestMultipart(
                priority = DataRequestPriority.Foreground,
                path = "product-feedback/tickets/$ticketId/comments",
                fields = mapOf(
                    "body" to snapshot.body,
                    "submission_id" to snapshot.submissionId
                ),
                files = snapshot.toMultipartFiles()
            )
        }
        if (response.code != 200 && response.code != 201) {
            return if (response.isSuccessful) {
                ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
            } else {
                ProductFeedbackResult.Failure(response.toProductFeedbackFailure())
            }
        }
        if (response.bodyText?.let(::parseProductFeedbackComment) == null) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
        }
        return getTicket(ticketId)
    }

    private suspend fun finalizeSubmission(
        snapshot: ProductFeedbackSubmissionSnapshot,
        result: ProductFeedbackResult<ProductFeedbackTicketDetail>
    ): ProductFeedbackResult<ProductFeedbackTicketDetail> {
        val terminal = result is ProductFeedbackResult.Success ||
            (result is ProductFeedbackResult.Failure && !result.error.retryable)
        if (terminal && !retireSubmission(snapshot)) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
        }
        return result
    }

    suspend fun downloadAttachment(
        ticketId: String,
        attachment: ProductFeedbackAttachmentMetadata
    ): ProductFeedbackResult<ProductFeedbackDownloadedAttachment> {
        if (!ticketId.isCanonicalProductFeedbackUuid() || !attachment.id.isCanonicalProductFeedbackUuid()) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.NotFound)
        }
        if (attachment.sizeBytes > PRODUCT_FEEDBACK_MAX_ATTACHMENT_BYTES) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.Invalid("attachment_size"))
        }
        if (sessionStore.readSession()?.user?.id != accountId) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unauthorized)
        }
        val binary =
            networkClient.requestBinary(
                priority = DataRequestPriority.Foreground,
                path = "product-feedback/tickets/$ticketId/attachments/${attachment.id}",
                maxBytes = PRODUCT_FEEDBACK_MAX_ATTACHMENT_BYTES
            )
        if (sessionStore.readSession()?.user?.id != accountId) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unauthorized)
        }
        if (!binary.response.isSuccessful) {
            return ProductFeedbackResult.Failure(binary.response.toProductFeedbackFailure())
        }
        val headers = binary.response.responseHeaders
        val contentLength = headers.headerValue("Content-Length")?.toLongOrNull()
        val contentType = headers.headerValue("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        val cacheControl = headers.headerValue("Cache-Control")
        val noSniff = headers.headerValue("X-Content-Type-Options")
        if (
            contentLength != attachment.sizeBytes ||
            contentLength != binary.bytes.size.toLong() ||
            contentType !in ProductFeedbackImageMimeTypes ||
            cacheControl?.contains("private", ignoreCase = true) != true ||
            cacheControl?.contains("no-store", ignoreCase = true) != true ||
            !noSniff.equals("nosniff", ignoreCase = true)
        ) {
            return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
        }
        val safeContentType = contentType ?: return ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
        return ProductFeedbackResult.Success(
            ProductFeedbackDownloadedAttachment(
                filename = attachment.filename,
                contentType = safeContentType,
                bytes = binary.bytes.copyOf()
            )
        )
    }

    private suspend fun executeForAccount(block: suspend () -> HomeNetworkResponse): HomeNetworkResponse {
        if (sessionStore.readSession()?.user?.id != accountId) return unauthorizedResponse()
        val response = block()
        return if (sessionStore.readSession()?.user?.id == accountId) response else unauthorizedResponse()
    }
}

internal fun productFeedbackImmutableOwnerKey(accountId: String): String =
    slockSha256Hex("product-feedback-owner:$accountId".encodeToByteArray())

internal fun productFeedbackImmutableScopeKey(submissionId: String): String =
    slockSha256Hex("product-feedback-submission:$submissionId".encodeToByteArray())

internal fun validateProductFeedbackSubmission(
    snapshot: ProductFeedbackSubmissionSnapshot
): ProductFeedbackFailure.Invalid? {
    if (!snapshot.submissionId.isCanonicalProductFeedbackUuid()) {
        return ProductFeedbackFailure.Invalid("submission_id")
    }
    val body = snapshot.body.trim()
    if (body.isEmpty() || body.codePointCount() > PRODUCT_FEEDBACK_MAX_MESSAGE_CODE_POINTS) {
        return ProductFeedbackFailure.Invalid("body")
    }
    if (snapshot.attachments.size > PRODUCT_FEEDBACK_MAX_ATTACHMENTS) {
        return ProductFeedbackFailure.Invalid("attachment_count")
    }
    snapshot.attachments.forEach { attachment ->
        if (attachment.filename.isBlank()) return ProductFeedbackFailure.Invalid("attachment_filename")
        if (attachment.mimeType !in ProductFeedbackImageMimeTypes) {
            return ProductFeedbackFailure.Invalid("attachment_type")
        }
        if (
            attachment.sizeBytes != attachment.bytes.size.toLong() ||
            attachment.contentSha256 != slockSha256Hex(attachment.bytes) ||
            attachment.sizeBytes > PRODUCT_FEEDBACK_MAX_ATTACHMENT_BYTES
        ) {
            return ProductFeedbackFailure.Invalid("attachment_size")
        }
    }
    return null
}

internal fun parseProductFeedbackTicketPage(bodyText: String): ProductFeedbackTicketPage? =
    parseProductFeedbackObject(bodyText)?.let { root ->
        val tickets = root.strictArray("tickets")?.map { parseProductFeedbackTicket(it) ?: return null } ?: return null
        val nextCursor = root.strictNullableString("next_cursor") ?: return null
        val unreadTotal = root.strictCount("unread_total") ?: return null
        ProductFeedbackTicketPage(tickets, nextCursor.value, unreadTotal)
    }

internal fun parseProductFeedbackTicketDetail(bodyText: String): ProductFeedbackTicketDetail? =
    parseProductFeedbackObject(bodyText)?.let { root ->
        val ticket = root["ticket"]?.let(::parseProductFeedbackTicket) ?: return null
        val comments = root.strictArray("comments")?.map { parseProductFeedbackComment(it) ?: return null } ?: return null
        val attachments = root.strictArray("attachments")?.map { parseProductFeedbackAttachment(it) ?: return null } ?: return null
        val nextCursor = root.strictNullableString("next_comment_cursor") ?: return null
        val unreadTotal = root.strictCount("unread_total") ?: return null
        ProductFeedbackTicketDetail(ticket, comments, attachments, nextCursor.value, unreadTotal)
    }

private fun parseProductFeedbackTicket(element: JsonElement): ProductFeedbackTicketSummary? {
    val value = element as? JsonObject ?: return null
    val id = value.strictString("id")?.takeIf(String::isCanonicalProductFeedbackUuid) ?: return null
    val kind = when (value.strictString("kind")) {
        "feedback" -> ProductFeedbackTicketKind.Feedback
        "bug" -> ProductFeedbackTicketKind.Bug
        "crash" -> ProductFeedbackTicketKind.Crash
        else -> return null
    }
    val status = when (value.strictString("status")) {
        "open" -> ProductFeedbackTicketStatus.Open
        "in_progress" -> ProductFeedbackTicketStatus.InProgress
        "resolved" -> ProductFeedbackTicketStatus.Resolved
        "closed" -> ProductFeedbackTicketStatus.Closed
        else -> return null
    }
    val unread = value.strictBoolean("unread") ?: return null
    val unreadCount = value.strictCount("unread_count") ?: return null
    val versionName = value.strictNullableString("version_name") ?: return null
    val channel = value.strictNullableString("channel") ?: return null
    val latestCommentAt = value.strictNullableEpochMillis("latest_comment_at") ?: return null
    if (unread != (unreadCount > 0)) return null
    return ProductFeedbackTicketSummary(
        id = id,
        kind = kind,
        status = status,
        message = value.strictString("message") ?: return null,
        versionName = versionName.value,
        channel = channel.value,
        createdAt = value.strictEpochMillis("created_at") ?: return null,
        updatedAt = value.strictEpochMillis("updated_at") ?: return null,
        attachmentCount = value.strictCount("attachment_count") ?: return null,
        commentCount = value.strictCount("comment_count") ?: return null,
        latestCommentAt = latestCommentAt.value,
        unread = unread,
        unreadCount = unreadCount
    )
}

internal fun parseProductFeedbackComment(bodyText: String): ProductFeedbackComment? =
    parseProductFeedbackObject(bodyText)?.let(::parseProductFeedbackComment)

private fun parseProductFeedbackComment(element: JsonElement): ProductFeedbackComment? {
    val value = element as? JsonObject ?: return null
    val authorType = when (value.strictString("author_type")) {
        "reporter" -> ProductFeedbackAuthorType.Reporter
        "staff" -> ProductFeedbackAuthorType.Staff
        "system" -> ProductFeedbackAuthorType.System
        else -> return null
    }
    return ProductFeedbackComment(
        id = value.strictString("id")?.takeIf(String::isCanonicalProductFeedbackUuid) ?: return null,
        authorType = authorType,
        body = value.strictString("body") ?: return null,
        createdAt = value.strictEpochMillis("created_at") ?: return null
    )
}

private fun parseProductFeedbackAttachment(element: JsonElement): ProductFeedbackAttachmentMetadata? {
    val value = element as? JsonObject ?: return null
    val filename = value.strictString("filename")?.takeIf(String::isNotBlank) ?: return null
    val contentType = value.strictNullableString("content_type") ?: return null
    return ProductFeedbackAttachmentMetadata(
        id = value.strictString("id")?.takeIf(String::isCanonicalProductFeedbackUuid) ?: return null,
        filename = filename,
        contentType = contentType.value,
        sizeBytes = value.strictCountLong("size_bytes") ?: return null,
        createdAt = value.strictEpochMillis("created_at") ?: return null
    )
}

private fun parseProductFeedbackCreateTicketId(bodyText: String): String? {
    val value = parseProductFeedbackObject(bodyText) ?: return null
    val id = value.strictString("id")?.takeIf(String::isCanonicalProductFeedbackUuid) ?: return null
    value.strictString("status") ?: return null
    return id
}

private fun ProductFeedbackSubmissionSnapshot.toMultipartFiles(): List<MultipartFile> =
    attachments.map { attachment ->
        MultipartFile(
            name = "attachments",
            fileName = attachment.filename,
            mimeType = attachment.mimeType,
            bytes = attachment.bytes.copyOf(),
            sizeBytes = attachment.sizeBytes
        )
    }

private fun HomeNetworkResponse.parseTicketPageSuccess(): ProductFeedbackResult<ProductFeedbackTicketPage> =
    if (isSuccessful) {
        bodyText?.let(::parseProductFeedbackTicketPage)?.let { ProductFeedbackResult.Success(it) }
            ?: ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
    } else {
        ProductFeedbackResult.Failure(toProductFeedbackFailure())
    }

private fun HomeNetworkResponse.parseTicketDetailSuccess(): ProductFeedbackResult<ProductFeedbackTicketDetail> =
    if (isSuccessful) {
        bodyText?.let(::parseProductFeedbackTicketDetail)?.let { ProductFeedbackResult.Success(it) }
            ?: ProductFeedbackResult.Failure(ProductFeedbackFailure.Unavailable)
    } else {
        ProductFeedbackResult.Failure(toProductFeedbackFailure())
    }

private fun HomeNetworkResponse.toProductFeedbackFailure(): ProductFeedbackFailure {
    val codeValue = (errorBodyText ?: bodyText)
        ?.let(::parseProductFeedbackObject)
        ?.strictString("code")
    return when {
        code == 0 -> ProductFeedbackFailure.Offline
        code == 401 || code == 403 || codeValue == "auth_required" -> ProductFeedbackFailure.Unauthorized
        code == 404 || codeValue == "feedback_not_found" -> ProductFeedbackFailure.NotFound
        code == 409 || codeValue == "feedback_submission_conflict" || codeValue == "feedback_comment_conflict" ->
            ProductFeedbackFailure.Conflict
        code == 429 || codeValue == "feedback_rate_limited" ->
            ProductFeedbackFailure.RateLimited(retryAfterSeconds())
        code == 400 || code == 413 || codeValue?.startsWith("feedback_attachment_") == true ||
            codeValue == "feedback_invalid" || codeValue == "feedback_upload_invalid" ->
            ProductFeedbackFailure.Invalid(codeValue ?: "invalid")
        else -> ProductFeedbackFailure.Unavailable
    }
}

private fun HomeNetworkResponse.retryAfterSeconds(): Int? {
    val header = responseHeaders.entries
        .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.toIntOrNull()
    val body = (errorBodyText ?: bodyText)
        ?.let(::parseProductFeedbackObject)
        ?.get("retry_after_seconds")
        ?.jsonPrimitive
        ?.intOrNull
    return (header ?: body)?.coerceIn(1, 300)
}

internal fun productFeedbackUuidV4(random: Random = Random.Default): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

private fun String.isCanonicalProductFeedbackUuid(): Boolean = ProductFeedbackUuidRegex.matches(this)

private fun String.codePointCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        val first = this[index]
        index += if (first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
        count += 1
    }
    return count
}

private fun parseProductFeedbackObject(bodyText: String): JsonObject? =
    runCatching { ProductFeedbackJson.parseToJsonElement(bodyText).jsonObject }.getOrNull()

private data class NullableField<T>(val value: T?)

private fun JsonObject.strictString(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.strictNullableString(key: String): NullableField<String>? {
    val element = get(key) ?: return null
    if (element is JsonNull) return NullableField(null)
    return (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let(::NullableField)
}

private fun JsonObject.strictBoolean(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

private fun JsonObject.strictArray(key: String): JsonArray? =
    get(key)?.let { it as? JsonArray }

private fun JsonObject.strictCount(key: String): Int? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }

private fun JsonObject.strictCountLong(key: String): Long? =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0L }

private fun JsonObject.strictEpochMillis(key: String): Long? = strictCountLong(key)

private fun JsonObject.strictNullableEpochMillis(key: String): NullableField<Long>? {
    val element = get(key) ?: return null
    if (element is JsonNull) return NullableField(null)
    return (element as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.longOrNull
        ?.takeIf { it >= 0L }
        ?.let(::NullableField)
}

private fun unauthorizedResponse(): HomeNetworkResponse =
    HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = "{\"code\":\"auth_required\"}")

private fun Map<String, List<String>>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
