package build.raft.app.model

import build.raft.app.isRaftHumanPixelAvatarUrl

// ConversationSummary extensions
private val ConversationTitleUuidRegex =
    Regex("^[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}$")
private val ConversationTitleShortIdRegex = Regex("^[A-Fa-f0-9]{8}$")
private const val InternalJointStorageTitlePrefix = "joint-storage-"

internal fun isInternalJointStorageIdentity(raw: String?): Boolean =
    raw?.trim()?.startsWith(InternalJointStorageTitlePrefix, ignoreCase = true) == true

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
    if (isInternalJointStorageIdentity(candidate)) return true
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
        ConversationKind.System,
        ConversationKind.Task -> "Channel"
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
        jointPeerStatus = jointPeerStatus ?: fallback.jointPeerStatus.takeIf { sameConversation },
        jointBillingLocked = jointBillingLocked ?: fallback.jointBillingLocked.takeIf { sameConversation },
        jointOverLimitGraceEndsAt =
            jointOverLimitGraceEndsAt ?: fallback.jointOverLimitGraceEndsAt.takeIf { sameConversation },
        bridgeProvider = bridgeProvider ?: fallback.bridgeProvider.takeIf { sameConversation },
        channelCapabilities = channelCapabilities ?: fallback.channelCapabilities.takeIf { sameConversation }
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
        val leftTime = parseRaftTimestampEpochMillisOrNull(left.timestampLabel)
        val rightTime = parseRaftTimestampEpochMillisOrNull(right.timestampLabel)
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
        avatar = avatar,
        avatarLabel = avatarLabel,
        presence = presence,
        agentStatus = agentStatus,
        isExternal = isExternal,
        model = model,
        permissionBadges = permissionBadges
    )

// Avatar priority helpers
data class AvatarSlotResolution(
    val displayAvatarWireValue: String?,
    val fallbackAvatarWireValue: String?
) {
    val effectiveAvatarWireValue: String?
        get() =
            displayAvatarWireValue
                ?: fallbackAvatarWireValue
}

/**
 * A profile's avatar slot. Gravatar is retired (slock #13674: the server sends its hash fields
 * as `""` and the web builds no gravatar.com URL), so a person shows an uploaded avatar or the
 * placeholder; a `gravatarHash` still parsed from the wire is never read here.
 */
fun MemberProfile.avatarSlotResolution(): AvatarSlotResolution =
    resolveAvatarSlot(
        kind = kind,
        id = id.value,
        avatarWireValue = avatar?.wireValue
    )

fun MemberProfile.avatarWireValueForAvatarSlot(): String? =
    avatarSlotResolution().effectiveAvatarWireValue

fun MemberProfile.avatarWireValueForRenderedAvatar(): String? =
    avatarWireValueForRenderedAvatar(
        kind = kind,
        id = id.value,
        avatarWireValue = avatar?.wireValue
    )

fun avatarWireValueForAvatarSlot(
    kind: MemberKind,
    id: String,
    avatarWireValue: String?,
    avatarUrl: String? = null
): String? =
    resolveAvatarSlot(
        kind = kind,
        id = id,
        avatarWireValue = avatarWireValue,
        avatarUrl = avatarUrl
    ).effectiveAvatarWireValue

fun avatarWireValueForRenderedAvatar(
    kind: MemberKind,
    id: String,
    avatarWireValue: String?,
    avatarUrl: String? = null
): String? =
    explicitAvatarWireValue(kind, avatarWireValue, avatarUrl)
        ?: avatarWireValueForAvatarSlot(
            kind = kind,
            id = id,
            avatarWireValue = null,
            avatarUrl = null
        )

/**
 * The avatar slot (web `AvatarSlot`): the uploaded avatar (a person's own upload; an agent's any
 * app avatar), else nothing for a person (the placeholder) and the default face for an agent.
 */
