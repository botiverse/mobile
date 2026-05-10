package ai.slock.android.core.model

fun ConversationSummary.withTitleFallback(fallback: ConversationSummary?): ConversationSummary {
    if (fallback == null) return this
    val nameLooksLikeIdentity =
        name.isBlank() || name == threadId.value || name == channelId?.value
    val sameConversation = isSameConversationAs(fallback)
    val shouldUseFallbackName = sameConversation && nameLooksLikeIdentity && fallback.hasDisplayName()
    return copy(
        name = if (shouldUseFallbackName) fallback.name else name,
        subtitle = subtitle ?: fallback.subtitle,
        channelId = channelId ?: fallback.channelId.takeIf { sameConversation },
        thread = thread ?: fallback.thread.takeIf { sameConversation },
        kind = if (shouldUseFallbackName) fallback.kind else kind,
        unreadCount = if (unreadCountKnown) unreadCount else fallback.unreadCount,
        unreadCountKnown = unreadCountKnown || fallback.unreadCountKnown,
        mentionCount = if (mentionCountKnown) mentionCount else fallback.mentionCount,
        mentionCountKnown = mentionCountKnown || fallback.mentionCountKnown,
        lastMessageId = lastMessageId ?: fallback.lastMessageId,
        firstUnreadMessageId = firstUnreadMessageId ?: fallback.firstUnreadMessageId,
        lastMessagePreview = lastMessagePreview ?: fallback.lastMessagePreview,
        lastMessageAt = lastMessageAt ?: fallback.lastMessageAt,
        lastMessageSenderName = lastMessageSenderName ?: fallback.lastMessageSenderName,
        hasDraft = if (hasDraftKnown) hasDraft else fallback.hasDraft,
        hasDraftKnown = hasDraftKnown || fallback.hasDraftKnown,
        memberCount = memberCount ?: fallback.memberCount
    )
}

fun ConversationSummary.messageStreamChannelId(): ChannelId =
    // API routing id for /messages/channel/{id}; thread identity remains parent channel + parent message.
    explicitMessageStreamChannelId() ?: ChannelId(threadId.value)

fun ConversationSummary.explicitMessageStreamChannelId(): ChannelId? =
    thread?.streamChannelId
        ?: if (kind == ConversationKind.Thread) null else channelId ?: ChannelId(threadId.value)

fun topLevelThreadId(channelId: ChannelId): ThreadId = ThreadId(channelId.value)

fun ConversationSummary.isSameConversationAs(other: ConversationSummary): Boolean {
    if (kind == ConversationKind.Thread || other.kind == ConversationKind.Thread) {
        return threadAnchorChannelId() != null &&
            threadAnchorChannelId() == other.threadAnchorChannelId() &&
            threadAnchorMessageId() != null &&
            threadAnchorMessageId() == other.threadAnchorMessageId()
    }
    return threadId == other.threadId ||
        isTopLevelChannel && other.isTopLevelChannel && channelId == other.channelId
}

fun threadIdentity(parentChannelId: ChannelId, parentMessageId: MessageId): ThreadId =
    ThreadId("${parentChannelId.value}:${parentMessageId.value}")

private fun ConversationSummary.threadAnchorChannelId(): ChannelId? =
    thread?.parentChannelId ?: channelId
        ?: threadId.parentChannelIdOrNull()

private fun ConversationSummary.threadAnchorMessageId(): MessageId? =
    thread?.parentMessageId ?: threadId.parentMessageIdOrNull()

fun ThreadId.parentChannelIdOrNull(): ChannelId? =
    value.substringBefore(':').takeIf { it != value && it.isNotBlank() }?.let(::ChannelId)

fun ThreadId.parentMessageIdOrNull(): MessageId? =
    value.substringAfter(':').takeIf { it != value && it.isNotBlank() }?.let(::MessageId)

private val ConversationSummary.isTopLevelChannel: Boolean
    get() = kind == ConversationKind.Channel && thread == null &&
        (channelId == null || channelId.value == threadId.value)

private fun ConversationSummary.hasDisplayName(): Boolean =
    name.isNotBlank() && name != threadId.value && name != channelId?.value

fun ConversationSummary.withPreviewMembershipFallback(
    fallback: ConversationSummary?
): ConversationSummary {
    if (fallback == null || fallback.joined) return this
    if (!isSameConversationAs(fallback)) return this
    return copy(
        joined = false,
        permissions = fallback.permissions
    )
}
