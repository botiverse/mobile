package build.raft.app.data

sealed interface DataResult<out T> {
    data class Content<T>(val data: T) : DataResult<T>
    data class Empty(val reason: EmptyReason) : DataResult<Nothing>
    data class Error(val error: ScreenError) : DataResult<Nothing>
    data class NoPermission(val blocker: PermissionBlocker) : DataResult<Nothing>
}

enum class EmptyReason {
    NoChannels,
    NoMessages,
    NoTasks,
    NoMembers,
    NoInboxItems,
    NoSettingsAvailable,
    NoSearchResults
}

data class ScreenError(
    val code: String,
    val retryable: Boolean = true,
    /**
     * task #193 (honest-states contract): the server's raw human-readable
     * error text, carried so displays can always fall back to showing the
     * real reason instead of a bare code. Humane localized special-cases
     * remain overlays on top of this fallback, never a replacement for it.
     */
    val serverMessage: String? = null
)

data class PermissionBlocker(
    val code: String,
    val requiredCapability: String? = null
)

fun PermissionBlocker.isAuthSessionBlocker(): Boolean =
    code == "auth_required" || requiredCapability == "auth_session"

fun ScreenError.isAuthSessionError(): Boolean =
    code == "auth_required" || code == "auth_session" || code == "unauthenticated"

fun String?.isAuthSessionErrorCode(): Boolean =
    this == "auth_required" || this == "auth_session" || this == "unauthenticated"

internal fun ScreenError.userFacingMessage(): String =
    // task #193 honest-states contract: known categories keep their humane
    // copy (overlay); anything unrecognized falls back to the server's raw
    // error text; only when neither exists do we show the generic sentence.
    // A bare code is never the user-facing message.
    code.userFacingErrorCodeMessageOrNull(
        authMessage = "Your session expired. Sign in again to continue."
    )
        ?: serverMessage?.takeIf { it.isNotBlank() }
        ?: "Something went wrong. Please try again."

internal fun PermissionBlocker.userFacingMessage(): String =
    code.userFacingErrorCodeMessage(
        authMessage = "Your session expired. Sign in again to continue.",
        permissionMessage = "You do not have permission to view this page."
    )

internal fun EmptyReason.userFacingMessage(): String =
    when (this) {
        EmptyReason.NoChannels -> "No channels are available yet."
        EmptyReason.NoMessages -> "No messages are available yet."
        EmptyReason.NoTasks -> "No tasks are available yet."
        EmptyReason.NoMembers -> "No members are available yet."
        EmptyReason.NoInboxItems -> "No activity is available yet."
        EmptyReason.NoSettingsAvailable -> "These settings are not available right now."
        EmptyReason.NoSearchResults -> "No results matched your search."
    }

internal fun String?.userFacingErrorCodeMessage(
    authMessage: String = "Please sign in again to continue.",
    permissionMessage: String = "You do not have permission to perform this action."
): String =
    userFacingErrorCodeMessageOrNull(authMessage = authMessage, permissionMessage = permissionMessage)
        ?: "Something went wrong. Please try again."

internal fun String?.userFacingErrorCodeMessageOrNull(
    authMessage: String = "Please sign in again to continue.",
    permissionMessage: String = "You do not have permission to perform this action."
): String? {
    val normalized = this?.trim().orEmpty()
    if (normalized.isBlank()) return null
    if (!normalized.looksLikeErrorCode()) return null
    val lower = normalized.lowercase()
    return when {
        lower.isAuthSessionErrorCode() || "auth" in lower || "unauthenticated" in lower -> authMessage
        "dns" in lower || "host" in lower || "unreachable" in lower ->
            "The server could not be reached. Check your connection and try again."
        "timeout" in lower || "timed_out" in lower ->
            "The server did not respond in time. Please try again."
        "network" in lower || "socket" in lower || lower == "code_0" || lower == "0" ->
            "The network connection failed. Check your connection and try again."
        "server_unavailable" in lower || "http_5" in lower || lower.endsWith("_503") ->
            "The server is unavailable right now. Please try again shortly."
        "not_found" in lower || "http_404" in lower ->
            "This item could not be found. It may have been moved or deleted."
        "permission" in lower || "forbidden" in lower || "admin_required" in lower || "no_permission" in lower ->
            permissionMessage
        "too_large" in lower || "attachment_size" in lower ->
            "The attachment is too large. Choose a smaller file and try again."
        "rate" in lower || "too_many" in lower ->
            "Too many attempts. Please wait a moment and try again."
        "contract" in lower || "parse" in lower || "mismatch" in lower ->
            "The response could not be read. Please try again."
        else -> null
    }
}

private fun String.looksLikeErrorCode(): Boolean =
    matches(Regex("[a-z0-9_]+")) ||
        matches(Regex("[a-zA-Z]+_[a-zA-Z0-9_]+")) ||
        matches(Regex("http_\\d+")) ||
        matches(Regex("\\d+"))

fun authSessionRequiredResult(): DataResult.NoPermission =
    DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))

inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> =
    when (this) {
        is DataResult.Content -> DataResult.Content(transform(data))
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }

fun <T> T.asDataResult(): DataResult<T> = DataResult.Content(this)
