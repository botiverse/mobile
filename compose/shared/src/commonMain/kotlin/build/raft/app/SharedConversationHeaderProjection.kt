package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.isGenericDisplayName
import build.raft.app.model.safeDisplayNameOrNull

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

internal data class ConversationAccessCandidate(
    val ids: Set<String>,
    val surfaceKind: String,
    val joined: Boolean,
    val archived: Boolean,
    val permissions: ConversationPermissions
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

internal fun KuiklyThreadScreenPayload.threadHeaderProjectionRoutePayload(): KuiklyThreadScreenPayload =
    copy(
        title = "",
        subtitle = null,
        status = "loading",
        messages = emptyList(),
        originMessage = null,
        originPreview = null,
        originStatus = "none",
        tasks = emptyList(),
        members = emptyList(),
        memberCandidates = emptyList(),
        dmPeerName = null,
        dmPeerAvatarUrl = null,
        dmPeerAvatarLabel = null,
        dmPeerGravatarUrl = null
    )

internal fun ThreadHeaderProjectionInput.stableProjection(
    current: SharedConversationDirectoryProjection?,
    previous: SharedConversationDirectoryProjection?
): SharedConversationDirectoryProjection? =
    current?.takeIf(::accepts) ?: previous?.takeIf(::accepts)

internal fun ThreadHeaderProjectionInput.stableProjectionSource(
    current: SharedConversationDirectoryProjection?,
    previous: SharedConversationDirectoryProjection?
): String =
    when {
        current?.takeIf(::accepts) != null -> "current"
        previous?.takeIf(::accepts) != null -> "previous"
        else -> "fallback"
    }

internal fun ThreadHeaderProjectionInput.headerProjectionDiagnostics(): String =
    buildString {
        append("surface=").append(surfaceKind.headerDiagnosticValue())
        append(" lookupIds=").append(lookupIds.headerDiagnosticList())
    }

internal fun Map<String, SharedConversationDirectoryProjection>.headerProjectionMapDiagnostics(): String =
    buildString {
        append("count=").append(size)
        append(" keys=").append(keys.sorted().headerDiagnosticList())
        if (isNotEmpty()) {
            append(" entries=")
            append(
                entries
                    .sortedBy { it.key }
                    .take(6)
                    .joinToString(prefix = "[", postfix = "]") { (key, projection) ->
                        "${key.headerDiagnosticValue()}->${projection.headerProjectionDiagnostics()}"
                    }
            )
            if (size > 6) append("+").append(size - 6)
        }
    }

internal fun SharedConversationDirectoryProjection?.headerProjectionDiagnostics(): String =
    this?.let { projection ->
        buildString {
            append("{server=").append(projection.serverId.headerDiagnosticValue())
            append(" canonical=").append(projection.canonicalChannelId.headerDiagnosticValue())
            append(" aliases=").append(projection.aliases.sorted().headerDiagnosticList())
            append(" surface=").append(projection.surfaceKind.headerDiagnosticValue())
            append(" channelKind=").append(projection.channelKind.headerDiagnosticValue())
            append(" title=").append(projection.title.headerDiagnosticValue())
            append(" subtitle=").append(projection.subtitle.headerDiagnosticValue())
            append(" avatar=").append(projection.avatarWireValue.headerAvatarDiagnosticValue())
            append(" avatarLabel=").append(projection.avatarLabel.headerDiagnosticValue())
            append(" peerMemberId=").append(projection.peerMemberId.headerDiagnosticValue())
            append(" parentChannelId=").append(projection.parentChannelId.headerDiagnosticValue())
            append(" parentMessageId=").append(projection.parentMessageId.headerDiagnosticValue())
            append(" joined=").append(projection.joined)
            append(" archived=").append(projection.archived)
            append("}")
        }
    } ?: "null"

internal fun ThreadHeaderDisplayModel.headerDisplayDiagnostics(): String =
    buildString {
        append("{title=").append(title.headerDiagnosticValue())
        append(" subtitle=").append(subtitle.headerDiagnosticValue())
        append(" surface=").append(surfaceKind.headerDiagnosticValue())
        append(" channelKind=").append(channelKind.headerDiagnosticValue())
        append(" dmPeerAvatar=").append(dmPeerAvatarWireValue.headerAvatarDiagnosticValue())
        append(" dmPeerAvatarLabel=").append(dmPeerAvatarLabel.headerDiagnosticValue())
        append(" dmPeerMemberId=").append(dmPeerMemberId.headerDiagnosticValue())
        append(" dmPeerGravatar=").append(dmPeerGravatarUrl.headerAvatarDiagnosticValue())
        append("}")
    }

internal fun KuiklyThreadScreenPayload.headerRouteDiagnostics(): String =
    buildString {
        append("{instanceId=").append(instanceId.headerDiagnosticValue())
        append(" status=").append(status.headerDiagnosticValue())
        append(" surface=").append(surfaceKind.headerDiagnosticValue())
        append(" channelKind=").append(channelKind.headerDiagnosticValue())
        append(" sourceThreadId=").append(sourceThreadId.headerDiagnosticValue())
        append(" streamChannelId=").append(streamChannelId.headerDiagnosticValue())
        append(" parentChannelId=").append(parentChannelId.headerDiagnosticValue())
        append(" parentMessageId=").append(parentMessageId.headerDiagnosticValue())
        append(" parentSurface=").append(parentSurfaceKind.headerDiagnosticValue())
        append(" parentChannelKind=").append(parentChannelKind.headerDiagnosticValue())
        append(" dmPeerMemberId=").append(dmPeerMemberId.headerDiagnosticValue())
        append(" dmPeerName=").append(dmPeerName.headerDiagnosticValue())
        append(" dmPeerAvatar=").append(dmPeerAvatarUrl.headerAvatarDiagnosticValue())
        append(" dmPeerAvatarLabel=").append(dmPeerAvatarLabel.headerDiagnosticValue())
        append(" dmPeerGravatar=").append(dmPeerGravatarUrl.headerAvatarDiagnosticValue())
        append(" title=").append(title.headerDiagnosticValue())
        append(" subtitle=").append(subtitle.headerDiagnosticValue())
        append("}")
    }

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
        routePayload.acceptsThreadHeaderDirectoryProjection(projection)

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
            val titleCandidate = header?.title ?: loadedConversationHeaderTitleOrNull()
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
            val titleCandidate = header?.title ?: loadedConversationHeaderTitleOrNull()
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(titleCandidate, surfaceKind),
                subtitle = header?.subtitle ?: loadedConversationHeaderSubtitleOrNull(),
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

internal fun ThreadHeaderProjectionInput.displayModel(
    projection: SharedConversationDirectoryProjection?,
    contentPayload: KuiklyThreadScreenPayload
): ThreadHeaderDisplayModel {
    val accepted = projection?.takeIf(::accepts)
    return if (accepted != null) {
        displayModel(accepted)
    } else {
        contentPayload.threadHeaderDisplayModel(null)
    }
}

internal fun KuiklyThreadScreenPayload.threadHeaderDisplayModelFromDirectory(
    projection: SharedConversationDirectoryProjection?,
    fallbackEntry: SharedConversationHeaderEntry? = null
): ThreadHeaderDisplayModel {
    val directory = projection?.takeIf(::acceptsThreadHeaderDirectoryProjection)
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
                dmPeerGravatarUrl = dmPeerGravatarUrl
            )
        }
        KuiklyThreadSurfaceKind.Channel ->
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(directory.title, surfaceKind),
                subtitle = directory.subtitle?.takeIf { it.isNotBlank() },
                surfaceKind = surfaceKind,
                channelKind = directory.channelKind
            )
        KuiklyThreadSurfaceKind.ReplyThread ->
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(null, surfaceKind),
                subtitle = directory.replyThreadParentSubtitle(),
                surfaceKind = surfaceKind,
                channelKind = parentChannelKind.normalizedHeaderChannelKind()
                    ?: directory.channelKind.normalizedHeaderChannelKind()
                    ?: channelKind
            )
        else ->
            threadHeaderDisplayModel(fallbackEntry)
    }
}

