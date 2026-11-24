package build.raft.app

import build.raft.app.network.RaftRequestTimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val ThreadFailureReasonCoreJson = Json {
    ignoreUnknownKeys = true
}

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
    runCatching { ThreadFailureReasonCoreJson.parseToJsonElement(this).jsonObject }.getOrNull()

private fun JsonObject.threadFailureStringField(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        get(name)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
