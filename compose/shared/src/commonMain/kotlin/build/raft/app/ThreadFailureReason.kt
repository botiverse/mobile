package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.network.RaftRequestTimeoutException
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val ThreadFailureReasonMaxLength = 120

private val ThreadFailureReasonJson = Json {
    ignoreUnknownKeys = true
}

private val ThreadFailureReasonKnownLabels =
    mapOf(
        "thread_send_timeout" to "request timed out",
        "thread_send_cancelled" to "request cancelled",
        "thread_send_network" to "network error",
        "thread_send_exception" to "unexpected error"
    )

internal enum class ThreadUserFacingErrorReason {
    AuthenticationRequired,
    PermissionDenied,
    ServerUnreachable,
    RequestTimedOut,
    NetworkFailed,
    ServerUnavailable,
    ItemNotFound,
    AttachmentTooLarge,
    AttachmentImageCompressionFailed,
    RateLimited,
    ResponseUnreadable,
    RequestCancelled,
    Generic
}

private val ThreadUserFacingKnownReasons =
    mapOf(
        "thread_send_timeout" to ThreadUserFacingErrorReason.RequestTimedOut,
        "thread_send_cancelled" to ThreadUserFacingErrorReason.RequestCancelled,
        "thread_send_network" to ThreadUserFacingErrorReason.NetworkFailed,
        "thread_send_exception" to ThreadUserFacingErrorReason.Generic
    )

internal fun threadSendFailureSummary(
    errorCode: String?,
    labels: ThreadUserFacingErrorLabels
): String = labels.forErrorCode(errorCode) ?: labels.generic()

internal fun threadFailureReasonLabel(errorCode: String?): String? =
    errorCode
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() }
        ?.let { value -> ThreadFailureReasonKnownLabels[value] ?: value }
        ?.let(::redactThreadFailureReason)
        ?.let { value ->
            if (value.length <= ThreadFailureReasonMaxLength) {
                value
            } else {
                value.take(ThreadFailureReasonMaxLength - 3).trimEnd() + "..."
            }
        }

internal fun threadFailureDiagnosticDetail(
    errorCode: String?,
    errorMessage: String?
): String? {
    val code = errorCode?.trim()?.takeIf { it.isNotBlank() }
    val message = errorMessage?.trim()?.takeIf { it.isNotBlank() }
    if (code == null && message == null) return null
    return buildString {
        code?.let { append("Code: ").append(it) }
        message?.let {
            if (isNotEmpty()) append('\n')
            append("Detail: ").append(it)
        }
    }.let(::redactThreadFailureReason)
}

internal fun threadUserFacingErrorCodeReason(errorCode: String?): ThreadUserFacingErrorReason? {
    val normalized =
        errorCode
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
    ThreadUserFacingKnownReasons[normalized]?.let { return it }
    return when {
        normalized == "auth_required" ||
            normalized == "auth_session" ||
            normalized == "unauthenticated" ||
            "auth" in normalized -> ThreadUserFacingErrorReason.AuthenticationRequired
        "dns" in normalized ||
            "host" in normalized ||
            "unreachable" in normalized -> ThreadUserFacingErrorReason.ServerUnreachable
        "timeout" in normalized ||
            "timed_out" in normalized -> ThreadUserFacingErrorReason.RequestTimedOut
        "network" in normalized ||
            "socket" in normalized ||
            normalized == "code_0" ||
            normalized == "0" -> ThreadUserFacingErrorReason.NetworkFailed
        "server_unavailable" in normalized ||
            normalized == "server_missing" ||
            "http_5" in normalized ||
            normalized.endsWith("_503") -> ThreadUserFacingErrorReason.ServerUnavailable
        "not_found" in normalized ||
            "http_404" in normalized -> ThreadUserFacingErrorReason.ItemNotFound
        "permission" in normalized ||
            "forbidden" in normalized ||
            "admin_required" in normalized ||
            "no_permission" in normalized -> ThreadUserFacingErrorReason.PermissionDenied
        normalized == "attachment_too_large" ||
            normalized == "upload_too_large" -> ThreadUserFacingErrorReason.AttachmentTooLarge
        normalized == "attachment_image_compression_failed" ->
            ThreadUserFacingErrorReason.AttachmentImageCompressionFailed
        "rate" in normalized ||
            "too_many" in normalized -> ThreadUserFacingErrorReason.RateLimited
        "contract" in normalized ||
            "parse" in normalized ||
            "mismatch" in normalized -> ThreadUserFacingErrorReason.ResponseUnreadable
        else -> ThreadUserFacingErrorReason.Generic
    }
}