private fun KuiklyThreadScreenPayload.loadedConversationHeaderTitleOrNull(): String? {
    if (!hasLoadedConversationHeaderContent()) return null
    return threadHeaderSafeTitleOrNull(title, conversationHeaderLookupIds().toSet())
        ?.takeUnless { it.isGenericThreadHeaderFallbackLabel() }
}

private fun KuiklyThreadScreenPayload.loadedConversationHeaderSubtitleOrNull(): String? {
    if (!hasLoadedConversationHeaderContent()) return null
    return subtitle?.takeIf { it.isNotBlank() }
}

private fun KuiklyThreadScreenPayload.hasLoadedConversationHeaderContent(): Boolean =
    status == "content" || status == "empty"

internal fun KuiklyThreadScreenPayload.acceptsThreadHeaderDirectoryProjection(
    projection: SharedConversationDirectoryProjection
): Boolean =
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) {
        acceptsReplyThreadParentHeaderProjection(projection)
    } else {
        acceptsConversationHeaderProjection(projection)
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

private fun KuiklyThreadScreenPayload.acceptsReplyThreadParentHeaderProjection(
    projection: SharedConversationDirectoryProjection
): Boolean {
    val resolvedParentSurfaceKind = parentSurfaceKind.normalizedReplyThreadParentSurfaceKind()
    if (projection.surfaceKind != resolvedParentSurfaceKind) return false
    val lookupIds = conversationHeaderLookupIds().toSet()
    if (lookupIds.isNotEmpty() && projection.headerProjectionIds().none { it in lookupIds }) return false
    if (resolvedParentSurfaceKind != KuiklyThreadSurfaceKind.Channel) return true
    val parentKind = parentChannelKind.normalizedHeaderChannelKind()
    if (parentKind == null || parentKind == KuiklyThreadSurfaceKind.Channel) return true
    return projection.channelKind.normalizedHeaderChannelKind() == parentKind
}

private fun SharedConversationDirectoryProjection.headerProjectionIds(): Set<String> =
    (aliases + canonicalChannelId).filter { it.isNotBlank() }.toSet()

private fun String?.normalizedReplyThreadParentSurfaceKind(): String =
    when (this?.trim()?.lowercase()) {
        KuiklyThreadSurfaceKind.DirectMessage,
        "direct_message",
        "direct-message",
        "directmessage" -> KuiklyThreadSurfaceKind.DirectMessage
        else -> KuiklyThreadSurfaceKind.Channel
    }

private fun SharedConversationDirectoryProjection.replyThreadParentSubtitle(): String? =
    when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage -> directMessageReplyThreadSubtitle(title)
        else ->
            existingThreadChannelHeaderSubtitle(subtitle)
                ?: threadChannelHeaderSubtitle(title)
    }

