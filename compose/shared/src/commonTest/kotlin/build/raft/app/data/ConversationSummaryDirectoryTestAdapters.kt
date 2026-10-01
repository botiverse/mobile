package build.raft.app.data

import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.memberDetailIdentity
import build.raft.app.parsedMemberKindOrNull
import build.raft.app.toKuiklySurfaceKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ServerId
import build.raft.app.model.isGenericDisplayName
import build.raft.app.model.messageStreamChannelId
import build.raft.app.model.safeDisplayNameOrNull

internal fun HomeSnapshot.resolveHomeConversationSummary(
    ids: Collection<String>,
    logger: (String) -> Unit = {}
): ConversationSummary? {
    val lookupIds = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (lookupIds.isEmpty()) return null
    val projection =
        toSharedConversationDirectoryState(logger)
            .let { directory -> lookupIds.firstNotNullOfOrNull(directory::resolve) }
            ?: return null
    return (channels + directMessages)
        .asSequence()
        .map { it.toConversationSummary() }
        .firstOrNull { conversation ->
            conversation.directoryCanonicalChannelId().value == projection.canonicalChannelId
        }
}

internal fun ConversationSummary.toSharedConversationDirectoryProjection(
    serverId: ServerId
): SharedConversationDirectoryProjection? {
    val canonicalChannelId = directoryCanonicalChannelId().value.takeIf { it.isNotBlank() } ?: return null
    val title =
        safeDisplayNameOrNull()
            ?.takeUnless { isGenericDisplayName(it, additionalIdentityIds = setOf(canonicalChannelId)) }
            ?: canonicalChannelId
    val kind = toDirectoryConversationKind()
    return SharedConversationDirectoryProjection(
        serverId = serverId.value,
        canonicalChannelId = canonicalChannelId,
        aliases = aliases(canonicalChannelId).filter { it.isNotBlank() }.toSet() + canonicalChannelId,
        conversationKind = kind,
        surfaceKind = kind.toKuiklySurfaceKind(),
        channelKind = toDirectoryChannelType(),
        title = title,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        avatarWireValue = null,
        avatarLabel = title.take(2).uppercase(),
        peerIdentity =
            memberDetailIdentity(
                memberId = presenceMemberId?.value,
                kind = directMessagePeerType.parsedMemberKindOrNull(),
                sourceServerId = serverId.value
            ),
        parentChannelId = if (kind == ConversationKind.Thread) thread?.parentChannelId?.value else null,
        parentMessageId = if (kind == ConversationKind.Thread) thread?.parentMessageId?.value else null,
        joined = joined && permissions.canRead,
        archived = archived,
        permissions = permissions,
        bridgeProvider = if (kind == ConversationKind.Thread) null else bridgeProvider
    )
}

private fun ConversationSummary.aliases(canonicalChannelId: String): Set<String> =
    buildSet {
        val routeIdentity = resolveThreadRouteIdentity()
        val isReplyThread = kind == ConversationKind.Thread
        add(canonicalChannelId)
        channelId
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { kind != ConversationKind.Thread || it == routeIdentity.timelineChannelId }
            ?.let(::add)
        threadId.value.takeIf { it.isNotBlank() }?.let(::add)
        if (isReplyThread) {
            thread?.streamChannelId?.value?.takeIf { it.isNotBlank() }?.let(::add)
            thread?.parentChannelId?.value?.takeIf { it.isNotBlank() }?.let(::add)
            thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }?.let(::add)
        }
        presenceMemberId?.value?.takeIf { it.isNotBlank() }?.let(::add)
    }

private fun ConversationSummary.directoryCanonicalChannelId(): ChannelId =
    if (kind == ConversationKind.Thread) {
        messageStreamChannelId()
    } else {
        channelId ?: ChannelId(threadId.value)
    }

private fun ConversationSummary.toDirectoryChannelType(): String =
    when (kind) {
        ConversationKind.Thread -> "thread"
        ConversationKind.Joint -> "joint"
        ConversationKind.Channel -> channelType?.takeIf { it.isNotBlank() } ?: "channel"
        ConversationKind.DirectMessage -> "dm"
        ConversationKind.Inbox,
        ConversationKind.System -> "channel"
    }

private fun ConversationSummary.toDirectoryConversationKind(): ConversationKind =
    when (kind) {
        ConversationKind.Thread -> ConversationKind.Thread
        ConversationKind.Joint -> ConversationKind.Joint
        ConversationKind.DirectMessage -> ConversationKind.DirectMessage
        ConversationKind.Channel,
        ConversationKind.Inbox,
        ConversationKind.System -> ConversationKind.Channel
    }
