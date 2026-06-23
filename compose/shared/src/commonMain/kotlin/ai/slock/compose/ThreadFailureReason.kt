package ai.slock.compose

private const val ThreadFailureReasonMaxLength = 120

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
        ?.let(::redactThreadFailureReason)
        ?.let { value ->
            if (value.length <= ThreadFailureReasonMaxLength) {
                value
            } else {
                value.take(ThreadFailureReasonMaxLength - 3).trimEnd() + "..."
            }
        }

private fun redactThreadFailureReason(value: String): String =
    value
        .replace(Regex("sk_agent_[A-Za-z0-9_=-]+"), "sk_agent_<redacted>")
        .replace(Regex("sk_machine_[A-Za-z0-9_=-]+"), "sk_machine_<redacted>")
        .replace(Regex("Bearer\\s+[A-Za-z0-9._=-]+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
