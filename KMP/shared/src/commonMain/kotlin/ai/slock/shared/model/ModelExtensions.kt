package ai.slock.shared.model

// ConversationSummary extensions
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

// Message extensions
fun List<Message>.sortedByMessageTime(): List<Message> =
    sortedWith(MessageTimelineComparator)

private val MessageTimelineComparator =
    Comparator<Message> { left, right ->
        val leftTime = left.timestampLabel.toMessageEpochMillisOrNull()
        val rightTime = right.timestampLabel.toMessageEpochMillisOrNull()
        if (leftTime != null && rightTime != null) {
            compareValues(leftTime, rightTime).takeIf { it != 0 }?.let { return@Comparator it }
        }
        compareValues(left.seq ?: Long.MAX_VALUE, right.seq ?: Long.MAX_VALUE)
            .takeIf { it != 0 }
            ?: left.id.value.compareTo(right.id.value)
    }

private fun String.toMessageEpochMillisOrNull(): Long? {
    val match =
        IsoTimestampRegex.matchEntire(trim())
            ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
    val minute = match.groupValues[5].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
    val second = match.groupValues[6].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
    val millis = match.groupValues[7].toMillisFraction()
    val offsetMillis = match.groupValues[8].toOffsetMillisOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
        return null
    }
    val epochDay = daysSinceUnixEpoch(year, month, day)
    return epochDay * 86_400_000L +
        hour * 3_600_000L +
        minute * 60_000L +
        second * 1_000L +
        millis -
        offsetMillis
}

private val IsoTimestampRegex =
    Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T\s](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(?:\s*(Z|[+-]\d{2}:?\d{2}))?)?$""")

private fun String.toMillisFraction(): Long {
    if (isBlank()) return 0L
    return take(3).padEnd(3, '0').toLongOrNull() ?: 0L
}

private fun String.toOffsetMillisOrNull(): Long? {
    if (isBlank() || this == "Z") return 0L
    val sign = when (firstOrNull()) {
        '+' -> 1
        '-' -> -1
        else -> return null
    }
    val raw = drop(1).replace(":", "")
    if (raw.length != 4) return null
    val hours = raw.substring(0, 2).toIntOrNull() ?: return null
    val minutes = raw.substring(2, 4).toIntOrNull() ?: return null
    if (hours !in 0..23 || minutes !in 0..59) return null
    return sign * (hours * 3_600_000L + minutes * 60_000L)
}

private fun daysSinceUnixEpoch(year: Int, month: Int, day: Int): Long {
    val adjustedYear = year - if (month <= 2) 1 else 0
    val era = floorDiv(adjustedYear, 400)
    val yearOfEra = adjustedYear - era * 400
    val adjustedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * adjustedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

private fun floorDiv(value: Int, divisor: Int): Int {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0 && (value xor divisor) < 0) quotient - 1 else quotient
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

// Gravatar helpers
fun String.toGravatarAvatarUrl(): String =
    "https://www.gravatar.com/avatar/${trim().lowercase()}?s=52&d=404"

fun String.toGravatarAvatarReference(): AvatarReference =
    when {
        startsWith("http://") || startsWith("https://") ->
            AvatarReference.Url(this)
        else -> AvatarReference.Url(toGravatarAvatarUrl())
    }
