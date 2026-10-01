package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelCapabilities
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Canonical Slock home parser.
 *
 * Mirrors the React server contracts directly. [SlockHomeApiClient.homePayload]
 * owns the API boundary and decodes strict typed DTOs with no envelope
 * unwrapping and no field aliasing before this parser maps them into domain
 * state.
 *
 *   GET /api/.../channels      -> Array<Channel>             (channelService.listChannels)
 *   GET /api/.../channels/dm   -> Array<DMChannel>           (channelService.listDMChannels)
 *   GET /api/.../channels/unread -> Record<channelId, number> (channelService.getUnreadCounts)
 *
 * See packages/server/src/services/channelService.ts for the source of truth.
 */
private val homeJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * task #396: presentation-only external bridge metadata attached by the server's
 * `channelService.attachExternalBridgeMetadata` to `GET /channels` and `GET /channels/:id`
 * (`bridge?: { provider: "slack"; providerConversationId; state: "active" | "paused" | "quarantined" }`).
 * Secret-free; mobile only reads [provider] to render the `[Slack]` badge (Web Sidebar parity).
 */
@Serializable
data class HomeChannelBridgeWireDto(
    val provider: String? = null,
    val providerConversationId: String? = null,
    val state: String? = null,
)

@Serializable
data class HomeChannelWireDto(
    val id: String,
    val name: String,
    val type: String,
    val description: String? = null,
    val archivedAt: String? = null,
    val joined: Boolean = true,
    val lastMessageAt: String? = null,
    val jointChannelId: String? = null,
    val jointRole: String? = null,
    val jointPeerServerId: String? = null,
    val jointPeerServerName: String? = null,
    val jointPeerServerSlug: String? = null,
    val jointPeerStatus: String? = null,
    val jointBillingLocked: Boolean? = null,
    val jointOverLimitGraceEndsAt: String? = null,
    val bridge: HomeChannelBridgeWireDto? = null,
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
    val activityMuteSupported: Boolean = false,
    val maxReadSeq: Long? = null,
    val readStateVersion: Long? = null,
    val channelCapabilities: WireChannelCapabilities? = null,
)

@Serializable
data class HomeDirectMessageWireDto(
    val id: String,
    val name: String,
    val type: String,
    val description: String? = null,
    val lastMessageAt: String? = null,
    val peerType: String,
    val peerId: String,
    val peerName: String,
    val peerDisplayName: String? = null,
    val peerDescription: String? = null,
    val peerAvatarUrl: String? = null,
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
    val activityMuteSupported: Boolean = false,
    val maxReadSeq: Long? = null,
    val readStateVersion: Long? = null,
    val lastMessagePreview: String? = null,
    val lastMessageSenderName: String? = null,
)

@Serializable
private data class HomeChannelDetailWireDto(
    val id: String,
    val name: String,
    val type: String,
    val description: String? = null,
    val archivedAt: String? = null,
    val joined: Boolean = true,
    val lastMessageAt: String? = null,
    val peerType: String? = null,
    val peerId: String? = null,
    val peerName: String? = null,
    val peerDisplayName: String? = null,
    val peerDescription: String? = null,
    val peerAvatarUrl: String? = null,
    val jointChannelId: String? = null,
    val jointRole: String? = null,
    val jointPeerServerId: String? = null,
    val jointPeerServerName: String? = null,
    val jointPeerServerSlug: String? = null,
    val jointPeerStatus: String? = null,
    val jointBillingLocked: Boolean? = null,
    val jointOverLimitGraceEndsAt: String? = null,
    val bridge: HomeChannelBridgeWireDto? = null,
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
    val activityMuteSupported: Boolean = false,
    val maxReadSeq: Long? = null,
    val readStateVersion: Long? = null,
    val lastMessagePreview: String? = null,
    val lastMessageSenderName: String? = null,
    val channelCapabilities: WireChannelCapabilities? = null,
)

/** Wire shape of `channelCapabilities` on channel records (web parity:
 *  `channel.channelCapabilities` in ChannelMembers.tsx). All keys optional —
 *  an absent key means "not resolved", not "denied". */
