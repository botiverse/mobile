package build.raft.app

import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.isGenericDisplayName
import build.raft.app.model.safeDisplayNameOrNull

internal fun ConversationSummary.toConversationHeaderEntry(): SharedConversationHeaderEntry? {
    val surfaceKind =
        when (kind) {
            ConversationKind.DirectMessage -> KuiklyThreadSurfaceKind.DirectMessage
            ConversationKind.Channel,
            ConversationKind.Joint -> KuiklyThreadSurfaceKind.Channel
            else -> return null
        }
    val ids =
        listOfNotNull(
            channelId?.value,
            threadId.value,
            thread?.streamChannelId?.value
        ).filter { it.isNotBlank() }.toSet()
    if (ids.isEmpty()) return null
    val displayName = safeDisplayNameOrNull()?.takeUnless { isGenericDisplayName(it) } ?: return null
    return SharedConversationHeaderEntry(
        ids = ids,
        surfaceKind = surfaceKind,
        channelKind = resolveThreadRouteIdentity().channelKind ?: channelType?.takeIf { it.isNotBlank() } ?: kind.name.lowercase(),
        title = displayName,
        subtitle = if (surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) null else subtitle?.takeIf { it.isNotBlank() },
        avatarWireValue = null,
        avatarLabel = displayName.take(2).uppercase(),
        dmPeerIdentity =
            memberDetailIdentity(
                memberId = presenceMemberId?.value,
                kind = directMessagePeerType.parsedMemberKindOrNull()
            )
    )
}

internal fun ConversationSummary.toConversationAccessCandidate(): ConversationAccessCandidate? {
    val entry = toConversationHeaderEntry() ?: return null
    return ConversationAccessCandidate(
        ids = entry.ids,
        surfaceKind = entry.surfaceKind,
        joined = joined,
        archived = archived,
        permissions = permissions
    )
}