private fun directMessageReplyThreadSubtitle(title: String?): String? {
    val display = threadHeaderSafeTitleOrNull(title)?.removePrefix("@")
        ?: return null
    return "@$display"
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
    entry: SharedConversationHeaderEntry?
): SharedConversationHeaderEntry? {
    val candidate = entry ?: return null
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread || candidate.surfaceKind != surfaceKind) return null
    val lookupIds = conversationHeaderLookupIds()
    return candidate.takeIf { lookupIds.any(candidate.ids::contains) }
}

internal fun KuiklyThreadScreenPayload.withConversationAccessFallback(
    fallback: ConversationAccessCandidate?
): KuiklyThreadScreenPayload {
    fallback ?: return this
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread) return this
    if (!acceptsConversationAccessCandidate(fallback)) return this
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

internal fun KuiklyThreadScreenPayload.acceptsConversationAccessCandidate(
    candidate: ConversationAccessCandidate
): Boolean {
    if (surfaceKind == KuiklyThreadSurfaceKind.ReplyThread || candidate.surfaceKind != surfaceKind) return false
    val lookupIds = conversationHeaderLookupIds()
    return lookupIds.any(candidate.ids::contains)
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
        dmPeerMemberId = presenceMemberId?.value,
        gravatarUrl = null
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

private fun String?.normalizedHeaderChannelKind(): String? =
    this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

private fun String?.headerDiagnosticValue(maxLength: Int = 120): String =
    this
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(maxLength)
        ?: "null"

private fun String?.headerAvatarDiagnosticValue(): String =
    this.headerDiagnosticValue(maxLength = 180)

private fun Iterable<String>.headerDiagnosticList(maxItems: Int = 8): String {
    val values = map { it.headerDiagnosticValue() }
    val visible = values.take(maxItems)
    val suffix = if (values.size > maxItems) "+${values.size - maxItems}" else ""
    return visible.joinToString(prefix = "[", postfix = "]$suffix")
}