internal fun ThreadUserFacingErrorReason.stringDesc(): StringDesc =
    when (this) {
        ThreadUserFacingErrorReason.AuthenticationRequired -> MR.strings.thread_error_reason_auth.desc()
        ThreadUserFacingErrorReason.PermissionDenied -> MR.strings.thread_error_reason_permission.desc()
        ThreadUserFacingErrorReason.ServerUnreachable -> MR.strings.thread_error_reason_server_unreachable.desc()
        ThreadUserFacingErrorReason.RequestTimedOut -> MR.strings.thread_error_reason_timeout.desc()
        ThreadUserFacingErrorReason.NetworkFailed -> MR.strings.thread_error_reason_network.desc()
        ThreadUserFacingErrorReason.ServerUnavailable -> MR.strings.thread_error_reason_server_unavailable.desc()
        ThreadUserFacingErrorReason.ItemNotFound -> MR.strings.thread_error_reason_not_found.desc()
        ThreadUserFacingErrorReason.AttachmentTooLarge -> MR.strings.thread_error_reason_attachment_too_large.desc()
        ThreadUserFacingErrorReason.AttachmentImageCompressionFailed ->
            MR.strings.thread_error_reason_attachment_image_compression_failed.desc()
        ThreadUserFacingErrorReason.RateLimited -> MR.strings.thread_error_reason_rate_limited.desc()
        ThreadUserFacingErrorReason.ResponseUnreadable -> MR.strings.thread_error_reason_response_unreadable.desc()
        ThreadUserFacingErrorReason.RequestCancelled -> MR.strings.thread_error_reason_cancelled.desc()
        ThreadUserFacingErrorReason.Generic -> MR.strings.thread_error_reason_generic.desc()
    }

internal class ThreadUserFacingErrorLabels(
    private val labels: Map<ThreadUserFacingErrorReason, String>
) {
    init {
        require(labels.keys == ThreadUserFacingErrorReason.entries.toSet())
    }

    fun forErrorCode(errorCode: String?): String? =
        threadUserFacingErrorCodeReason(errorCode)?.let(labels::get)

    fun forReason(reason: ThreadUserFacingErrorReason): String = checkNotNull(labels[reason])

    fun generic(): String = checkNotNull(labels[ThreadUserFacingErrorReason.Generic])
}

@Composable
internal fun threadUserFacingErrorLabels(): ThreadUserFacingErrorLabels =
    ThreadUserFacingErrorLabels(
        mapOf(
            ThreadUserFacingErrorReason.AuthenticationRequired to
                ThreadUserFacingErrorReason.AuthenticationRequired.stringDesc().localized(),
            ThreadUserFacingErrorReason.PermissionDenied to
                ThreadUserFacingErrorReason.PermissionDenied.stringDesc().localized(),
            ThreadUserFacingErrorReason.ServerUnreachable to
                ThreadUserFacingErrorReason.ServerUnreachable.stringDesc().localized(),
            ThreadUserFacingErrorReason.RequestTimedOut to
                ThreadUserFacingErrorReason.RequestTimedOut.stringDesc().localized(),
            ThreadUserFacingErrorReason.NetworkFailed to
                ThreadUserFacingErrorReason.NetworkFailed.stringDesc().localized(),
            ThreadUserFacingErrorReason.ServerUnavailable to
                ThreadUserFacingErrorReason.ServerUnavailable.stringDesc().localized(),
            ThreadUserFacingErrorReason.ItemNotFound to
                ThreadUserFacingErrorReason.ItemNotFound.stringDesc().localized(),
            ThreadUserFacingErrorReason.AttachmentTooLarge to
                ThreadUserFacingErrorReason.AttachmentTooLarge.stringDesc().localized(),
            ThreadUserFacingErrorReason.AttachmentImageCompressionFailed to
                ThreadUserFacingErrorReason.AttachmentImageCompressionFailed.stringDesc().localized(),
            ThreadUserFacingErrorReason.RateLimited to
                ThreadUserFacingErrorReason.RateLimited.stringDesc().localized(),
            ThreadUserFacingErrorReason.ResponseUnreadable to
                ThreadUserFacingErrorReason.ResponseUnreadable.stringDesc().localized(),
            ThreadUserFacingErrorReason.RequestCancelled to
                ThreadUserFacingErrorReason.RequestCancelled.stringDesc().localized(),
            ThreadUserFacingErrorReason.Generic to
                ThreadUserFacingErrorReason.Generic.stringDesc().localized()
        )
    )

internal fun threadSendExceptionErrorCode(throwable: Throwable): String {
    val type = throwable::class.simpleName.orEmpty()
    val message = throwable.message.orEmpty()
    return when {
        throwable is RaftRequestTimeoutException -> "thread_send_timeout"
        type.contains("Timeout", ignoreCase = true) -> "thread_send_timeout"
        type.contains("Cancel", ignoreCase = true) ||
            message.contains("cancel", ignoreCase = true) ->
            "thread_send_cancelled"
        type.equals("IOException", ignoreCase = true) ||
            type.endsWith("IOException", ignoreCase = true) ||
            type.contains("Network", ignoreCase = true) ->
            "thread_send_network"
        else -> "thread_send_exception"
    }
}

