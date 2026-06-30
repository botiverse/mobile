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
