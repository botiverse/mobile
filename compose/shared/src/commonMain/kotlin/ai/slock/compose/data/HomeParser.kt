package ai.slock.compose.data

import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.toGravatarAvatarUrl
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Canonical Slock home parser.
 *
 * Mirrors the React server contracts directly. The boundary validator in
 * [SlockHomeApiClient.homePayload] guarantees the channels / DM bodies are JSON
 * arrays and the unread counts body is a JSON object, so we decode strict typed
 * DTOs here with no envelope unwrapping and no field aliasing.
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

@Serializable
private data class ChannelWireDto(
    val id: String,
    val name: String,
    val type: String,
    val description: String? = null,
    val archivedAt: String? = null,
    val joined: Boolean = true,
    val lastMessageAt: String? = null
)

@Serializable
private data class DMChannelWireDto(
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
    val peerGravatarHash: String? = null
)

fun parseSlockHome(
    payload: HomeNetworkPayload,
    session: StoredAuthSession,
    warningLogger: (String) -> Unit = {}
): DataResult<HomeSnapshot> {
    payload.failedResponse()?.let { return it.toHomeFailure() }

    val currentServer = session.servers.firstOrNull { it.id == payload.serverId.value }?.toServerSummary()
        ?: session.currentServerId?.let { id ->
            session.servers.firstOrNull { it.id == id }?.toServerSummary()
        }
        ?: return DataResult.Empty(EmptyReason.NoChannels)
    val currentMember = session.user.toMemberProfile()

    val channelsRaw = decodeList(
        body = payload.channels.bodyText,
        serializer = ChannelWireDto.serializer(),
        source = "home_channels",
        warningLogger = warningLogger
    )
    val dmsRaw = decodeList(
        body = payload.directMessages.bodyText,
        serializer = DMChannelWireDto.serializer(),
        source = "home_direct_messages",
        warningLogger = warningLogger
    )

    val channels = channelsRaw
        .filter { it.archivedAt == null }
        .map { it.toConversation() }
        .dedupeHomeConversations()

    val directMessages = dmsRaw
        .map { it.toConversation(warningLogger) }
        .dedupeHomeConversations()

    if (channels.isEmpty() && directMessages.isEmpty()) {
        return DataResult.Empty(EmptyReason.NoChannels)
    }

    val unreadCounts = decodeUnread(payload.unreadCounts.bodyText)
    val channelsWithUnread = channels.applyUnreadCounts(unreadCounts)
    val dmsWithUnread = directMessages.applyUnreadCounts(unreadCounts)
    val unreadTotal = channelsWithUnread.sumOf { it.unreadCount } + dmsWithUnread.sumOf { it.unreadCount }

    return DataResult.Content(
        HomeSnapshot(
            server = currentServer,
            currentMember = currentMember,
            channels = channelsWithUnread,
            directMessages = dmsWithUnread,
            unreadTotal = unreadTotal
        )
    )
}

fun parseSharedChannelSnapshot(
    bodyText: String,
    server: ServerSummary,
): ChannelSnapshot? = runCatching {
    ChannelSnapshot(
        server = server,
        channels = homeJson.decodeFromString(ListSerializer(ChannelWireDto.serializer()), bodyText)
            .filter { it.archivedAt == null }
            .map { it.toConversation() }
            .dedupeHomeConversations(),
    )
}.getOrNull()

fun parseSharedDirectMessageSnapshot(
    bodyText: String,
    server: ServerSummary,
    warningLogger: (String) -> Unit = {},
): DirectMessageSnapshot? = runCatching {
    DirectMessageSnapshot(
        server = server,
        directMessages = homeJson.decodeFromString(ListSerializer(DMChannelWireDto.serializer()), bodyText)
            .map { it.toConversation(warningLogger) }
            .dedupeHomeConversations(),
    )
}.getOrNull()

fun parseSharedCreateChannelResult(bodyText: String): CreateChannelResult? = runCatching {
    CreateChannelResult(
        channel = homeJson.decodeFromString(ChannelWireDto.serializer(), bodyText)
            .toConversation()
    )
}.getOrNull()

fun parseSharedOpenDirectMessageResult(bodyText: String): OpenDirectMessageResult? = runCatching {
    OpenDirectMessageResult(
        conversation = homeJson.decodeFromString(DMChannelWireDto.serializer(), bodyText)
            .toConversation { _ -> },
        directMessages = emptyList(),
    )
}.getOrNull()

