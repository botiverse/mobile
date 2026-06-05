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
    val serverUnreadCount: Int = server.unreadCount
)

fun HomeContent.withDraftIndicators(draftThreadIds: Set<String>): HomeContent {
    if (draftThreadIds.isEmpty()) return this.clearDraftIndicators()
    return copy(
        channels = channels.map { it.withDraftIndicator(draftThreadIds) },
        directMessages = directMessages.map { it.withDraftIndicator(draftThreadIds) }
    )
}

fun HomeContent.defaultSelectedChannelId(): String? =
    channels.firstOrNull { it.permissions.canRead }
        ?.threadId
        ?.value
        ?: channels.firstOrNull()?.threadId?.value

private fun HomeContent.clearDraftIndicators(): HomeContent =
    copy(
        channels = channels.map { it.copy(hasDraft = false) },
        directMessages = directMessages.map { it.copy(hasDraft = false) }
    )

private fun ConversationSummary.withDraftIndicator(draftThreadIds: Set<String>): ConversationSummary =
    copy(
        hasDraft =
            threadId.value in draftThreadIds ||
        channelId?.value?.let { it in draftThreadIds } == true
    )
