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
        }
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