@Serializable
data class WireChannelCapabilities(
    val addChannelMembers: Boolean? = null,
    val removeChannelMembers: Boolean? = null,
    val changeChannelMemberRoles: Boolean? = null,
) {
    fun toModel(): ChannelCapabilities =
        ChannelCapabilities(
            addChannelMembers = addChannelMembers,
            removeChannelMembers = removeChannelMembers,
            changeChannelMemberRoles = changeChannelMemberRoles
        )
}

internal fun parseSlockChannelDetail(
    bodyText: String,
    warningLogger: (String) -> Unit = {}
): HomeConversationRow? {
    val wire = runCatching {
        homeJson.decodeFromString(HomeChannelDetailWireDto.serializer(), bodyText)
    }.getOrNull() ?: return null
    if (wire.id.isBlank() || wire.type.isBlank()) return null
    return if (wire.type.equals("dm", ignoreCase = true)) {
        val peerId = wire.peerId?.takeIf { it.isNotBlank() } ?: return null
        val peerName = wire.peerName?.takeIf { it.isNotBlank() } ?: return null
        HomeDirectMessageWireDto(
            id = wire.id,
            name = wire.name,
            type = wire.type,
            description = wire.description,
            lastMessageAt = wire.lastMessageAt,
            peerType = wire.peerType?.takeIf { it.isNotBlank() } ?: return null,
            peerId = peerId,
            peerName = peerName,
            peerDisplayName = wire.peerDisplayName,
            peerDescription = wire.peerDescription,
            peerAvatarUrl = wire.peerAvatarUrl,
            activityMuted = wire.activityMuted,
            muteFromSeq = wire.muteFromSeq,
            activityMuteSupported = wire.activityMuteSupported,
            maxReadSeq = wire.maxReadSeq,
            readStateVersion = wire.readStateVersion,
            lastMessagePreview = wire.lastMessagePreview,
            lastMessageSenderName = wire.lastMessageSenderName,
        ).toDirectMessageRow(warningLogger)
    } else {
        HomeChannelWireDto(
            id = wire.id,
            name = wire.name,
            type = wire.type,
            description = wire.description,
            archivedAt = wire.archivedAt,
            joined = wire.joined,
            lastMessageAt = wire.lastMessageAt,
            jointChannelId = wire.jointChannelId,
            jointRole = wire.jointRole,
            jointPeerServerId = wire.jointPeerServerId,
            jointPeerServerName = wire.jointPeerServerName,
            jointPeerServerSlug = wire.jointPeerServerSlug,
            jointPeerStatus = wire.jointPeerStatus,
            jointBillingLocked = wire.jointBillingLocked,
            jointOverLimitGraceEndsAt = wire.jointOverLimitGraceEndsAt,
            bridge = wire.bridge,
            activityMuted = wire.activityMuted,
            muteFromSeq = wire.muteFromSeq,
            activityMuteSupported = wire.activityMuteSupported,
            maxReadSeq = wire.maxReadSeq,
            readStateVersion = wire.readStateVersion,
            channelCapabilities = wire.channelCapabilities,
        ).toChannelRow()
    }
}

