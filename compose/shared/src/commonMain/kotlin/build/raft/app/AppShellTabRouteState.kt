package build.raft.app

import com.tencent.kuikly.compose.foundation.lazy.LazyListState

internal data class AppShellLazyListPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)

internal data class AppShellServerScopedLazyListPosition(
    val serverId: String? = null,
    val listPosition: AppShellLazyListPosition = AppShellLazyListPosition()
)

internal fun appShellLazyListPosition(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int
): AppShellLazyListPosition =
    AppShellLazyListPosition(
        firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0)
    )

internal fun LazyListState.toAppShellLazyListPosition(): AppShellLazyListPosition =
    appShellLazyListPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)

internal fun appShellServerScopedLazyListPosition(
    serverId: String?,
    listPosition: AppShellLazyListPosition
): AppShellServerScopedLazyListPosition =
    AppShellServerScopedLazyListPosition(
        serverId = serverId?.trim()?.takeIf { it.isNotEmpty() },
        listPosition = listPosition
    )

internal fun appShellHomeListServerId(
    renderedServerId: String?,
    readyServerId: String?
): String? =
    renderedServerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: readyServerId?.trim()?.takeIf { it.isNotEmpty() }

internal fun appShellLazyListPositionForServer(
    currentServerId: String?,
    savedPosition: AppShellServerScopedLazyListPosition
): AppShellLazyListPosition {
    val current = currentServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return AppShellLazyListPosition()
    return if (savedPosition.serverId == current) {
        savedPosition.listPosition
    } else {
        AppShellLazyListPosition()
    }
}

internal data class AppShellMembersListState(
    val listPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    val agentsExpanded: Boolean = true,
    val humansExpanded: Boolean = true
)
