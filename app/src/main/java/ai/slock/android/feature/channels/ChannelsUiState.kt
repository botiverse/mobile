package ai.slock.android.feature.channels

import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.state.ScreenLoadState

data class ChannelsUiState(
    val loadState: ScreenLoadState<ChannelsContent>,
    val searchQuery: String = "",
    val showArchived: Boolean = false
)

data class ChannelsContent(
    val server: ServerSummary,
    val channels: List<ConversationSummary>
)

