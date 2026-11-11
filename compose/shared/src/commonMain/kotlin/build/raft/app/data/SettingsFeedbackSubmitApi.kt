package build.raft.app.data

import build.raft.app.FeedbackDiagnosticsReference
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/*
 * Settings › Feedback "New Feedback" while signed in: the ticket is filed through the Raft
 * server's proxy, `POST /api/product-feedback` (slock packages/server/src/routes/productFeedback.ts),
 * as the web page does (packages/web/src/feedback/createHandsFeedbackTransport.ts `createTicket`),
 * so the server files it under this person (X-Hands-Reporter-Id) and it shows in their own ticket
 * list (`GET /api/product-feedback/tickets`). The native Hands SDK form files anonymously; it stays
 * only for signed-out feedback and for a server that cannot file it ([SettingsFeedbackProxyOutcome.UseNativeForm]).
 *
 * The body is the web form (`productFeedbackForm`): `type`, `message`, `metadata`,
 * `submission_id`, `may_contact`, then `diagnostics` (the presigned archive's reference, web
 * standalone/diagnostics.ts) and the image `attachments` (PNG / JPEG / GIF / WebP only).
 *
 * Sent from an agent's `feedback:submit` card (slock docs/development/feedback-native-entry.md,
 * "Opened from a feedback card"; web standalone/actionCard.tsx + agentFeedbackCard.ts): the card's
 * draft is read first (`GET /api/product-feedback/action-card/:messageId`, `X-Server-Id` the server
 * the card is viewed in), and the send adds `action_card_message_id` and
 * `expected_confirmation_version` under the same `X-Server-Id`; the server checks the card before
 * Hands and completes it from the receipt.
 */

/** Server `PRODUCT_FEEDBACK_MAX_ATTACHMENTS`. */
const val PRODUCT_FEEDBACK_MAX_ATTACHMENTS: Int = 3

/** The image types the route accepts (server `ALLOWED_IMAGE_TYPES`). */
internal val ProductFeedbackImageContentTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

private val ProductFeedbackImageExtensions =
    mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
    )

/**
 * The content type an attachment is sent with, or null when the route would refuse it
 * (`feedback_attachment_type_invalid`): its MIME type when that is an accepted image type, else
 * its file name's extension.
 */
internal fun PlatformFile.productFeedbackImageContentType(): String? {
    val declared = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty().let { if (it == "image/jpg") "image/jpeg" else it }
    if (declared in ProductFeedbackImageContentTypes) return declared
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").trim().lowercase()
    return ProductFeedbackImageExtensions[extension]
}

/**
 * The form's Idea / Bug choice (web NewFeedbackForm `kind`: `feedback` / `bug`), sent as the
 * `type` field (`idea` / `problem`, web createTicket).
 */
enum class SettingsFeedbackType(val wireValue: String) {
    Idea("idea"),
    Problem("problem"),
}

/**
 * A `feedback:submit` card's draft as this person may send it now
 * (`GET /api/product-feedback/action-card/:messageId`: `message_id`, `feedback_type`, `message`,
 * `confirmation_version`), and [serverId], the server it is viewed (operated) from. Identity and
 * the confirmation it was read at go back with the send.
 */
data class SettingsFeedbackCardDraft(
    val messageId: String,
    val serverId: String,
    val type: SettingsFeedbackType,
    val message: String,
    val confirmationVersion: Long,
)

/** One `POST /api/product-feedback`. Safe to resend with the same [submissionId]: Hands dedupes on it. */
data class SettingsFeedbackSubmitRequest(
    val message: String,
    val attachments: List<PlatformFile>,
    val submissionId: String,
    /** The UI locale (`en`, `zh-CN`, `ja`), the form's `metadata.locale`. */
    val locale: String,
    val diagnostics: FeedbackDiagnosticsReference? = null,
    val type: SettingsFeedbackType = SettingsFeedbackType.Problem,
    /** Sent as this card's submission (its draft, possibly edited). */
    val card: SettingsFeedbackCardDraft? = null,
)

interface SettingsFeedbackSubmitRepository {
    /** The new ticket's id. */
    suspend fun submit(request: SettingsFeedbackSubmitRequest): FeedbackResult<String>

    /**
     * The draft of card [messageId] as operated from [serverId], or null whenever it cannot be read
     * (the server's one 404 for every refusal, a network failure, a malformed answer): the caller
     * then opens the ordinary Feedback page, as the web page does.
     */
    suspend fun loadCardDraft(messageId: String, serverId: String): SettingsFeedbackCardDraft? = null
}