fun resolveAvatarSlot(
    kind: MemberKind,
    id: String,
    avatarWireValue: String?,
    avatarUrl: String? = null
): AvatarSlotResolution {
    val wireValue = explicitAvatarWireValue(kind, avatarWireValue, avatarUrl)
    if (kind != MemberKind.Agent) {
        return AvatarSlotResolution(
            displayAvatarWireValue = wireValue,
            fallbackAvatarWireValue = null
        )
    }
    return AvatarSlotResolution(
        displayAvatarWireValue = wireValue,
        fallbackAvatarWireValue = agentAvatarFallbackWireValue(id)
    )
}

fun agentAvatarFallbackWireValue(id: String): String =
    "pixel:robot"

/** The retired Gravatar wire value (`gravatar:<hash>`), still found in caches and snapshots. */
private const val RetiredGravatarAvatarWirePrefix = "gravatar:"

/**
 * A Gravatar avatar the app no longer shows (slock #13674): a gravatar.com URL, or a cached /
 * snapshot `gravatar:<hash>` wire value. Either draws the placeholder, as no avatar does.
 */
fun String.isLegacyGravatarAvatarUrl(): Boolean =
    contains("gravatar.com/avatar", ignoreCase = true) ||
        trim().startsWith(RetiredGravatarAvatarWirePrefix, ignoreCase = true)

fun String.appAvatarWireValueOrNull(): String? =
    trim()
        .takeIf { it.isNotBlank() }
        ?.takeUnless { it.isLegacyGravatarAvatarUrl() }

private val RaftUploadedHumanAvatarPath =
    Regex("^/(?:api/)?avatars/users/[0-9a-f]+\\.webp$", RegexOption.IGNORE_CASE)

internal fun String.raftUploadedHumanAvatarWireValueOrNull(): String? {
    val normalized = trim().takeIf { it.isNotBlank() } ?: return null
    val path =
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            val authorityStart = normalized.indexOf("://") + 3
            val authorityEnd = normalized.indexOf('/', startIndex = authorityStart)
            if (authorityEnd < 0) return null
            normalized.substring(authorityEnd)
        } else {
            normalized
        }
            .substringBefore('?')
            .substringBefore('#')
    return normalized.takeIf { RaftUploadedHumanAvatarPath.matches(path) }
}

/**
 * What a person's avatar shows (web `humanAvatarSource`): their uploaded photo, else the pixel
 * avatar they built (`pixel-human:v1:...`); anything else (an old social-provider picture, a
 * value Raft doesn't draw) is none, the placeholder.
 */
internal fun String.raftShownHumanAvatarWireValueOrNull(): String? =
    raftUploadedHumanAvatarWireValueOrNull()
        ?: trim().takeIf(::isRaftHumanPixelAvatarUrl)

private fun explicitAvatarWireValue(
    kind: MemberKind,
    avatarWireValue: String?,
    avatarUrl: String?
): String? =
    if (kind == MemberKind.Human) {
        avatarWireValue?.raftShownHumanAvatarWireValueOrNull()
            ?: avatarUrl?.raftShownHumanAvatarWireValueOrNull()
    } else {
        avatarWireValue?.appAvatarWireValueOrNull()
            ?: avatarUrl?.appAvatarWireValueOrNull()
    }

fun String.appAvatarApiFallbackUrl(apiBaseUrl: String?): String? {
    val normalizedSource = trim()
    if (!normalizedSource.startsWith("http://") && !normalizedSource.startsWith("https://")) return null

    val authorityEnd = normalizedSource.indexOf('/', startIndex = normalizedSource.indexOf("://") + 3)
    if (authorityEnd < 0) return null
    val path =
        normalizedSource
            .substring(authorityEnd)
            .substringBefore('?')
            .substringBefore('#')
    val match = AppAvatarCdnPath.matchEntire(path) ?: return null
    val base = apiBaseUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    val namespace = match.groupValues[1]
    val filename = match.groupValues[2]
    return "${base.trimEnd('/')}/avatars/$namespace/$filename"
}

private val AppAvatarCdnPath =
    Regex("^/avatars/([A-Za-z0-9_-]+)/([0-9A-Fa-f]{32,64}\\.webp)$")

fun String.isAgentFallbackAvatarWireValue(): Boolean =
    equals(agentAvatarFallbackWireValue(""), ignoreCase = true) ||
        startsWith("random:", ignoreCase = true)