fun parseSharedChannelArchiveResult(
    bodyText: String,
    fallbackChannelId: ChannelId,
): ChannelArchiveResult? = runCatching {
    val channel = homeJson.decodeFromString(ChannelWireDto.serializer(), bodyText)
        .toConversation()
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

private fun HomeNetworkPayload.failedResponse(): HomeNetworkResponse? =
    listOf(channels, directMessages, unreadCounts, serverUnreadSummary).firstOrNull { !it.isSuccessful }

private fun HomeNetworkResponse.toHomeFailure(): DataResult<HomeSnapshot> =
    if (code == 401 || code == 403) {
        DataResult.NoPermission(PermissionBlocker(code = "home_http_$code"))
    } else {
        DataResult.Error(ScreenError(code = "home_http_$code", retryable = true))
    }

private fun <T> decodeList(
    body: String?,
    serializer: KSerializer<T>,
    source: String,
    warningLogger: (String) -> Unit
): List<T> {
    val trimmed = body?.trim().orEmpty()
    if (trimmed.isEmpty()) return emptyList()
    return try {
        homeJson.decodeFromString(ListSerializer(serializer), trimmed)
    } catch (_: SerializationException) {
        warningLogger("home_decode_failed source=$source")
        emptyList()
    }
}

private fun decodeUnread(body: String?): Map<ChannelId, Int> {
    val trimmed = body?.trim().orEmpty()
    if (trimmed.isEmpty()) return emptyMap()
    return try {
        homeJson.decodeFromString(MapSerializer(String.serializer(), Int.serializer()), trimmed)
            .mapKeys { (key, _) -> ChannelId(key) }
    } catch (_: SerializationException) {
        emptyMap()
    }
}

private fun ChannelWireDto.toConversation(): ConversationSummary =
    ConversationSummary(
        threadId = ThreadId(id),
        channelId = ChannelId(id),
        kind = ConversationKind.Channel,
        name = name,
        subtitle = description?.takeIf { it.isNotBlank() },
        joined = joined,
        archived = archivedAt != null,
        lastMessageAt = lastMessageAt,
        permissions = ConversationPermissions(
            canSend = joined,
            canAttach = joined,
            canCreateTask = joined
        )
    )

private fun DMChannelWireDto.toConversation(warningLogger: (String) -> Unit): ConversationSummary {
    val displayName =
        peerDisplayName?.takeIf { it.isNotBlank() }
            ?: peerName.takeIf { it.isNotBlank() }
            ?: id
    val directUrl = peerAvatarUrl?.takeIf { it.isNotBlank() }
    val gravatarUrl = peerGravatarHash?.takeIf { it.isNotBlank() }?.toGravatarAvatarUrl()
    val effectiveAvatarUrl = directUrl ?: gravatarUrl
    if (effectiveAvatarUrl == null) {
        warningLogger("dm_avatar_missing source=home_direct_messages peerId=$peerId peerName=$displayName")
    }
    return ConversationSummary(
        threadId = ThreadId(id),
        channelId = ChannelId(id),
        kind = ConversationKind.DirectMessage,
        name = displayName,
        subtitle =
            peerDescription?.takeIf { it.isNotBlank() }
                ?: description?.takeIf { it.isNotBlank() },
        avatarUrl = effectiveAvatarUrl,
        avatar = effectiveAvatarUrl?.let { AvatarReference.fromWireValue(it) },
        presenceMemberId = MemberId(peerId),
        lastMessageAt = lastMessageAt
    )
}

private fun List<ConversationSummary>.applyUnreadCounts(
    counts: Map<ChannelId, Int>
): List<ConversationSummary> =
    map { conversation ->
        val channelId = conversation.channelId
        val count = if (channelId == null) null else counts[channelId]
        if (count == null) conversation else conversation.copy(unreadCount = count, unreadCountKnown = true)
    }

private fun List<ConversationSummary>.dedupeHomeConversations(): List<ConversationSummary> {
    val deduped = LinkedHashMap<String, ConversationSummary>()
    for (conversation in this) {
        val key = conversation.channelId?.value ?: conversation.threadId.value
        deduped.remove(key)
        deduped[key] = conversation
    }
    return deduped.values.toList()
}
