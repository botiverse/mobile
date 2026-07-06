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
    val retryable: Boolean = true
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
    code.userFacingErrorCodeMessage(
        authMessage = "Your session expired. Sign in again to continue."
    )

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
): String {
    val normalized = this?.trim().orEmpty()
    if (normalized.isBlank()) return "Something went wrong. Please try again."
    if (!normalized.looksLikeErrorCode()) return "Something went wrong. Please try again."
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
        "contract" in lower || "parse" in lower || "mismatch" in lower ->
            "The response could not be read. Please try again."
        else -> "Something went wrong. Please try again."
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
