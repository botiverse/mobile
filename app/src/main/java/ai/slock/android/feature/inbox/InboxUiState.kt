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

fun InboxUiState.withFilterLoading(filter: InboxFilter): InboxUiState =
    copy(
        loadState = ScreenLoadState.Loading,
        selectedFilter = filter,
        focusedItemKey = null,
        loadingMore = false,
        nextOffset = null,
        previousContent = (loadState as? ScreenLoadState.Content)?.data ?: previousContent
    )

fun InboxUiState.withBodyLoading(filter: InboxFilter = selectedFilter): InboxUiState =
    InboxUiState(
        loadState = ScreenLoadState.Loading,
        selectedFilter = filter,
        previousContent = (loadState as? ScreenLoadState.Content)?.data ?: previousContent
    )
