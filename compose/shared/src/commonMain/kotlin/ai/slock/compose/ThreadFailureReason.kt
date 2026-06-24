package ai.slock.compose

import ai.slock.compose.network.SlockRequestTimeoutException

private const val ThreadFailureReasonMaxLength = 120

private val ThreadFailureReasonKnownLabels =
    mapOf(
        "thread_send_timeout" to "request timed out",
        "thread_send_cancelled" to "request cancelled",
        "thread_send_network" to "network error",
        "thread_send_exception" to "unexpected error"
    )

internal fun threadSendFailureStatusLabel(errorCode: String?): String {
    val reason = threadFailureReasonLabel(errorCode)
    return if (reason == null) {
        "Send failed · Retry"
    } else {
        "Send failed: $reason · Retry"
    }
}

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

internal fun threadSendExceptionErrorCode(throwable: Throwable): String {
    val type = throwable::class.simpleName.orEmpty()
    val message = throwable.message.orEmpty()
    return when {
        throwable is SlockRequestTimeoutException -> "thread_send_timeout"
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

private fun redactThreadFailureReason(value: String): String =
    value
        .replace(Regex("sk_agent_[A-Za-z0-9_=-]+"), "sk_agent_<redacted>")
        .replace(Regex("sk_machine_[A-Za-z0-9_=-]+"), "sk_machine_<redacted>")
        .replace(Regex("Bearer\\s+[A-Za-z0-9._=-]+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
