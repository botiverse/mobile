package ai.slock.android.feature.inbox

import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.state.ScreenLoadState

data class InboxUiState(
    val loadState: ScreenLoadState<InboxContent>,
    val selectedFilter: InboxFilter = InboxFilter.All,
    val focusedItemKey: String? = null,
    val loadingMore: Boolean = false,
    val nextOffset: Int? = null
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