class NetworkSettingsFeedbackSubmitRepository(
    private val networkClient: RaftNetworkClient,
    /** `android` / `ios` (raftFeedbackWebViewClientKind): selects the Hands app the ticket list reads. */
    private val clientKind: String,
    /** The app version, `metadata.client_version` (a native client kind requires it). */
    private val clientVersion: String,
    private val logger: (String) -> Unit = {},
) : SettingsFeedbackSubmitRepository {
    override suspend fun submit(request: SettingsFeedbackSubmitRequest): FeedbackResult<String> {
        val kind = clientKind.trim()
        val files =
            request.attachments.take(PRODUCT_FEEDBACK_MAX_ATTACHMENTS).map { file ->
                val contentType =
                    file.productFeedbackImageContentType()
                        ?: return FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.Invalid, "feedback_attachment_type_invalid"))
                file.toProductFeedbackMultipartFile(contentType)
            }
        val response =
            try {
                networkClient.requestMultipartWithHeaders(
                    priority = DataRequestPriority.Foreground,
                    path = PRODUCT_FEEDBACK_ROUTE,
                    headers = if (kind.isEmpty()) emptyMap() else mapOf(FEEDBACK_CLIENT_KIND_HEADER to kind),
                    fields = productFeedbackFormFields(request, kind, clientVersion.trim()),
                    files = files,
                    // A card's send is checked as operated from the server it is viewed in.
                    serverIdOverride = request.card?.serverId?.let(::ServerId),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logger("settings_feedback_submit_exception type=${error::class.simpleName}")
                HomeNetworkResponse(code = 0, bodyText = null)
            }
        logger(
            "settings_feedback_submit code=${response.code} attachments=${files.size} " +
                "diagnostics=${request.diagnostics != null} card=${request.card != null}"
        )
        if (!response.isSuccessful) {
            val failure =
                feedbackFailureOf(response.code, response.errorBodyText ?: response.bodyText)
                    .let { it.copy(traceId = response.traceId ?: it.traceId) }
            return FeedbackResult.Failed(
                if (response.code == 429) failure.copy(retryAfterSeconds = response.retryAfterHeaderSeconds()) else failure
            )
        }
        val ticketId =
            response.bodyText
                ?.let { body -> runCatching { submitJson.decodeFromString(AgentIssueTicketReceipt.serializer(), body) }.getOrNull() }
                ?.id
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        if (ticketId == null) {
            logger("settings_feedback_submit_contract_mismatch")
            return FeedbackResult.Failed(FeedbackFailure(FeedbackErrorKind.Unavailable, "feedback_contract_mismatch", response.code))
        }
        return FeedbackResult.Ok(ticketId)
    }

    override suspend fun loadCardDraft(messageId: String, serverId: String): SettingsFeedbackCardDraft? {
        val id = messageId.trim().takeIf { it.isNotEmpty() } ?: return null
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        val kind = clientKind.trim()
        val response =
            try {
                networkClient.requestWithHeaders(
                    priority = DataRequestPriority.Foreground,
                    path = "$PRODUCT_FEEDBACK_ROUTE/action-card/$id",
                    headers = if (kind.isEmpty()) emptyMap() else mapOf(FEEDBACK_CLIENT_KIND_HEADER to kind),
                    serverIdOverride = ServerId(server),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logger("settings_feedback_card_draft_unreadable reason=transport type=${error::class.simpleName}")
                return null
            }
        val draft = if (response.isSuccessful) parseProductFeedbackCardDraft(response.bodyText, id, server) else null
        val reason =
            when {
                draft != null -> null
                response.code == 0 -> "transport"
                response.code == 404 -> "card_unavailable"
                !response.isSuccessful -> "http_${response.code}"
                else -> "contract_mismatch"
            }
        // The page then opens the ticket list (web's ordinary page); the reason code says why.
        logger(
            if (reason == null) "settings_feedback_card_draft_ready code=${response.code}"
            else "settings_feedback_card_draft_unreadable reason=$reason code=${response.code} traceId=${response.traceId.orEmpty()}"
        )
        return draft
    }
}

/**
 * The card draft route's answer for card [messageId] (web `loadFeedbackActionCardDraft`): the same
 * message id, an `idea` / `problem` type, a message, and a positive confirmation version; anything
 * else is no draft.
 */
