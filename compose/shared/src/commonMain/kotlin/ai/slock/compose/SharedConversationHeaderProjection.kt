package ai.slock.compose

import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary

internal data class SharedConversationHeaderEntry(
    val ids: Set<String>,
    val surfaceKind: String,
    val title: String,
    val subtitle: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.take(2).uppercase(),
    val gravatarUrl: String? = null
)

internal data class SharedConversationHeaderState(
    val entriesById: Map<String, SharedConversationHeaderEntry> = emptyMap()
) {
    fun entryFor(payload: KuiklyThreadScreenPayload): SharedConversationHeaderEntry? {
        if (payload.surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) return null
        return payload.conversationHeaderLookupIds()
            .firstNotNullOfOrNull { id ->
                entriesById[id]?.takeIf { entry -> entry.surfaceKind == payload.surfaceKind }
            }
    }
}

internal fun sharedConversationHeaderState(
    conversations: List<ConversationSummary>
): SharedConversationHeaderState =
    SharedConversationHeaderState(
        entriesById =
            conversations
                .mapNotNull { conversation -> conversation.toConversationHeaderEntry() }
                .flatMap { entry -> entry.ids.map { id -> id to entry } }
                .toMap()
    )

internal fun KuiklyThreadScreenPayload.withConversationHeader(
    entry: SharedConversationHeaderEntry?
): KuiklyThreadScreenPayload {
    if (entry == null) return this
    return when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage ->
            copy(
                title = entry.title,
                subtitle = entry.subtitle,
                dmPeerName = entry.title,
                dmPeerAvatarUrl = entry.avatarWireValue,
                dmPeerAvatarLabel = entry.avatarLabel,
                dmPeerGravatarUrl = entry.gravatarUrl
            )
        KuiklyThreadSurfaceKind.Channel ->
            copy(
                title = entry.title,
                subtitle = entry.subtitle
            )
        else -> this
    }
}

private fun KuiklyThreadScreenPayload.conversationHeaderLookupIds(): List<String> =
    listOfNotNull(
        parentChannelId,
        streamChannelId,
        sourceThreadId
    ).mapNotNull { id -> id.takeIf { it.isNotBlank() } }

private fun ConversationSummary.toConversationHeaderEntry(): SharedConversationHeaderEntry? {
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
    val displayName =
        when (surfaceKind) {
            KuiklyThreadSurfaceKind.DirectMessage -> name.removePrefix("@").ifBlank { name }
            else -> name.removePrefix("#").ifBlank { name }
        }
    return SharedConversationHeaderEntry(
        ids = ids,
        surfaceKind = surfaceKind,
        title = displayName,
        subtitle = if (surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) null else subtitle?.takeIf { it.isNotBlank() },
        avatarWireValue = avatar?.wireValue ?: avatarUrl,
        avatarLabel = displayName.take(2).uppercase(),
        gravatarUrl = gravatarUrl
    )
}
