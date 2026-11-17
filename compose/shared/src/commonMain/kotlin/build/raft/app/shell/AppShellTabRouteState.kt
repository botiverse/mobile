package build.raft.app

import androidx.compose.foundation.lazy.LazyListState

internal data class AppShellLazyListPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)

internal data class AppShellServerLazyListPositions(
    val positionsByServerId: Map<String, AppShellLazyListPosition> = emptyMap()
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

internal fun AppShellServerLazyListPositions.withPosition(
    serverId: String?,
    listPosition: AppShellLazyListPosition
): AppShellServerLazyListPositions {
    val normalizedServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    return copy(positionsByServerId = positionsByServerId + (normalizedServerId to listPosition))
}

internal fun appShellHomeListServerId(
    renderedServerId: String?,
    readyServerId: String?
): String? =
    renderedServerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: readyServerId?.trim()?.takeIf { it.isNotEmpty() }

internal fun appShellLazyListPositionForServer(
    currentServerId: String?,
    savedPositions: AppShellServerLazyListPositions
): AppShellLazyListPosition {
    val current = currentServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return AppShellLazyListPosition()
    return savedPositions.positionsByServerId[current] ?: AppShellLazyListPosition()
}

internal data class AppShellMembersListState(
    val listPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    val agentsExpanded: Boolean = true,
    val humansExpanded: Boolean = true
)