internal fun parseProductFeedbackCardDraft(body: String?, messageId: String, serverId: String): SettingsFeedbackCardDraft? {
    val wire =
        body?.takeIf { it.isNotBlank() }
            ?.let { text -> runCatching { submitJson.decodeFromString(ProductFeedbackCardDraftWire.serializer(), text) }.getOrNull() }
            ?: return null
    if (wire.messageId != messageId) return null
    val type = SettingsFeedbackType.entries.firstOrNull { it.wireValue == wire.feedbackType } ?: return null
    val message = wire.message ?: return null
    val version = wire.confirmationVersion?.takeIf { it >= 1L } ?: return null
    return SettingsFeedbackCardDraft(messageId = messageId, serverId = serverId, type = type, message = message, confirmationVersion = version)
}

@Serializable
private data class ProductFeedbackCardDraftWire(
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("feedback_type") val feedbackType: String? = null,
    val message: String? = null,
    @SerialName("confirmation_version") val confirmationVersion: Long? = null,
)

private const val PRODUCT_FEEDBACK_ROUTE = "product-feedback"

private val submitJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** Web `DiagnosticsReference`: exactly these four fields (server `parseProductFeedbackDiagnostics`). */
@Serializable
private data class ProductFeedbackDiagnosticsWire(
    @SerialName("r2_key") val r2Key: String,
    val filename: String,
    @SerialName("content_type") val contentType: String,
    val size: Long,
)

/** The form's string fields, in the web form's order. */
internal fun productFeedbackFormFields(
    request: SettingsFeedbackSubmitRequest,
    clientKind: String,
    clientVersion: String,
): Map<String, String> =
    buildMap {
        put("type", request.type.wireValue)
        put("message", request.message)
        put("metadata", productFeedbackMetadataJson(clientKind, clientVersion, request.locale))
        put("submission_id", request.submissionId)
        put("may_contact", "false")
        request.diagnostics?.let { ref ->
            put(
                "diagnostics",
                submitJson.encodeToString(
                    ProductFeedbackDiagnosticsWire.serializer(),
                    ProductFeedbackDiagnosticsWire(ref.r2Key, ref.filename, ref.contentType, ref.size),
                )
            )
        }
        // A card's send (web sendFeedbackCard): the card, and the confirmation its draft was read at.
        request.card?.let { card ->
            put("action_card_message_id", card.messageId)
            put("expected_confirmation_version", card.confirmationVersion.toString())
        }
    }

/** The multipart `attachments` part: streamed from its file ref, else its bytes. */
private fun PlatformFile.toProductFeedbackMultipartFile(contentType: String): MultipartFile {
    val ref = fileRef?.trim()?.takeIf { it.isNotEmpty() }
    val name = fileName.ifBlank { "screenshot." + contentType.substringAfter('/') }
    return if (ref != null && bytes.isEmpty()) {
        MultipartFile(name = "attachments", fileName = name, mimeType = contentType, fileRef = ref, sizeBytes = sizeBytes)
    } else {
        MultipartFile(name = "attachments", fileName = name, mimeType = contentType, bytes = bytes, sizeBytes = sizeBytes ?: bytes.size.toLong())
    }
}

/**
 * Uploads the app's diagnostics archive (Hands' presign + direct PUT, done natively) and answers
 * its reference, or null when there is none to attach: generating or uploading failed, it was too
 * large, it stalled, or the host cannot. Never fails the feedback itself (web skips the archive).
 */
fun interface SettingsFeedbackDiagnosticsUploader {
    suspend fun upload(accountId: String): FeedbackDiagnosticsReference?
}

/**
 * One feedback being sent. Every retry of it keeps its [submissionId] and its uploaded
 * [diagnostics] (web createDiagnosticsSubmission: "a retry never starts another upload").
 */
data class SettingsFeedbackProxyAttempt(
    val message: String,
    val attachments: List<PlatformFile>,
    val submissionId: String,
    /** The diagnostics step ran (whether or not it produced an archive). */
    val diagnosticsPrepared: Boolean = false,
    val diagnostics: FeedbackDiagnosticsReference? = null,
    val type: SettingsFeedbackType = SettingsFeedbackType.Problem,
    /** Sent as this card's submission. */
    val card: SettingsFeedbackCardDraft? = null,
)

sealed interface SettingsFeedbackProxyOutcome {
    data class Filed(val ticketId: String) : SettingsFeedbackProxyOutcome

    /**
     * The server cannot file it: no product-feedback route, or its Hands integration is
     * unavailable (web host's `integration-unavailable`, which hands over to the native form).
     */
    object UseNativeForm : SettingsFeedbackProxyOutcome

