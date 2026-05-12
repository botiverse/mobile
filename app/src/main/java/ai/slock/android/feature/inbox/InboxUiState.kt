package ai.slock.android.feature.inbox

import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.state.ScreenLoadState

data class InboxUiState(
    val loadState: ScreenLoadState<InboxContent>,
    val selectedFilter: InboxFilter = InboxFilter.All,
    val focusedItemKey: String? = null,
    val loadingMore: Boolean = false,
    val nextOffset: Int? = null,
    val previousContent: InboxContent? = null
)

data class InboxContent(
    val server: ServerSummary,
    val items: List<InboxItem>,
    val totalCount: Int = items.size,
    val totalUnreadCount: Int = items.sumOf { it.unreadCount },
    val hasMore: Boolean = false
)

enum class InboxFilter {
    All,
    Unread
}

fun InboxUiState.withFilterLoading(
    filter: InboxFilter,
    renderCache: InboxContent? = null
): InboxUiState =
    copy(
        loadState = ScreenLoadState.Loading,
        selectedFilter = filter,
        focusedItemKey = null,
        loadingMore = false,
        nextOffset = null,
        previousContent = renderCache
    )

fun InboxUiState.withBodyLoading(
    filter: InboxFilter = selectedFilter,
    renderCache: InboxContent? = null
): InboxUiState =
    InboxUiState(
        loadState = ScreenLoadState.Loading,
        selectedFilter = filter,
        previousContent = renderCache
    )

fun InboxUiState.withSilentRefresh(
    filter: InboxFilter = selectedFilter,
    renderCache: InboxContent? = null
): InboxUiState =
    copy(
        selectedFilter = filter,
        focusedItemKey = null,
        loadingMore = false,
        previousContent = renderCache
    )