fun parseSlockHome(
    payload: HomeNetworkPayload,
    session: StoredAuthSession,
    warningLogger: (String) -> Unit = {}
): DataResult<HomeSnapshot> {
    val currentServer = session.servers.firstOrNull { it.id == payload.serverId.value }?.toServerSummary()
        ?: session.currentServerId?.let { id ->
            session.servers.firstOrNull { it.id == id }?.toServerSummary()
        }
        ?: return DataResult.Empty(EmptyReason.NoChannels)
    val currentMember = session.user.toMemberProfile()

    val channels = payload.channels
        .filter { it.archivedAt == null }
        .map { it.toChannelRow() }
        .dedupeHomeChannels()

    val directMessages = payload.directMessages
        .map { it.toDirectMessageRow(warningLogger) }
        .dedupeHomeDirectMessages()

    if (channels.isEmpty() && directMessages.isEmpty()) {
        return DataResult.Empty(EmptyReason.NoChannels)
    }

    val unreadCounts = payload.unreadCounts
    val channelsWithUnread = channels.applyChannelUnreadCounts(unreadCounts)
    val dmsWithUnread = directMessages.applyDirectMessageUnreadCounts(unreadCounts)
    val channelUnreadTotal = channelsWithUnread.sumOf { it.unreadCount } + dmsWithUnread.sumOf { it.unreadCount }
    val serverUnreadTotal =
        payload.serverUnreadSummary
            .firstOrNull { it.serverId == currentServer.id.value }
            ?.unreadCount
    val serverUnreadCounts =
        payload.serverUnreadSummary
            .associate { it.serverId to it.unreadCount.coerceAtLeast(0) }
    val serverActivityUnreadCounts =
        payload.serverUnreadSummary
            .mapNotNull { summary ->
                val serverId = summary.serverId.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                summary.knownActivityUnreadCount?.let { serverId to it }
            }
            .toMap()
    val serverPushMutedByServer =
        payload.serverUnreadSummary
            .mapNotNull { summary ->
                summary.serverId.trim().takeIf { it.isNotEmpty() }?.let { it to summary.serverPushMuted }
            }
            .toMap()
    val unreadTotal = serverUnreadTotal ?: channelUnreadTotal
    val unreadSource =
        if (serverUnreadTotal != null) AppBadgeSource.SERVER_UNREAD_SUMMARY
        else AppBadgeSource.CHANNEL_DM_FALLBACK
    val server = currentServer.copy(unreadCount = unreadTotal)

    return DataResult.Content(
        HomeSnapshot(
            server = server,
            currentMember = currentMember,
            channels = channelsWithUnread,
            directMessages = dmsWithUnread,
            unreadTotal = unreadTotal,
            unreadSource = unreadSource,
            serverUnreadCounts = serverUnreadCounts,
            serverActivityUnreadCounts = serverActivityUnreadCounts,
            serverPushMutedByServer = serverPushMutedByServer,
            serverUnreadSummaryAvailable = payload.serverUnreadSummaryAvailable,
            // task #831: carry unread availability through so the Store keeps
            // prior badges instead of clobbering them to zero when the unread
            // endpoints were unavailable.
            unreadEnrichmentAvailable = payload.unreadEnrichmentAvailable
        )
    )
}

fun parseSharedChannelSnapshot(
    bodyText: String,
    server: ServerSummary,
): ChannelSnapshot? = runCatching {
    ChannelSnapshot(
        server = server,
        channels = homeJson.decodeFromString(ListSerializer(HomeChannelWireDto.serializer()), bodyText)
            .filter { it.archivedAt == null }
            .map { it.toChannelRow() }
            .dedupeHomeChannels(),
    )
}.getOrNull()

fun parseSharedDirectMessageSnapshot(
    bodyText: String,
    server: ServerSummary,
    warningLogger: (String) -> Unit = {},
): DirectMessageSnapshot? = runCatching {
    DirectMessageSnapshot(
        server = server,
        directMessages = homeJson.decodeFromString(ListSerializer(HomeDirectMessageWireDto.serializer()), bodyText)
            .map { it.toDirectMessageRow(warningLogger) }
            .dedupeHomeDirectMessages(),
    )
}.getOrNull()

fun parseSharedCreateChannelResult(bodyText: String): CreateChannelResult? = runCatching {
    CreateChannelResult(
        channel = homeJson.decodeFromString(HomeChannelWireDto.serializer(), bodyText)
            .toChannelRow()
    )
}.getOrNull()

fun parseSharedChannelProfileUpdateResult(bodyText: String): ChannelProfileUpdateResult? = runCatching {
    val channel = homeJson.decodeFromString(HomeChannelWireDto.serializer(), bodyText).toChannelRow()
    ChannelProfileUpdateResult(
        channelId = channel.channelId ?: error("Update channel response missing channel id"),
        name = channel.name,
        description = channel.subtitle
    )
}.getOrNull()

fun parseSharedOpenDirectMessageResult(bodyText: String): OpenDirectMessageResult? = runCatching {
    OpenDirectMessageResult(
        conversation = homeJson.decodeFromString(HomeDirectMessageWireDto.serializer(), bodyText)
            .toDirectMessageRow { _ -> },
        directMessages = emptyList(),
    )
}.getOrNull()

