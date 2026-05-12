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
    currentContent?.withDirectMessagePresenceFallback(lastVisibleContent)
        ?: lastVisibleContent?.takeIf {
            homeRefreshInProgress || homeLoadState == ScreenLoadState.Loading
        }
        ?: fallbackContent

fun HomeContent.withDirectMessagePresenceFallback(previous: HomeContent?): HomeContent {
    if (previous == null || previous.directMessages.isEmpty()) return this
    val previousByKey =
        previous.directMessages.associateBy { it.directMessagePresenceFallbackKey() }
    val updatedDirectMessages =
        directMessages.map { conversation ->
            val previousConversation =
                previousByKey[conversation.directMessagePresenceFallbackKey()]
                    ?: return@map conversation
            conversation.copy(
                presence = conversation.presence ?: previousConversation.presence,
                agentStatus = conversation.agentStatus ?: previousConversation.agentStatus,
                presenceMemberId = conversation.presenceMemberId ?: previousConversation.presenceMemberId
            )
        }
    return copy(directMessages = updatedDirectMessages)
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

private fun ConversationSummary.directMessagePresenceFallbackKey(): String =
    channelId?.value ?: threadId.value

private fun MemberProfile.matchesDirectMessage(conversation: ConversationSummary): Boolean =
    conversation.presenceMemberId == id ||
        conversation.channelId?.value == id.value ||
        conversation.threadId.value == id.value ||
        handle.equals(conversation.name, ignoreCase = true) ||
        displayName.equals(conversation.name, ignoreCase = true) ||
        handle.equals(conversation.name.removePrefix("@"), ignoreCase = true)
