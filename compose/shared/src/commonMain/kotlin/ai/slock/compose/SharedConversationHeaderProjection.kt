package ai.slock.compose

import ai.slock.compose.data.SharedConversationDirectoryProjection
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.avatarSlotResolution
import ai.slock.compose.model.isGenericDisplayName
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
    val dmPeerMemberId: String? = null,
    val gravatarUrl: String? = null
)

internal data class ThreadHeaderDisplayModel(
    val title: String,
    val subtitle: String?,
    val surfaceKind: String,
    val channelKind: String,
    val dmPeerAvatarWireValue: String? = null,
    val dmPeerAvatarLabel: String = title.take(2).uppercase(),
    val dmPeerMemberId: String? = null,
    val dmPeerGravatarUrl: String? = null
)

internal sealed interface ThreadHeaderProjectionInput {
    val lookupIds: List<String>
    val surfaceKind: String

    fun accepts(projection: SharedConversationDirectoryProjection): Boolean

    fun displayModel(projection: SharedConversationDirectoryProjection?): ThreadHeaderDisplayModel
}

internal fun KuiklyThreadScreenPayload.threadHeaderProjectionInput(): ThreadHeaderProjectionInput =
    when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage ->
            DirectMessageThreadHeaderProjectionInput(
                routePayload = this,
                lookupIds = conversationHeaderLookupIds()
            )
        KuiklyThreadSurfaceKind.Channel ->
            ChannelThreadHeaderProjectionInput(
                routePayload = this,
                lookupIds = conversationHeaderLookupIds()
            )
        else ->
            ReplyThreadHeaderProjectionInput(
                routePayload = this,
                lookupIds = conversationHeaderLookupIds()
            )
    }

internal fun ThreadHeaderProjectionInput.stableProjection(
    current: SharedConversationDirectoryProjection?,
    previous: SharedConversationDirectoryProjection?
): SharedConversationDirectoryProjection? =
    current?.takeIf(::accepts) ?: previous?.takeIf(::accepts)

private data class DirectMessageThreadHeaderProjectionInput(
    private val routePayload: KuiklyThreadScreenPayload,
    override val lookupIds: List<String>
) : ThreadHeaderProjectionInput {
    override val surfaceKind: String = KuiklyThreadSurfaceKind.DirectMessage

    override fun accepts(projection: SharedConversationDirectoryProjection): Boolean =
        routePayload.acceptsConversationHeaderProjection(projection)

    override fun displayModel(projection: SharedConversationDirectoryProjection?): ThreadHeaderDisplayModel =
        routePayload.threadHeaderDisplayModelFromDirectory(
            projection = projection?.takeIf(::accepts)
        )
}

private data class ChannelThreadHeaderProjectionInput(
    private val routePayload: KuiklyThreadScreenPayload,
    override val lookupIds: List<String>
) : ThreadHeaderProjectionInput {
    override val surfaceKind: String = KuiklyThreadSurfaceKind.Channel

    override fun accepts(projection: SharedConversationDirectoryProjection): Boolean =
        routePayload.acceptsConversationHeaderProjection(projection)

    override fun displayModel(projection: SharedConversationDirectoryProjection?): ThreadHeaderDisplayModel =
        routePayload.threadHeaderDisplayModelFromDirectory(
            projection = projection?.takeIf(::accepts)
        )
}

private data class ReplyThreadHeaderProjectionInput(
    private val routePayload: KuiklyThreadScreenPayload,
    override val lookupIds: List<String>
) : ThreadHeaderProjectionInput {
    override val surfaceKind: String = KuiklyThreadSurfaceKind.ReplyThread

    override fun accepts(projection: SharedConversationDirectoryProjection): Boolean =
        routePayload.acceptsConversationHeaderProjection(projection)

    override fun displayModel(projection: SharedConversationDirectoryProjection?): ThreadHeaderDisplayModel =
        routePayload.threadHeaderDisplayModelFromDirectory(
            projection = projection?.takeIf(::accepts)
        )
}

internal fun KuiklyThreadScreenPayload.threadHeaderDisplayModel(
    entry: SharedConversationHeaderEntry?
): ThreadHeaderDisplayModel {
    val header = entry?.takeIf { it.surfaceKind == surfaceKind }
    return when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage -> {
            val titleCandidate = header?.title
            val displayTitle = threadHeaderDisplayTitle(titleCandidate, surfaceKind)
            ThreadHeaderDisplayModel(
                title = displayTitle,
                subtitle = null,
                surfaceKind = surfaceKind,
                channelKind = channelKind,
                dmPeerAvatarWireValue = header?.avatarWireValue,
                dmPeerAvatarLabel =
                    header?.avatarLabel?.takeIf { it.isNotBlank() }
                        ?: displayTitle.take(2).uppercase(),
                dmPeerMemberId = header?.dmPeerMemberId ?: dmPeerMemberId,
                dmPeerGravatarUrl = header?.gravatarUrl
            )
        }
        KuiklyThreadSurfaceKind.Channel -> {
            val titleCandidate = header?.title
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(titleCandidate, surfaceKind),
                subtitle = header?.subtitle,
                surfaceKind = surfaceKind,
                channelKind = header?.channelKind ?: channelKind
            )
        }
        else ->
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(null, surfaceKind),
                subtitle = subtitle,
                surfaceKind = surfaceKind,
                channelKind = channelKind
            )
    }
}

