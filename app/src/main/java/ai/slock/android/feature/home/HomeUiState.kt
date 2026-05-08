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

fun resolveHomeShellContent(
    currentContent: HomeContent?,
    lastVisibleContent: HomeContent?,
    homeLoadState: ScreenLoadState<HomeContent>,
    homeRefreshInProgress: Boolean,
    fallbackContent: HomeContent
): HomeContent =
    currentContent
        ?: lastVisibleContent?.takeIf {
            homeRefreshInProgress || homeLoadState == ScreenLoadState.Loading
        }
        ?: fallbackContent

fun HomeContent.withPreservedConversationUnread(previous: HomeContent?): HomeContent {
    if (previous == null) return this
    val preservedChannels = channels.map { it.withPreservedUnread(previous.conversationUnreadFor(it)) }
    val preservedDirectMessages = directMessages.map { it.withPreservedUnread(previous.conversationUnreadFor(it)) }
    return copy(
        channels = preservedChannels,
        directMessages = preservedDirectMessages,
        unreadTotal = preserveUnreadTotal(
            previous = previous,
            channels = preservedChannels,
            directMessages = preservedDirectMessages
        )
    )
}

private fun HomeContent.conversationUnreadFor(conversation: ConversationSummary): ConversationSummary? {
    val candidates = if (conversation.kind == ai.slock.android.core.model.ConversationKind.DirectMessage) {
        directMessages
    } else {
        channels
    }
    return candidates.firstOrNull { existing ->
        existing.threadId == conversation.threadId ||
            (existing.channelId != null && existing.channelId == conversation.channelId) ||
            (conversation.channelId != null && existing.threadId.value == conversation.channelId.value) ||
            (existing.channelId != null && existing.channelId.value == conversation.threadId.value)
    }
}

private fun ConversationSummary.withPreservedUnread(previous: ConversationSummary?): ConversationSummary {
    if (previous == null || unreadCount > 0 || previous.unreadCount <= 0) return this
    return copy(
        unreadCount = previous.unreadCount,
        mentionCount = previous.mentionCount,
        firstUnreadMessageId = previous.firstUnreadMessageId
    )
}

private fun HomeContent.preserveUnreadTotal(
    previous: HomeContent,
    channels: List<ConversationSummary>,
    directMessages: List<ConversationSummary>
): Int {
    val rowTotal = channels.sumOf { it.unreadCount } + directMessages.sumOf { it.unreadCount }
    return when {
        rowTotal > 0 -> rowTotal
        unreadTotal > 0 && previous.unreadTotal <= 0 -> unreadTotal
        else -> 0
    }
}
