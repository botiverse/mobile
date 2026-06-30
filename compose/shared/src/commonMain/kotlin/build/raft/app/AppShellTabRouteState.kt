package build.raft.app

import com.tencent.kuikly.compose.foundation.lazy.LazyListState

internal data class AppShellLazyListPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
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

internal data class AppShellMembersListState(
    val listPosition: AppShellLazyListPosition = AppShellLazyListPosition(),
    val agentsExpanded: Boolean = true,
    val humansExpanded: Boolean = true
)
