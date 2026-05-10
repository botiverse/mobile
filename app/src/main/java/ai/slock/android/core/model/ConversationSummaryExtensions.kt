package ai.slock.android.core.model

fun ConversationSummary.withTitleFallback(fallback: ConversationSummary?): ConversationSummary {
    if (fallback == null) return this
    val genericNames = setOf("Messages", "channel", threadId.value, channelId?.value.orEmpty())
        .filter { it.isNotBlank() }
    val shouldUseFallback = name.isBlank() || name in genericNames
    val shouldUseFallbackThreadId = threadId.value == "unknown-channel"
    val shouldUseFallbackChannelId = channelId == null || channelId?.value == "unknown-channel"
    return if (shouldUseFallback) {
        copy(
            name = fallback.name,
            subtitle = subtitle ?: fallback.subtitle,
            threadId = if (shouldUseFallbackThreadId) fallback.threadId else threadId,
            channelId = if (shouldUseFallbackChannelId) fallback.channelId else channelId,
            kind = fallback.kind,
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
    } else {
        copy(
            threadId = if (shouldUseFallbackThreadId) fallback.threadId else threadId,
            channelId = if (shouldUseFallbackChannelId) fallback.channelId else channelId,
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
}

fun ConversationSummary.withPreviewMembershipFallback(
    fallback: ConversationSummary?
): ConversationSummary {
    if (fallback == null || fallback.joined) return this
    val sameChannel = threadId == fallback.threadId ||
        channelId == fallback.channelId ||
        channelId?.value == fallback.threadId.value ||
        fallback.channelId?.value == threadId.value
    if (!sameChannel) return this
    return copy(
        joined = false,
        permissions = fallback.permissions
    )
}
