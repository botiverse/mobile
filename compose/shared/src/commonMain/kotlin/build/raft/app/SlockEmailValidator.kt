package build.raft.app

internal const val SLOCK_EMAIL_FORMAT_PATTERN = """^[^\s@]+@[^\s@]+\.[^\s@]+$"""

private val SlockEmailFormatRegex = Regex(SLOCK_EMAIL_FORMAT_PATTERN)

internal fun isValidAuthEmailFormat(email: String): Boolean =
    SlockEmailFormatRegex.matches(email.trim())
