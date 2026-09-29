package build.raft.app.network

/**
 * Redacted, single-token diagnostics for transport failures. Never includes
 * tokens, passwords, JWTs, full emails, or raw bodies.
 */
private fun Throwable.safeDiagnosticsName(): String =
    this::class.simpleName ?: "Exception"

internal fun Throwable.safeDiagnosticsSummary(): String =
    buildString {
        append("reason=")
        append(safeDiagnosticsName())
        cause
            ?.safeDiagnosticsName()
            ?.takeUnless { it == safeDiagnosticsName() }
            ?.let { causeName ->
                append(" cause=")
                append(causeName)
            }
        listOfNotNull(message, cause?.message)
            .firstOrNull { it.isNotBlank() }
            ?.toSafeDiagnosticsToken()
            ?.let { detail ->
                append(" detail=")
                append(detail)
            }
    }

internal fun String.toSafeDiagnosticsToken(): String {
    val redacted =
        SensitiveNetworkDiagnosticsPatterns.fold(this) { current, pattern ->
            current.replace(pattern, "$1[redacted]")
        }.redactUrlQueries()
    return redacted
        .replace(Regex("""\s+"""), "_")
        .filter { char ->
            char.isLetterOrDigit() ||
                char == '_' ||
                char == '-' ||
                char == '.' ||
                char == ':' ||
                char == '/' ||
                char == '=' ||
                char == '[' ||
                char == ']'
        }
        .take(180)
        .ifBlank { "blank" }
}

/**
 * Remove bearer-style object URLs before an error can cross the transport/data boundary.  A
 * presigned URL is sensitive even when it appears only in an exception message or an HTTP error
 * envelope.  Keep this helper intentionally conservative for ordinary diagnostics: URLs without
 * a query remain readable (for example connection origins), while any URL carrying a query is
 * replaced wholesale.  Callers handling a known presigned response use [redactPresignedUrlText]
 * directly, which redacts every HTTP(S) URL.
 */
internal fun String.redactPresignedUrlText(): String =
    PresignedUrlPattern.replace(this) { "[redacted-url]" }

internal fun String.redactNetworkErrorText(): String =
    redactPresignedUrlText()
        .replace(EncodedPresignedUrlPattern, "[redacted-url]")
        .replace(SignedQueryValuePattern, "$1[redacted]")

private fun String.redactUrlQueries(): String =
    UrlWithQueryPattern.replace(this) { match ->
        "${match.groupValues[1]}[redacted-query]"
    }

private val PresignedUrlPattern =
    Regex("https?://[^\\s\\\"'<>\\]}]+", RegexOption.IGNORE_CASE)

private val UrlWithQueryPattern =
    Regex("(https?://[^\\s\\\"'<>\\]}?]+)\\?[^\\s\\\"'<>\\]}]*", RegexOption.IGNORE_CASE)

private val EncodedPresignedUrlPattern =
    Regex("https?%3a%2f%2f[^\\s\\\"'<>\\]}]+", RegexOption.IGNORE_CASE)

private val SignedQueryValuePattern =
    Regex("((?:X-Amz-(?:Signature|Credential|Security-Token)|Signature|sig|token)=)[^&\\s\\\"'<>]+", RegexOption.IGNORE_CASE)

private val SensitiveNetworkDiagnosticsPatterns =
    listOf(
        Regex("""(\bauthorization\s*[:=]\s*bearer\s+)[^\s,;]+""", RegexOption.IGNORE_CASE),
        Regex("""(\bbearer\s+)[^\s,;]+""", RegexOption.IGNORE_CASE),
        Regex("""(\baccess[_-]?token\s*[:=]\s*)[^\s,;]+""", RegexOption.IGNORE_CASE),
        Regex("""(\brefresh[_-]?token\s*[:=]\s*)[^\s,;]+""", RegexOption.IGNORE_CASE),
        Regex("""(\bpassword\s*[:=]\s*)[^\s,;]+""", RegexOption.IGNORE_CASE),
        Regex("""(\braw\s+(?:response|body)\s*[:=]\s*).+""", RegexOption.IGNORE_CASE),
        Regex("""\b((?:eyJ)[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.)[A-Za-z0-9_-]+\b"""),
        Regex("""\b([A-Za-z0-9._%+-]{2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b""")
    )
