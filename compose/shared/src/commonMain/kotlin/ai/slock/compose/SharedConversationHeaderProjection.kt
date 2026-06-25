package ai.slock.compose

import ai.slock.compose.data.SharedConversationDirectoryProjection
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.avatarSlotResolution
import ai.slock.compose.model.safeDisplayNameOrNull
import ai.slock.compose.model.toGravatarAvatarUrl

internal data class SharedConversationHeaderEntry(
    val ids: Set<String>,
    val surfaceKind: String,
    val channelKind: String = "channel",
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

internal data class ThreadHeaderDisplayModel(
    val title: String,
    val subtitle: String?,
    val surfaceKind: String,
    val channelKind: String,
    val dmPeerAvatarWireValue: String? = null,
    val dmPeerAvatarLabel: String = title.take(2).uppercase(),
    val dmPeerGravatarUrl: String? = null
)

internal fun KuiklyThreadScreenPayload.threadHeaderDisplayModel(
    entry: SharedConversationHeaderEntry?
): ThreadHeaderDisplayModel {
    val header = entry?.takeIf { it.surfaceKind == surfaceKind }
    return when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage -> {
            val titleCandidate =
                header?.title
                    ?: dmPeerName
                    ?: title
            val displayTitle = threadHeaderDisplayTitle(titleCandidate, surfaceKind)
            ThreadHeaderDisplayModel(
                title = displayTitle,
                subtitle = null,
                surfaceKind = surfaceKind,
                channelKind = channelKind,
                dmPeerAvatarWireValue = header?.avatarWireValue ?: dmPeerAvatarUrl,
                dmPeerAvatarLabel =
                    header?.avatarLabel?.takeIf { it.isNotBlank() }
                        ?: dmPeerAvatarLabel?.takeIf { it.isNotBlank() }
                        ?: displayTitle.take(2).uppercase(),
                dmPeerGravatarUrl = header?.gravatarUrl ?: dmPeerGravatarUrl
            )
        }
        KuiklyThreadSurfaceKind.Channel -> {
            val titleCandidate = header?.title ?: title
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(titleCandidate, surfaceKind),
                subtitle = header?.subtitle ?: subtitle,
                surfaceKind = surfaceKind,
                channelKind = header?.channelKind ?: channelKind
            )
        }
        else ->
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(title, surfaceKind),
                subtitle = subtitle,
                surfaceKind = surfaceKind,
                channelKind = channelKind
            )
    }
}

internal fun ThreadHeaderDisplayModel.threadComposerMentionScopeChannelLabel(): String? =
    when (surfaceKind) {
        KuiklyThreadSurfaceKind.ReplyThread ->
            existingThreadChannelHeaderSubtitle(subtitle)
                ?: threadChannelHeaderSubtitle(title)
        KuiklyThreadSurfaceKind.DirectMessage ->
            title.takeIf { it.isNotBlank() }?.let { raw ->
                if (raw.startsWith("@")) raw else "@${raw.removePrefix("@")}"
            }
        else -> title.takeIf { it.isNotBlank() }
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

internal fun sharedConversationHeaderStateFromDirectory(
    projections: Collection<SharedConversationDirectoryProjection>
): SharedConversationHeaderState =
    SharedConversationHeaderState(
        entriesById =
            projections
                .map { projection -> projection.toConversationHeaderEntry() }
                .flatMap { entry -> entry.ids.map { id -> id to entry } }
                .toMap()
    )

internal fun SharedConversationHeaderState.withDirectoryOverride(
    directory: SharedConversationHeaderState
): SharedConversationHeaderState =
    if (directory.entriesById.isEmpty()) {
        this
    } else {
        SharedConversationHeaderState(entriesById = entriesById + directory.entriesById)
    }

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
                subtitle = entry.subtitle,
                channelKind = entry.channelKind
            )
        else -> this
    }
}

internal fun KuiklyThreadScreenPayload.withConversationHeaderFallback(
    fallback: ConversationSummary?
): KuiklyThreadScreenPayload {
    fallback ?: return this
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
        return withReplyThreadHeaderFallback(fallback)
    }
    val headerState = sharedConversationHeaderState(listOf(fallback))
    val entry = headerState.entryFor(this) ?: return this
    val headerPayload = withConversationHeader(entry)
    return if (!fallback.joined) {
        headerPayload.copy(
            joined = false,
            canSend = fallback.permissions.canSend,
            composer = headerPayload.composer.copy(enabled = false)
        )
    } else {
        headerPayload
    }
}

internal fun KuiklyThreadScreenPayload.withReplyThreadHeaderFallback(
    fallback: ConversationSummary?
): KuiklyThreadScreenPayload {
    fallback ?: return this
    if (surfaceKind != KuiklyThreadSurfaceKind.ReplyThread) return this
    val fallbackSubtitle =
        existingThreadChannelHeaderSubtitle(subtitle)
            ?: existingThreadChannelHeaderSubtitle(fallback.subtitle)
            ?: threadChannelHeaderSubtitle(fallback.name)
            ?: return this
    return if (fallbackSubtitle == subtitle) this else copy(subtitle = fallbackSubtitle)
}

internal fun KuiklyThreadScreenPayload.conversationHeaderLookupIds(): List<String> =
    listOfNotNull(
        parentChannelId,
        streamChannelId,
        sourceThreadId
    ).mapNotNull { id -> id.takeIf { it.isNotBlank() } }

private fun SharedConversationDirectoryProjection.toConversationHeaderEntry(): SharedConversationHeaderEntry =
    SharedConversationHeaderEntry(
        ids = (aliases + canonicalChannelId).filter { it.isNotBlank() }.toSet(),
        surfaceKind = surfaceKind,
        channelKind = channelKind,
        title = title,
        subtitle = subtitle?.takeIf { it.isNotBlank() },
        avatarWireValue = avatarWireValue?.takeIf { it.isNotBlank() },
        avatarLabel = avatarLabel.takeIf { it.isNotBlank() } ?: title.take(2).uppercase()
    )

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
    val displayName = safeDisplayNameOrNull() ?: threadHeaderFallbackTitle(surfaceKind)
    val avatar = avatarSlotResolution()
    return SharedConversationHeaderEntry(
        ids = ids,
        surfaceKind = surfaceKind,
        channelKind = channelType?.takeIf { it.isNotBlank() } ?: kind.name.lowercase(),
        title = displayName,
        subtitle = if (surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) null else subtitle?.takeIf { it.isNotBlank() },
        avatarWireValue = avatar.effectiveAvatarWireValue,
        avatarLabel = displayName.take(2).uppercase(),
        gravatarUrl = avatar.gravatarHash?.toGravatarAvatarUrl()
    )
}
