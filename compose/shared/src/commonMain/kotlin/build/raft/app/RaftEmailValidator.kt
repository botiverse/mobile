package build.raft.app

internal const val RAFT_EMAIL_FORMAT_PATTERN = """^[^\s@]+@[^\s@]+\.[^\s@]+$"""

private val RaftEmailFormatRegex = Regex(RAFT_EMAIL_FORMAT_PATTERN)

internal fun isValidAuthEmailFormat(email: String): Boolean =
    RaftEmailFormatRegex.matches(email.trim())