internal fun KuiklyThreadScreenPayload.threadHeaderDisplayModelFromDirectory(
    projection: SharedConversationDirectoryProjection?,
    fallbackEntry: SharedConversationHeaderEntry? = null
): ThreadHeaderDisplayModel {
    val directory = projection?.takeIf(::acceptsConversationHeaderProjection)
    if (directory == null) return threadHeaderDisplayModel(fallbackEntry)
    return when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage -> {
            val displayTitle = threadHeaderDisplayTitle(directory.title, surfaceKind)
            ThreadHeaderDisplayModel(
                title = displayTitle,
                subtitle = null,
                surfaceKind = surfaceKind,
                channelKind = directory.channelKind,
                dmPeerAvatarWireValue = directory.avatarWireValue,
                dmPeerAvatarLabel =
                    directory.avatarLabel.takeIf { it.isNotBlank() }
                        ?: displayTitle.take(2).uppercase(),
                dmPeerMemberId = directory.peerMemberId ?: dmPeerMemberId,
                dmPeerGravatarUrl = null
            )
        }
        KuiklyThreadSurfaceKind.Channel ->
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(directory.title, surfaceKind),
                subtitle = directory.subtitle?.takeIf { it.isNotBlank() },
                surfaceKind = surfaceKind,
                channelKind = directory.channelKind
            )
        else -> threadHeaderDisplayModel(fallbackEntry)
    }
}

internal fun KuiklyThreadScreenPayload.acceptsConversationHeaderProjection(
    projection: SharedConversationDirectoryProjection
): Boolean {
    if (projection.surfaceKind != surfaceKind) return false
    if (surfaceKind != KuiklyThreadSurfaceKind.Channel) return true
    val payloadChannelKind =
        resolveThreadRouteIdentity().channelKind.normalizedHeaderChannelKind()
            ?: channelKind.normalizedHeaderChannelKind()
    if (payloadChannelKind == null || payloadChannelKind == KuiklyThreadSurfaceKind.Channel) return true
    return projection.channelKind.normalizedHeaderChannelKind() == payloadChannelKind
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

internal fun KuiklyThreadScreenPayload.conversationHeaderEntryFrom(
    conversation: ConversationSummary?
): SharedConversationHeaderEntry? {
    val entry = conversation?.toConversationHeaderEntry() ?: return null
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread || entry.surfaceKind != surfaceKind) return null
    val lookupIds = conversationHeaderLookupIds()
    return entry.takeIf { lookupIds.any(entry.ids::contains) }
}

internal fun KuiklyThreadScreenPayload.withConversationAccessFallback(
    fallback: ConversationSummary?
): KuiklyThreadScreenPayload {
    fallback ?: return this
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) return this
    conversationHeaderEntryFrom(fallback) ?: return this
    return if (!fallback.joined) {
        copy(
            joined = false,
            canSend = fallback.permissions.canSend,
            composer = composer.copy(enabled = false)
        )
    } else {
        this
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
        avatarLabel = avatarLabel.takeIf { it.isNotBlank() } ?: title.take(2).uppercase(),
        dmPeerMemberId = peerMemberId?.takeIf { it.isNotBlank() }
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
    val displayName = safeDisplayNameOrNull()?.takeUnless { isGenericDisplayName(it) } ?: return null
    val avatar = avatarSlotResolution()
    val acceptedGravatarUrl =
        gravatarUrl
            ?.takeUnless {
                surfaceKind == KuiklyThreadSurfaceKind.DirectMessage &&
                    directMessagePeerType.equals("agent", ignoreCase = true)
            }
    return SharedConversationHeaderEntry(
        ids = ids,
        surfaceKind = surfaceKind,
        channelKind = resolveThreadRouteIdentity().channelKind ?: channelType?.takeIf { it.isNotBlank() } ?: kind.name.lowercase(),
        title = displayName,
        subtitle = if (surfaceKind == KuiklyThreadSurfaceKind.DirectMessage) null else subtitle?.takeIf { it.isNotBlank() },
        avatarWireValue = avatar.effectiveAvatarWireValue,
        avatarLabel = displayName.take(2).uppercase(),
        dmPeerMemberId = presenceMemberId?.value,
        gravatarUrl = acceptedGravatarUrl ?: avatar.gravatarHash?.toGravatarAvatarUrl()
    )
}

private fun String?.normalizedHeaderChannelKind(): String? =
    this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
