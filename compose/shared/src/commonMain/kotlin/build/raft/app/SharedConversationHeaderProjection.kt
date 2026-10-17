package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions

internal data class ConversationAccessCandidate(
    val ids: Set<String>,
    val conversationKind: ConversationKind,
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
    val dmPeerIdentity: MemberDetailIdentity? = null,
    // task #396: Channel-surface only; reply threads / DMs never show the bridge badge.
    val bridgeProvider: String? = null,
    /** Channel-surface only: the directory row's `guestVisible` / archived state. */
    val channelGuestVisible: Boolean = false,
    val channelArchived: Boolean = false,
    /**
     * The header's server `publicPageLive`, read from the session server list (never from the
     * route payload). Applied by the Thread route after projection.
     */
    val serverPublicPageLive: Boolean = false
) {
    /** Web ChatPanel.tsx:1366: the channel header icon carries the public-page globe. */
    val channelOnPublicPage: Boolean
        get() =
            surfaceKind == KuiklyThreadSurfaceKind.Channel &&
                build.raft.app.components.channelOnPublicPage(
                    serverPublicPageLive = serverPublicPageLive,
                    channelKind = channelKind,
                    guestVisible = channelGuestVisible,
                    archived = channelArchived,
                )
}

internal sealed interface ThreadHeaderProjectionInput {
    // Order IS the contract (t432 family ③): implementations list ids that name the
    // conversation rendered by the header before related ids. For a reply thread that is
    // the exact parent Channel/DM, never the child stream projection.
    val lookupIds: List<String>
    val surfaceKind: String

    fun accepts(projection: SharedConversationDirectoryProjection): Boolean

    fun acceptsForDisplay(projection: SharedConversationDirectoryProjection): Boolean

    fun displayModel(
        projection: SharedConversationDirectoryProjection?,
        contentPayload: KuiklyThreadScreenPayload
    ): ThreadHeaderDisplayModel
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
        dmPeerAvatarLabel = null
    )

internal fun ThreadHeaderProjectionInput.stableProjection(
    current: SharedConversationDirectoryProjection?,
    previous: SharedConversationDirectoryProjection?
): SharedConversationDirectoryProjection? =
    current?.takeIf(::acceptsForDisplay) ?: previous?.takeIf(::acceptsForDisplay)

internal fun ThreadHeaderProjectionInput.stableProjectionSource(
    current: SharedConversationDirectoryProjection?,
    previous: SharedConversationDirectoryProjection?
): String =
    when {
        current?.takeIf(::acceptsForDisplay) != null -> "current"
        previous?.takeIf(::acceptsForDisplay) != null -> "previous"
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
            append(" peerIdentity=").append(projection.peerIdentity.headerDiagnosticValue())
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
        append(" dmPeerIdentity=").append(dmPeerIdentity.headerDiagnosticValue())
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
        append(" dmPeerIdentity=").append(dmPeerIdentity.headerDiagnosticValue())
        append(" dmPeerName=").append(dmPeerName.headerDiagnosticValue())
        append(" dmPeerAvatar=").append(dmPeerAvatarUrl.headerAvatarDiagnosticValue())
        append(" dmPeerAvatarLabel=").append(dmPeerAvatarLabel.headerDiagnosticValue())
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

    override fun acceptsForDisplay(projection: SharedConversationDirectoryProjection): Boolean =
        accepts(projection) &&
            threadHeaderSafeTitleOrNull(projection.title, lookupIds.toSet()) != null

    override fun displayModel(
        projection: SharedConversationDirectoryProjection?,
        contentPayload: KuiklyThreadScreenPayload
    ): ThreadHeaderDisplayModel =
        projection?.takeIf(::acceptsForDisplay)
            ?.let(routePayload::threadHeaderDisplayModelFromDirectory)
            ?: contentPayload.threadHeaderFallbackDisplayModel()
}

private data class ChannelThreadHeaderProjectionInput(
    private val routePayload: KuiklyThreadScreenPayload,
    override val lookupIds: List<String>
) : ThreadHeaderProjectionInput {
    override val surfaceKind: String = KuiklyThreadSurfaceKind.Channel

    override fun accepts(projection: SharedConversationDirectoryProjection): Boolean =
        routePayload.acceptsConversationHeaderProjection(projection)

    override fun acceptsForDisplay(projection: SharedConversationDirectoryProjection): Boolean =
        accepts(projection) &&
            threadHeaderSafeTitleOrNull(projection.title, lookupIds.toSet()) != null

    override fun displayModel(
        projection: SharedConversationDirectoryProjection?,
        contentPayload: KuiklyThreadScreenPayload
    ): ThreadHeaderDisplayModel =
        projection?.takeIf(::acceptsForDisplay)
            ?.let(routePayload::threadHeaderDisplayModelFromDirectory)
            ?: contentPayload.threadHeaderFallbackDisplayModel()
}

