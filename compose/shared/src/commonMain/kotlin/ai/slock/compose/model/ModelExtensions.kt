package ai.slock.compose.model

// ConversationSummary extensions
private val ConversationTitleUuidRegex =
    Regex("^[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$")
private val ConversationTitleShortIdRegex = Regex("^[A-Fa-f0-9]{8}$")

fun conversationDisplayTitleCandidate(raw: String?): String? =
    raw
        ?.trim()
        ?.removePrefix("#")
        ?.removePrefix("@")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

fun conversationIdentityLikeTitle(
    title: String,
    identityIds: Set<String> = emptySet()
): Boolean {
    val candidate = conversationDisplayTitleCandidate(title) ?: return false
    if (ConversationTitleUuidRegex.matches(candidate) || ConversationTitleShortIdRegex.matches(candidate)) return true
    return identityIds.any { id -> id.isNotBlank() && candidate.equals(id.trim(), ignoreCase = true) }
}

fun conversationSafeDisplayTitleOrNull(
    raw: String?,
    identityIds: Set<String> = emptySet()
): String? =
    conversationDisplayTitleCandidate(raw)
        ?.takeUnless { candidate -> conversationIdentityLikeTitle(candidate, identityIds) }

fun ConversationSummary.headerIdentityIds(): Set<String> =
    listOfNotNull(
        threadId.value,
        channelId?.value,
        thread?.streamChannelId?.value
    ).filter { it.isNotBlank() }.toSet()

fun ConversationSummary.safeDisplayNameOrNull(): String? =
    conversationSafeDisplayTitleOrNull(name, headerIdentityIds())

fun ConversationSummary.genericDisplayTitleFallback(): String =
    when (kind) {
        ConversationKind.DirectMessage -> "Direct Message"
        ConversationKind.Thread -> "Thread"
        ConversationKind.Channel,
        ConversationKind.Joint,
        ConversationKind.Inbox,
        ConversationKind.System -> "Channel"
    }

fun ConversationSummary.isGenericDisplayName(
    rawName: String = name,
    additionalIdentityIds: Set<String> = emptySet()
): Boolean {
    val candidate = conversationDisplayTitleCandidate(rawName) ?: return true
    if (conversationIdentityLikeTitle(candidate, headerIdentityIds() + additionalIdentityIds)) return true
    return candidate.equals(genericDisplayTitleFallback(), ignoreCase = true)
}

fun ConversationSummary.nameIsBlankOrIdentity(
    rawName: String = name,
    additionalIdentityIds: Set<String> = emptySet()
): Boolean =
    conversationSafeDisplayTitleOrNull(rawName, headerIdentityIds() + additionalIdentityIds) == null

fun ConversationSummary.withTitleFallback(fallback: ConversationSummary?): ConversationSummary {
    if (fallback == null) return this
    val nameLooksLikeIdentity = nameIsBlankOrIdentity()
    val sameConversation = isSameConversationAs(fallback)
    val sameParentChannel =
        kind == ConversationKind.Thread &&
            fallback.kind != ConversationKind.Thread &&
            channelId != null &&
            channelId == fallback.channelId &&
            fallback.hasDisplayName()
    val shouldUseFallbackName =
        nameLooksLikeIdentity && fallback.hasDisplayName() && (sameConversation || sameParentChannel)
    val fallbackDisplayName = fallback.safeDisplayNameOrNull()
    return copy(
        name = if (shouldUseFallbackName && fallbackDisplayName != null) fallbackDisplayName else name,
        subtitle = subtitle ?: fallback.subtitle,
        directMessagePeerType = directMessagePeerType ?: fallback.directMessagePeerType.takeIf { sameConversation },
        channelId = channelId ?: fallback.channelId.takeIf { sameConversation },
        channelType = channelType ?: fallback.channelType.takeIf { sameConversation },
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
        memberCount = memberCount ?: fallback.memberCount,
        jointChannelId = jointChannelId ?: fallback.jointChannelId.takeIf { sameConversation },
        jointRole = jointRole ?: fallback.jointRole.takeIf { sameConversation },
        jointPeerServerId = jointPeerServerId ?: fallback.jointPeerServerId.takeIf { sameConversation },
        jointPeerServerName = jointPeerServerName ?: fallback.jointPeerServerName.takeIf { sameConversation },
        jointPeerServerSlug = jointPeerServerSlug ?: fallback.jointPeerServerSlug.takeIf { sameConversation },
        jointPeerStatus = jointPeerStatus ?: fallback.jointPeerStatus.takeIf { sameConversation }
    )
}

fun ConversationSummary.messageStreamChannelId(): ChannelId =
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
    safeDisplayNameOrNull() != null

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

// Message extensions
fun List<Message>.sortedByMessageTime(): List<Message> =
    sortedWith(MessageTimelineComparator)

private val MessageTimelineComparator =
    Comparator<Message> { left, right ->
        val leftTime = parseSlockTimestampEpochMillisOrNull(left.timestampLabel)
        val rightTime = parseSlockTimestampEpochMillisOrNull(right.timestampLabel)
        if (leftTime != null && rightTime != null) {
            compareValues(leftTime, rightTime).takeIf { it != 0 }?.let { return@Comparator it }
        }
        compareValues(left.seq ?: Long.MAX_VALUE, right.seq ?: Long.MAX_VALUE)
            .takeIf { it != 0 }
            ?: left.id.value.compareTo(right.id.value)
    }

