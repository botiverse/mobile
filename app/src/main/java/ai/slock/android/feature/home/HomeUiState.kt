package ai.slock.android.feature.home

import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.state.ScreenLoadState

data class HomeUiState(
    val loadState: ScreenLoadState<HomeContent>,
    val selectedServerId: String? = null,
    val selectedThreadId: String? = null,
    val searchQuery: String = ""
)

data class HomeContent(
    val server: ServerSummary,
    val currentMember: MemberProfile,
    val channels: List<ConversationSummary>,
    val directMessages: List<ConversationSummary>,
    val unreadTotal: Int,
    val inboxUnreadCount: Int = unreadTotal,
    val savedUnreadCount: Int = 0
)