fun parseSharedChannelArchiveResult(
    bodyText: String,
    fallbackChannelId: ChannelId,
): ChannelArchiveResult? = runCatching {
    val channel = homeJson.decodeFromString(HomeChannelWireDto.serializer(), bodyText)
        .toChannelRow()
        .copy(archived = true)
    ChannelArchiveResult(
        channelId = channel.channelId ?: fallbackChannelId,
        archived = true,
        channel = channel,
    )
}.getOrNull()

@Serializable
private data class WireOkResponse(
    val ok: Boolean = false,
    val seq: Long? = null,
    val unreadCount: Int = 0,
    val markedCount: Int = 0,
)

fun parseSharedChannelJoinResult(bodyText: String, channelId: ChannelId): ChannelJoinResult? =
    runCatching {
        val decoded = homeJson.decodeFromString(WireOkResponse.serializer(), bodyText)
        if (decoded.ok) ChannelJoinResult(channelId = channelId, joined = true) else null
    }.getOrNull()

fun parseSharedChannelLeaveResult(bodyText: String, channelId: ChannelId): ChannelLeaveResult? =
    runCatching {
        val decoded = homeJson.decodeFromString(WireOkResponse.serializer(), bodyText)
        if (decoded.ok) ChannelLeaveResult(channelId = channelId, left = true) else null
    }.getOrNull()

fun parseSharedChannelDeleteResult(bodyText: String, channelId: ChannelId): ChannelDeleteResult? =
    runCatching {
        val decoded = homeJson.decodeFromString(WireOkResponse.serializer(), bodyText)
        if (decoded.ok) ChannelDeleteResult(channelId = channelId, deleted = true) else null
    }.getOrNull()

internal fun HomeChannelBridgeWireDto?.normalizedBridgeProviderOrNull(): String? =
    this?.provider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

private fun HomeChannelWireDto.toChannelRow(): HomeChannelRow {
    val isJoint =
        type.equals("joint", ignoreCase = true) ||
            jointChannelId?.isNotBlank() == true ||
            jointRole?.isNotBlank() == true
    return HomeChannelRow(
        threadId = ThreadId(id),
        channelId = ChannelId(id),
        channelType = type.takeIf { it.isNotBlank() },
        kind = if (isJoint) ConversationKind.Joint else ConversationKind.Channel,
        name = name,
        subtitle = description?.takeIf { it.isNotBlank() },
        joined = joined,
        archived = archivedAt != null,
        lastMessageAt = lastMessageAt,
        readStateV1Hydrate = readStateV1HydrateCarrier(),
        jointChannelId = jointChannelId?.takeIf { it.isNotBlank() },
        jointRole = jointRole?.takeIf { it.isNotBlank() },
        jointPeerServerId = jointPeerServerId?.takeIf { it.isNotBlank() }?.let(::ServerId),
        jointPeerServerName = jointPeerServerName?.takeIf { it.isNotBlank() },
        jointPeerServerSlug = jointPeerServerSlug?.takeIf { it.isNotBlank() },
        jointPeerStatus = jointPeerStatus?.takeIf { it.isNotBlank() },
        jointBillingLocked = jointBillingLocked,
        jointOverLimitGraceEndsAt = jointOverLimitGraceEndsAt?.takeIf { it.isNotBlank() },
        bridgeProvider = bridge.normalizedBridgeProviderOrNull(),
        activityNotificationPreference =
            ChannelActivityNotificationPreference(
                activityMuted = activityMuted,
                muteFromSeq = muteFromSeq,
                activityMuteSupported = activityMuteSupported
            ),
        permissions = ConversationPermissions(
            canSend = joined,
            canAttach = joined,
            canCreateTask = joined
        ),
        channelCapabilities = channelCapabilities?.toModel()
    )
}