// AgentProfile extensions
fun AgentProfile.toMemberProfile(): MemberProfile =
    MemberProfile(
        id = id,
        displayName = displayName,
        handle = handle,
        kind = MemberKind.Agent,
        description = description,
        roleDescription = roleDescription,
        avatarUrl = avatarUrl,
        avatar = avatar,
        avatarLabel = avatarLabel,
        presence = presence,
        agentStatus = agentStatus,
        permissionBadges = permissionBadges
    )

// Avatar priority helpers
data class AvatarSlotResolution(
    val displayAvatarWireValue: String?,
    val gravatarHash: String?,
    val fallbackAvatarWireValue: String?
) {
    val effectiveAvatarWireValue: String?
        get() =
            displayAvatarWireValue
                ?: gravatarHash?.takeIf { it.isNotBlank() }?.toGravatarAvatarUrl()
                ?: fallbackAvatarWireValue
}

data class AvatarMergeResolution(
    val effectiveAvatarWireValue: String?,
    val gravatarHash: String?
)

fun MemberProfile.avatarSlotResolution(): AvatarSlotResolution =
    resolveAvatarSlot(
        kind = kind,
        id = id.value,
        avatarWireValue = avatar?.wireValue,
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash
    )

fun MemberProfile.avatarWireValueForAvatarSlot(): String? =
    avatarSlotResolution().effectiveAvatarWireValue

fun avatarWireValueForAvatarSlot(
    kind: MemberKind,
    id: String,
    avatarWireValue: String?,
    avatarUrl: String? = null,
    gravatarHash: String? = null
): String? =
    resolveAvatarSlot(
        kind = kind,
        id = id,
        avatarWireValue = avatarWireValue,
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash
    ).effectiveAvatarWireValue

fun resolveMergedAvatarSlot(
    kind: MemberKind,
    id: String,
    currentAvatarWireValue: String?,
    currentGravatarHash: String? = null,
    incomingAvatarWireValue: String? = null,
    incomingAvatarUrl: String? = null,
    incomingGravatarHash: String? = null
): AvatarMergeResolution {
    val cleanCurrentAvatar = currentAvatarWireValue?.takeIf { it.isNotBlank() }
    val current =
        resolveAvatarSlot(
            kind = kind,
            id = id,
            avatarWireValue = cleanCurrentAvatar,
            gravatarHash = currentGravatarHash
        )
    val incoming =
        resolveAvatarSlot(
            kind = kind,
            id = id,
            avatarWireValue = incomingAvatarWireValue,
            avatarUrl = incomingAvatarUrl,
            gravatarHash = incomingGravatarHash
        )
    if (kind == MemberKind.Agent) {
        val shouldUseIncoming =
            cleanCurrentAvatar == null ||
                cleanCurrentAvatar.isGravatarAvatarWireValue() ||
                cleanCurrentAvatar.isAgentFallbackAvatarWireValue()
        return AvatarMergeResolution(
            effectiveAvatarWireValue =
                if (shouldUseIncoming) {
                    incoming.effectiveAvatarWireValue ?: current.effectiveAvatarWireValue
                } else {
                    current.effectiveAvatarWireValue
                },
            gravatarHash = null
        )
    }
    return AvatarMergeResolution(
        effectiveAvatarWireValue = current.effectiveAvatarWireValue ?: incoming.effectiveAvatarWireValue,
        gravatarHash = current.gravatarHash ?: incoming.gravatarHash
    )
}

fun resolveAvatarSlot(
    kind: MemberKind,
    id: String,
    avatarWireValue: String?,
    avatarUrl: String? = null,
    gravatarHash: String? = null
): AvatarSlotResolution {
    val wireValue =
        avatarWireValue
            ?.takeIf { it.isNotBlank() }
            ?: avatarUrl?.takeIf { it.isNotBlank() }
    if (kind != MemberKind.Agent) {
        return AvatarSlotResolution(
            displayAvatarWireValue = wireValue,
            gravatarHash = gravatarHash?.takeIf { it.isNotBlank() },
            fallbackAvatarWireValue = null
        )
    }
    val fallback = agentAvatarFallbackWireValue(id)
    return AvatarSlotResolution(
        displayAvatarWireValue = wireValue?.takeIf { it.isAgentAvatarWireValue() },
        gravatarHash = null,
        fallbackAvatarWireValue = fallback
    )
}

fun agentAvatarFallbackWireValue(id: String): String =
    "pixel:robot"

fun String.isGravatarAvatarWireValue(): Boolean =
    contains("gravatar.com/avatar", ignoreCase = true)

fun String.isAgentFallbackAvatarWireValue(): Boolean =
    equals(agentAvatarFallbackWireValue(""), ignoreCase = true) ||
        startsWith("random:", ignoreCase = true)

private fun String.isAgentAvatarWireValue(): Boolean =
    startsWith("pixel:") ||
        startsWith("random:") ||
        ((startsWith("http://") || startsWith("https://")) && !isGravatarAvatarWireValue())

// Gravatar helpers
fun String.toGravatarAvatarUrl(): String =
    "https://www.gravatar.com/avatar/${trim().lowercase()}?s=52&d=404"

fun String.toGravatarAvatarReference(): AvatarReference =
    when {
        startsWith("http://") || startsWith("https://") ->
            AvatarReference.Url(this)
        else -> AvatarReference.Url(toGravatarAvatarUrl())
    }
