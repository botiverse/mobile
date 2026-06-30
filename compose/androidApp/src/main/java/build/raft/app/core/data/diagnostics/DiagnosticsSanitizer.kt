package build.raft.app.core.data.diagnostics

object DiagnosticsSanitizer {
    fun sanitize(value: String): String {
        var sanitized = value
        SensitivePatterns.forEach { pattern ->
            sanitized = pattern.replace(sanitized) { match ->
                val prefix = match.groups[1]?.value.orEmpty()
                "$prefix[redacted]"
            }
        }
        return sanitized
    }

    private val SensitivePatterns = listOf(
        Regex("""(?i)\b(authorization\s*[:=]\s*bearer\s+)[^\s,;]+"""),
        Regex("""(?i)\b(bearer\s+)[^\s,;]+"""),
        Regex("""(?i)\b(access[_-]?token\s*[:=]\s*)[^\s,;]+"""),
        Regex("""(?i)\b(refresh[_-]?token\s*[:=]\s*)[^\s,;]+"""),
        Regex("""(?i)\b(password\s*[:=]\s*)[^\s,;]+"""),
        Regex("""(?i)\b(raw\s+(?:response|body)\s*[:=]\s*).+"""),
        Regex("""\b((?:eyJ)[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.)[A-Za-z0-9_-]+\b"""),
        Regex("""\b([A-Za-z0-9._%+-]{2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b""")
    )
}
