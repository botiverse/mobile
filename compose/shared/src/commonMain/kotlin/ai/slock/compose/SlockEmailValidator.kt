package ai.slock.compose

internal const val SLOCK_EMAIL_FORMAT_PATTERN = """^[^\s@]+@[^\s@]+\.[^\s@]+$"""
internal const val AUTH_INVALID_EMAIL_FORMAT = "Invalid email format"
internal const val AUTH_PASSWORD_REQUIRED = "Password is required"
internal const val AUTH_PASSWORD_TOO_SHORT = "Password must be at least 8 characters"

private val SlockEmailFormatRegex = Regex(SLOCK_EMAIL_FORMAT_PATTERN)

internal fun isValidAuthEmailFormat(email: String): Boolean =
    SlockEmailFormatRegex.matches(email.trim())
