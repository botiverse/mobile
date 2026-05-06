package ai.slock.android.core.state

sealed interface ScreenLoadState<out T> {
    data object Loading : ScreenLoadState<Nothing>
    data class Content<T>(val data: T) : ScreenLoadState<T>
    data class Empty(val reason: EmptyReason) : ScreenLoadState<Nothing>
    data class Error(val error: ScreenError) : ScreenLoadState<Nothing>
    data class NoPermission(val blocker: PermissionBlocker) : ScreenLoadState<Nothing>
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

