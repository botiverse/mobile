package build.raft.app.data

import build.raft.app.FeedbackDiagnosticsReference
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

/** One `POST /api/product-feedback`. Safe to resend with the same [submissionId]: Hands dedupes on it. */
data class SettingsFeedbackSubmitRequest(
    val message: String,
    val attachments: List<PlatformFile>,
    val submissionId: String,
    /** The UI locale (`en`, `zh-CN`, `ja`), the form's `metadata.locale`. */
    val locale: String,
    val diagnostics: FeedbackDiagnosticsReference? = null,
)

interface SettingsFeedbackSubmitRepository {
    /** The new ticket's id. */
    suspend fun submit(request: SettingsFeedbackSubmitRequest): FeedbackResult<String>
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
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logger("settings_feedback_submit_exception type=${error::class.simpleName}")
                HomeNetworkResponse(code = 0, bodyText = null)
            }
        logger(
            "settings_feedback_submit code=${response.code} attachments=${files.size} " +
                "diagnostics=${request.diagnostics != null}"
        )
        if (!response.isSuccessful) {
            val failure = feedbackFailureOf(response.code, response.errorBodyText ?: response.bodyText)
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
}

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
        // The Settings form has no idea/problem choice; the native form filed every one as a bug.
        put("type", "problem")
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
    ) : SettingsFeedbackProxyOutcome
}

/** The Settings form's codes for a failure it explains (SettingsHandsFeedbackCopy.submissionFailure). */
internal const val SETTINGS_FEEDBACK_ATTACHMENT_TYPE_INVALID = "settings_feedback_attachment_type_invalid"

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
) {
    fun newAttempt(message: String, attachments: List<PlatformFile>): SettingsFeedbackProxyAttempt =
        SettingsFeedbackProxyAttempt(message = message, attachments = attachments, submissionId = newSubmissionId())

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
                is FeedbackResult.Ok -> return SettingsFeedbackProxyOutcome.Filed(result.value)
                is FeedbackResult.Failed -> result.failure
            }
        logger("settings_feedback_submit_failed kind=${failure.kind} code=${failure.code} status=${failure.httpStatus}")
        if (failure.kind == FeedbackErrorKind.NotSupported || failure.code == "feedback_integration_unavailable") {
            return SettingsFeedbackProxyOutcome.UseNativeForm
        }
        fun failed(code: String, retry: SettingsFeedbackProxyAttempt?) =
            SettingsFeedbackProxyOutcome.Failed(
                code = code,
                retryable = retry != null,
                retryAfterSeconds = failure.retryAfterSeconds,
                retry = retry,
            )
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
        )
}

@OptIn(ExperimentalUuidApi::class)
private fun newSettingsFeedbackSubmissionId(): String = Uuid.random().toString()