    /**
     * [code] is one of the form's `settings_feedback_*` failure codes. [retry] is what a Retry
     * sends again (null: retrying the same thing cannot help).
     */
    data class Failed(
        val code: String,
        val retryable: Boolean,
        val retryAfterSeconds: Long? = null,
        val retry: SettingsFeedbackProxyAttempt? = null,
        /** The refusing response's server trace id, shown under the error (AGENTS.md "Errors carry the trace ID"). */
        val traceId: String? = null,
    ) : SettingsFeedbackProxyOutcome
}

/** The Settings form's codes for a failure it explains (SettingsHandsFeedbackCopy.submissionFailure). */
internal const val SETTINGS_FEEDBACK_ATTACHMENT_TYPE_INVALID = "settings_feedback_attachment_type_invalid"

/** A card this person already sent (409 `feedback_card_already_sent`): the page opens the ticket list, where it is. */
internal const val SETTINGS_FEEDBACK_CARD_ALREADY_SENT = "settings_feedback_card_already_sent"

/** A card that is no longer waiting (`STATE_MISMATCH`): web `actionCard.feedback.error.alreadySent`. */
internal const val SETTINGS_FEEDBACK_CARD_NOT_WAITING = "settings_feedback_card_not_waiting"

/** The card changed after its draft was read: web `actionCard.feedback.error.changed`. */
internal const val SETTINGS_FEEDBACK_CARD_CHANGED = "settings_feedback_card_changed"

/** Feedback cards are off here, or this is not one: web `actionCard.feedback.error.unavailable`. */
internal const val SETTINGS_FEEDBACK_CARD_UNAVAILABLE = "settings_feedback_card_unavailable"

/** This person may not send the card: web `actionCard.feedback.error.notAllowed`. */
internal const val SETTINGS_FEEDBACK_CARD_NOT_ALLOWED = "settings_feedback_card_not_allowed"

/**
 * A card send's refusals (web FeedbackCardForm `REFUSALS`, server `assertFeedbackCardSendable` /
 * `sendFeedbackCardRefusal`), by the server's `code`, to the form's codes. All are final: a retry
 * of the same send cannot help.
 */
private val FeedbackCardRefusals: Map<String, String> =
    mapOf(
        "feedback_card_already_sent" to SETTINGS_FEEDBACK_CARD_ALREADY_SENT,
        "STATE_MISMATCH" to SETTINGS_FEEDBACK_CARD_NOT_WAITING,
        "NOT_FEEDBACK_CARD" to SETTINGS_FEEDBACK_CARD_UNAVAILABLE,
        "FEATURE_DISABLED" to SETTINGS_FEEDBACK_CARD_UNAVAILABLE,
        "NOT_A_MEMBER" to SETTINGS_FEEDBACK_CARD_NOT_ALLOWED,
        "CONFIRMATION_VERSION_MISMATCH" to SETTINGS_FEEDBACK_CARD_CHANGED,
        "CONFIRMATION_VERSION_REQUIRED" to SETTINGS_FEEDBACK_CARD_CHANGED,
        "action_card_reconfirmation_required" to SETTINGS_FEEDBACK_CARD_CHANGED,
        "action_card_conversion_frozen" to SETTINGS_FEEDBACK_CARD_CHANGED,
    )

/** Diagnostics the server or Hands refused: the feedback is sent once more without them (web `omit`). */
private val DiagnosticsRefusedCodes = setOf("feedback_diagnostics_invalid", "feedback_diagnostics_rejected")

/**
 * Sends Settings feedback through the Raft server: attachment check, the diagnostics archive
 * (once per feedback), the request, and what a failure means for the form.
 */