private data class ReplyThreadHeaderProjectionInput(
    private val routePayload: KuiklyThreadScreenPayload,
    override val lookupIds: List<String>
) : ThreadHeaderProjectionInput {
    override val surfaceKind: String = KuiklyThreadSurfaceKind.ReplyThread

    override fun accepts(projection: SharedConversationDirectoryProjection): Boolean =
        routePayload.acceptsThreadHeaderDirectoryProjection(projection)

    override fun acceptsForDisplay(projection: SharedConversationDirectoryProjection): Boolean =
        accepts(projection) && projection.replyThreadParentSubtitle() != null

    override fun displayModel(
        projection: SharedConversationDirectoryProjection?,
        contentPayload: KuiklyThreadScreenPayload
    ): ThreadHeaderDisplayModel =
        projection?.takeIf(::acceptsForDisplay)
            ?.let(routePayload::threadHeaderDisplayModelFromDirectory)
            ?: contentPayload.threadHeaderFallbackDisplayModel()
}

internal fun KuiklyThreadScreenPayload.threadHeaderFallbackDisplayModel(): ThreadHeaderDisplayModel {
    return when (surfaceKind) {
        KuiklyThreadSurfaceKind.DirectMessage -> {
            val titleCandidate = loadedConversationHeaderTitleOrNull()
            val displayTitle = threadHeaderDisplayTitle(titleCandidate, surfaceKind)
            ThreadHeaderDisplayModel(
                title = displayTitle,
                subtitle = null,
                surfaceKind = surfaceKind,
                channelKind = channelKind,
                dmPeerAvatarWireValue = dmPeerAvatarUrl?.takeIf { it.isNotBlank() },
                dmPeerAvatarLabel =
                    dmPeerAvatarLabel?.takeIf { it.isNotBlank() }
                        ?: displayTitle.take(2).uppercase(),
                dmPeerIdentity = dmPeerIdentity
            )
        }
        KuiklyThreadSurfaceKind.Channel -> {
            val titleCandidate = loadedConversationHeaderTitleOrNull()
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(titleCandidate, surfaceKind),
                subtitle = loadedConversationHeaderSubtitleOrNull(),
                surfaceKind = surfaceKind,
                channelKind = channelKind
            )
        }
        else ->
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(null, surfaceKind),
                // A reply route/content slice carries parent identity, not authoritative
                // parent display state. Keep the subtitle empty until the directory resolves
                // the exact parent canonical id; a readable guess is still a misidentity.
                subtitle = null,
                surfaceKind = surfaceKind,
                channelKind = channelKind
            )
    }
}

internal fun KuiklyThreadScreenPayload.threadHeaderDisplayModelFromDirectory(
    projection: SharedConversationDirectoryProjection?
): ThreadHeaderDisplayModel {
    val directory = projection?.takeIf(::acceptsThreadHeaderDirectoryProjection)
    if (directory == null) return threadHeaderFallbackDisplayModel()
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
                dmPeerIdentity = directory.peerIdentity
            )
        }
        KuiklyThreadSurfaceKind.Channel ->
            ThreadHeaderDisplayModel(
                title = threadHeaderDisplayTitle(directory.title, surfaceKind),
                subtitle = directory.subtitle?.takeIf { it.isNotBlank() },
                surfaceKind = surfaceKind,
                channelKind = directory.channelKind,
                bridgeProvider = directory.bridgeProvider,
                channelGuestVisible = directory.guestVisible,
                channelArchived = directory.archived
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
            threadHeaderFallbackDisplayModel()
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
    // Parent shape parses through the #417 contract (accepts "direct" -> dm). An
    // unresolvable parent shape REJECTS enrichment instead of guessing Channel — the
    // header keeps its neutral payload fallback (§5: display degradation over displayed
    // misidentity; this was the last live identity fallback in the codebase, t432 PR-A).
    val resolvedParentSurfaceKind =
        parentSurfaceKind.parsedThreadParentSurfaceKindOrNull() ?: return false
    if (projection.surfaceKind != resolvedParentSurfaceKind) return false
    val exactParentChannelId = parentChannelId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    if (projection.canonicalChannelId.trim() != exactParentChannelId) return false
    if (resolvedParentSurfaceKind != KuiklyThreadSurfaceKind.Channel) return true
    val parentKind = parentChannelKind.normalizedHeaderChannelKind()
    if (parentKind == null || parentKind == KuiklyThreadSurfaceKind.Channel) return true
    return projection.channelKind.normalizedHeaderChannelKind() == parentKind
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

internal fun SharedConversationDirectoryProjection.toConversationAccessCandidate(): ConversationAccessCandidate =
    ConversationAccessCandidate(
        ids = (aliases + canonicalChannelId).filter { it.isNotBlank() }.toSet(),
        conversationKind = conversationKind,
        surfaceKind = surfaceKind,
        joined = joined,
        archived = archived,
        permissions = permissions
    )

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

private fun MemberDetailIdentity?.headerDiagnosticValue(): String =
    this
        ?.let { identity ->
            "${identity.kind.name}:${identity.memberId.value}@${identity.sourceServerId?.value.orEmpty()}"
        }
        .headerDiagnosticValue()

private fun String?.headerAvatarDiagnosticValue(): String =
    this.headerDiagnosticValue(maxLength = 180)

private fun Iterable<String>.headerDiagnosticList(maxItems: Int = 8): String {
    val values = map { it.headerDiagnosticValue() }
    val visible = values.take(maxItems)
    val suffix = if (values.size > maxItems) "+${values.size - maxItems}" else ""
    return visible.joinToString(prefix = "[", postfix = "]$suffix")
}
