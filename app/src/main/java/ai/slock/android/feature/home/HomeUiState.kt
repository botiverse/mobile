package ai.slock.android.feature.home

import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberKind
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
    val rowTotal = preservedChannels.sumOf { it.unreadCount } + preservedDirectMessages.sumOf { it.unreadCount }
    return copy(
        channels = preservedChannels,
        directMessages = preservedDirectMessages,
        unreadTotal = rowTotal,
        serverUnreadCount = maxOf(serverUnreadCount, rowTotal)
    )
}

fun HomeContent.withAgentDirectMessagePresence(members: List<MemberProfile>): HomeContent {
    val agents = members.filter { it.kind == MemberKind.Agent }
    if (agents.isEmpty()) return this
    val updatedDirectMessages = directMessages.map { conversation ->
        if (conversation.presence != null) {
            conversation
        } else {
            val agent = agents.firstOrNull { it.matchesDirectMessage(conversation) }
            if (agent != null) {
                conversation.copy(
                    presence = agent.presence,
                    agentStatus = agent.agentStatus,
                    presenceMemberId = conversation.presenceMemberId ?: agent.id
                )
            } else {
                conversation
            }
        }
    }
    return copy(directMessages = updatedDirectMessages)
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

private fun MemberProfile.matchesDirectMessage(conversation: ConversationSummary): Boolean =
    conversation.presenceMemberId == id ||
        conversation.channelId?.value == id.value ||
        conversation.threadId.value == id.value ||
        handle.equals(conversation.name, ignoreCase = true) ||
        displayName.equals(conversation.name, ignoreCase = true) ||
        handle.equals(conversation.name.removePrefix("@"), ignoreCase = true)
