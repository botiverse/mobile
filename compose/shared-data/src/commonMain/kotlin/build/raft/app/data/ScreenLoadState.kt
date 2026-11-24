package build.raft.app.data

sealed interface ScreenLoadState<out T> {
    data object Loading : ScreenLoadState<Nothing>
    data class Content<T>(val data: T) : ScreenLoadState<T>
    data class Empty(val reason: EmptyReason) : ScreenLoadState<Nothing>
    data class Error(val error: ScreenError) : ScreenLoadState<Nothing>
    data class NoPermission(val blocker: PermissionBlocker) : ScreenLoadState<Nothing>
}