private fun HomeDirectMessageWireDto.toDirectMessageRow(warningLogger: (String) -> Unit): HomeDirectMessageRow {
    val displayName =
        peerDisplayName?.takeIf { it.isNotBlank() }
            ?: peerName.takeIf { it.isNotBlank() }
            ?: id
    if (peerAvatarUrl.isNullOrBlank()) {
        warningLogger("dm_avatar_missing source=home_direct_messages peerId=$peerId peerName=$displayName")
    }
    return HomeDirectMessageRow(
        threadId = ThreadId(id),
        channelId = ChannelId(id),
        kind = ConversationKind.DirectMessage,
        name = displayName,
        subtitle =
            peerDescription?.takeIf { it.isNotBlank() }
                ?: description?.takeIf { it.isNotBlank() },
        directMessagePeerType = peerType.takeIf { it.isNotBlank() },
        directMessagePeerAvatarWireValue = peerAvatarUrl?.takeIf { it.isNotBlank() },
        presenceMemberId = MemberId(peerId),
        lastMessageAt = lastMessageAt,
        lastMessagePreview = lastMessagePreview,
        lastMessageSenderName = lastMessageSenderName,
        readStateV1Hydrate = readStateV1HydrateCarrier(),
        activityNotificationPreference =
            ChannelActivityNotificationPreference(
                activityMuted = activityMuted,
                muteFromSeq = muteFromSeq,
                activityMuteSupported = activityMuteSupported
            )
    )
}

private fun HomeChannelWireDto.readStateV1HydrateCarrier(): ReadStateV1HydrateCarrier? =
    if (readStateVersion == null && maxReadSeq == null) null else ReadStateV1HydrateCarrier(readStateVersion, maxReadSeq)

private fun HomeDirectMessageWireDto.readStateV1HydrateCarrier(): ReadStateV1HydrateCarrier? =
    if (readStateVersion == null && maxReadSeq == null) null else ReadStateV1HydrateCarrier(readStateVersion, maxReadSeq)

internal fun HomeNetworkPayload.directMessagePeerProfiles(
    serverId: ServerId,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): List<MemberProfile> =
    directMessages.mapNotNull { it.toPeerMemberProfile(serverId, diagnostics) }

private fun HomeDirectMessageWireDto.toPeerMemberProfile(
    serverId: ServerId,
    diagnostics: (String) -> Unit,
): MemberProfile? {
    val cleanPeerId = peerId.trim().takeIf { it.isNotBlank() } ?: return null
    val memberKind =
        peerType.strictMemberKindOrNull(
            source = "home_dm_peer",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.InternalActor,
        ) ?: return null
    val displayName =
        peerDisplayName?.takeIf { it.isNotBlank() }
            ?: peerName.takeIf { it.isNotBlank() }
            ?: cleanPeerId
    val directAvatarUrl = peerAvatarUrl?.takeIf { it.isNotBlank() }
    return MemberProfile(
        id = MemberId(cleanPeerId),
        displayName = displayName,
        handle = peerName.takeIf { it.isNotBlank() } ?: displayName,
        kind = memberKind,
        description = peerDescription?.takeIf { it.isNotBlank() },
        avatarUrl = directAvatarUrl,
        avatar = directAvatarUrl?.let { AvatarReference.fromWireValue(it) },
        avatarLabel = displayName.take(1).uppercase(),
        serverId = serverId
    )
}

private fun List<HomeChannelRow>.applyChannelUnreadCounts(
    counts: Map<ChannelId, Int>
): List<HomeChannelRow> =
    map { conversation ->
        val count = counts[conversation.channelId]
        if (count == null) conversation else conversation.copy(unreadCount = count, unreadCountKnown = true)
    }

private fun List<HomeDirectMessageRow>.applyDirectMessageUnreadCounts(
    counts: Map<ChannelId, Int>
): List<HomeDirectMessageRow> =
    map { conversation ->
        val count = counts[conversation.channelId]
        if (count == null) conversation else conversation.copy(unreadCount = count, unreadCountKnown = true)
    }

private fun List<HomeChannelRow>.dedupeHomeChannels(): List<HomeChannelRow> {
    val deduped = LinkedHashMap<String, HomeChannelRow>()
    for (conversation in this) {
        val key = conversation.channelId?.value ?: conversation.threadId.value
        deduped.remove(key)
        deduped[key] = conversation
    }
    return deduped.values.toList()
}

private fun List<HomeDirectMessageRow>.dedupeHomeDirectMessages(): List<HomeDirectMessageRow> {
    val deduped = LinkedHashMap<String, HomeDirectMessageRow>()
    for (conversation in this) {
        val key = conversation.channelId?.value ?: conversation.threadId.value
        deduped.remove(key)
        deduped[key] = conversation
    }
    return deduped.values.toList()
}