class SettingsFeedbackProxySubmitter(
    private val repository: SettingsFeedbackSubmitRepository,
    private val diagnostics: SettingsFeedbackDiagnosticsUploader?,
    private val accountId: String,
    private val locale: () -> String,
    private val newSubmissionId: () -> String = ::newSettingsFeedbackSubmissionId,
    private val logger: (String) -> Unit = {},
    /** A card's draft filed: Settings › Feedback stops suggesting an agent's draft ([accountId]'s flag). */
    private val agentDraftSent: RaftFeedbackAgentDraftSentRepository = RaftFeedbackAgentDraftSent.repository,
) {
    fun newAttempt(
        message: String,
        attachments: List<PlatformFile>,
        type: SettingsFeedbackType = SettingsFeedbackType.Problem,
        card: SettingsFeedbackCardDraft? = null,
    ): SettingsFeedbackProxyAttempt =
        SettingsFeedbackProxyAttempt(message = message, attachments = attachments, submissionId = newSubmissionId(), type = type, card = card)

    suspend fun submit(attempt: SettingsFeedbackProxyAttempt): SettingsFeedbackProxyOutcome {
        // The route refuses the whole feedback for one non-image file (web's form takes images
        // only): say so before uploading anything, and keep the draft for the person to fix.
        val unsupported = attempt.attachments.count { it.productFeedbackImageContentType() == null }
        if (unsupported > 0) {
            logger("settings_feedback_submit_blocked reason=attachment_type count=$unsupported")
            return SettingsFeedbackProxyOutcome.Failed(SETTINGS_FEEDBACK_ATTACHMENT_TYPE_INVALID, retryable = false)
        }
        val prepared =
            if (attempt.diagnosticsPrepared) {
                attempt
            } else {
                val ref = diagnostics?.let { uploader -> uploadDiagnostics(uploader) }
                logger("settings_feedback_diagnostics_prepared attached=${ref != null}")
                attempt.copy(diagnosticsPrepared = true, diagnostics = ref)
            }
        val first = repository.submit(prepared.toRequest())
        if (first is FeedbackResult.Failed && prepared.diagnostics != null && first.failure.code in DiagnosticsRefusedCodes) {
            // A new submission id: Hands keyed the refused one to a payload with the archive.
            val without = prepared.copy(diagnostics = null, submissionId = newSubmissionId())
            logger("settings_feedback_submit_retry_without_diagnostics code=${first.failure.code}")
            return outcome(without, repository.submit(without.toRequest()))
        }
        return outcome(prepared, first)
    }

    private suspend fun uploadDiagnostics(uploader: SettingsFeedbackDiagnosticsUploader): FeedbackDiagnosticsReference? =
        try {
            uploader.upload(accountId)?.takeIf { it.isValid() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger("settings_feedback_diagnostics_exception type=${error::class.simpleName}")
            null
        }

    private fun outcome(sent: SettingsFeedbackProxyAttempt, result: FeedbackResult<String>): SettingsFeedbackProxyOutcome {
        val failure =
            when (result) {
                is FeedbackResult.Ok -> {
                    // They sent an agent's draft (web useFeedbackCardDialog `sent`, slock #14076).
                    if (sent.card != null) agentDraftSent.markSent(accountId)
                    return SettingsFeedbackProxyOutcome.Filed(result.value)
                }
                is FeedbackResult.Failed -> result.failure
            }
        logger("settings_feedback_submit_failed kind=${failure.kind} code=${failure.code} status=${failure.httpStatus}")
        fun failed(code: String, retry: SettingsFeedbackProxyAttempt?) =
            SettingsFeedbackProxyOutcome.Failed(
                code = code,
                retryable = retry != null,
                retryAfterSeconds = failure.retryAfterSeconds,
                retry = retry,
                traceId = failure.traceId,
            )
        if (sent.card != null) {
            FeedbackCardRefusals[failure.code]?.let { code -> return failed(code, null) }
        }
        if (failure.kind == FeedbackErrorKind.NotSupported || failure.code == "feedback_integration_unavailable") {
            // The native Hands form files anonymously and cannot complete a card: a card's send
            // says the server is unavailable instead.
            if (sent.card != null) return failed("settings_feedback_server_unavailable", sent)
            return SettingsFeedbackProxyOutcome.UseNativeForm
        }
        return when {
            failure.code == "feedback_attachment_type_invalid" -> failed(SETTINGS_FEEDBACK_ATTACHMENT_TYPE_INVALID, null)
            failure.code == "feedback_attachment_too_large" -> failed("settings_feedback_attachment_too_large", null)
            failure.kind == FeedbackErrorKind.RateLimited -> failed("settings_feedback_rate_limited", sent)
            // Hands holds this submission id for another payload: a retry is a new submission.
            failure.kind == FeedbackErrorKind.Conflict -> failed("settings_feedback_submit_failed", sent.copy(submissionId = newSubmissionId()))
            failure.kind == FeedbackErrorKind.Unauthorized -> failed("settings_feedback_auth_failed", null)
            failure.kind == FeedbackErrorKind.Invalid -> failed("settings_feedback_submit_failed", null)
            failure.httpStatus == 0 -> failed("settings_feedback_network_failed", sent)
            else -> failed("settings_feedback_server_unavailable", sent)
        }
    }

    private fun SettingsFeedbackProxyAttempt.toRequest(): SettingsFeedbackSubmitRequest =
        SettingsFeedbackSubmitRequest(
            message = message,
            attachments = attachments,
            submissionId = submissionId,
            locale = locale(),
            diagnostics = diagnostics,
            type = type,
            card = card,
        )
}

@OptIn(ExperimentalUuidApi::class)
private fun newSettingsFeedbackSubmissionId(): String = Uuid.random().toString()