internal fun threadSendExceptionErrorMessage(throwable: Throwable): String? =
    throwable.message?.takeIf { it.isNotBlank() }

internal fun threadSendShouldSilentlyRetry(errorCode: String?, httpCode: Int? = null): Boolean {
    if (httpCode == 408 || httpCode == 429 || httpCode in 500..599) return true
    return when (errorCode?.trim()) {
        "thread_send_timeout",
        "thread_send_network",
        "thread_send_exception" -> true
        else -> false
    }
}

internal fun threadSendFailureErrorCode(
    contractCode: String?,
    errorBodyText: String?,
    httpCode: Int
): String =
    contractCode
        ?: threadFailureDiagnosticCode(errorBodyText)
        ?: threadSendTransportFailureErrorCode(errorBodyText, httpCode)
        ?: "http_$httpCode"

private fun threadSendTransportFailureErrorCode(errorBodyText: String?, httpCode: Int): String? {
    if (httpCode != 0) return null
    val cleanBody = errorBodyText?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val transportError =
        cleanBody
            .threadFailureJsonObjectOrNull()
            ?.threadFailureStringField("error")
            ?.trim()
    if (transportError.equals("network_timeout", ignoreCase = true)) {
        return "thread_send_timeout"
    }
    val curlCode =
        ThreadSendConnectionLostCurlCode
            .find(cleanBody)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    return if (curlCode != null && curlCode > 0) "thread_send_network" else null
}

private val ThreadSendConnectionLostCurlCode =
    Regex("""^\[connection_lost]\s*[_-]?\s*CURLcode\s*:\s*([1-9][0-9]*)""", RegexOption.IGNORE_CASE)

internal class ThreadSendAttemptState(
    randomId: String?,
    private val maxSilentRetries: Int
) {
    val randomId: String? = randomId?.takeIf { it.isNotBlank() }

    var attempt: Int = 1
        private set

    fun advanceForSilentRetry(eligible: Boolean): Boolean {
        if (!eligible || randomId == null || attempt > maxSilentRetries) return false
        attempt += 1
        return true
    }
}

internal fun threadFailureReasonMessage(errorBodyText: String?): String? {
    val cleanBody = errorBodyText?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val bodyObject = cleanBody.threadFailureJsonObjectOrNull()
    if (bodyObject != null) {
        return bodyObject.threadFailureStringField(
            "errorMessage",
            "message",
            "detail",
            "error_description"
        )
    }
    return cleanBody
}

internal fun threadFailureDiagnosticCode(errorBodyText: String?): String? =
    errorBodyText
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.threadFailureJsonObjectOrNull()
        ?.threadFailureStringField("code", "errorCode", "diagnosticCode")

internal fun redactThreadFailureReason(value: String): String =
    value
        .replace(Regex("sk_agent_[A-Za-z0-9_=-]+"), "sk_agent_<redacted>")
        .replace(Regex("sk_machine_[A-Za-z0-9_=-]+"), "sk_machine_<redacted>")
        .replace(Regex("Bearer\\s+[A-Za-z0-9._=-]+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
        .replace(
            Regex("(?i)((?:authorization)\\s*[:=]\\s*)(?:(?:basic|bearer)\\s+)?([^\\s,;]+)"),
            "$1<redacted>"
        )
        .replace(
            Regex("(?i)((?:cookie|set-cookie)\\s*[:=]\\s*)([^\\s,]+)"),
            "$1<redacted>"
        )
        .replace(
            Regex("(?i)((?:x-api-key|api[_-]?key)\\s*[:=]\\s*)([^\\s,;]+)"),
            "$1<redacted>"
        )
        .replace(
            Regex("(?i)([?&](?:access_token|token|api_key|apikey|signature|sig)=)([^&\\s]+)"),
            "$1<redacted>"
        )
        .replace(
            Regex("(?<![A-Za-z0-9])/(?:Users|home|var|tmp|private|data)/[^\\s,;]+"),
            "<absolute-path-redacted>"
        )
        .replace(
            Regex("[A-Za-z]:\\\\[^\\s,;]+"),
            "<absolute-path-redacted>"
        )

private fun String.threadFailureJsonObjectOrNull(): JsonObject? =
    runCatching { ThreadFailureReasonJson.parseToJsonElement(this).jsonObject }.getOrNull()

private fun JsonObject.threadFailureStringField(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        get(name)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
