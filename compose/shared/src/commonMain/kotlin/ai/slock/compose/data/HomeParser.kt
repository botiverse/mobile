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

@Serializable
data class HomeChannelWireDto(
    val id: String,
    val name: String,
    val type: String,
    val description: String? = null,
    val archivedAt: String? = null,
    val joined: Boolean = true,
    val lastMessageAt: String? = null
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
    val peerGravatarHash: String? = null
)

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
        .map { it.toConversation() }
        .dedupeHomeConversations()

    val directMessages = payload.directMessages
        .map { it.toConversation(warningLogger) }
        .dedupeHomeConversations()

    if (channels.isEmpty() && directMessages.isEmpty()) {
        return DataResult.Empty(EmptyReason.NoChannels)
    }

    val unreadCounts = payload.unreadCounts
    val channelsWithUnread = channels.applyUnreadCounts(unreadCounts)
    val dmsWithUnread = directMessages.applyUnreadCounts(unreadCounts)
    val channelUnreadTotal = channelsWithUnread.sumOf { it.unreadCount } + dmsWithUnread.sumOf { it.unreadCount }
    val serverUnreadTotal =
        payload.serverUnreadSummary
            .firstOrNull { it.serverId == currentServer.id.value }
            ?.unreadCount
    val unreadTotal = serverUnreadTotal ?: channelUnreadTotal
    val server = currentServer.copy(unreadCount = unreadTotal)

    return DataResult.Content(
        HomeSnapshot(
            server = server,
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
        channels = homeJson.decodeFromString(ListSerializer(HomeChannelWireDto.serializer()), bodyText)
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
        directMessages = homeJson.decodeFromString(ListSerializer(HomeDirectMessageWireDto.serializer()), bodyText)
            .map { it.toConversation(warningLogger) }
            .dedupeHomeConversations(),
    )
}.getOrNull()

fun parseSharedCreateChannelResult(bodyText: String): CreateChannelResult? = runCatching {
    CreateChannelResult(
        channel = homeJson.decodeFromString(HomeChannelWireDto.serializer(), bodyText)
            .toConversation()
    )
}.getOrNull()

fun parseSharedOpenDirectMessageResult(bodyText: String): OpenDirectMessageResult? = runCatching {
    OpenDirectMessageResult(
        conversation = homeJson.decodeFromString(HomeDirectMessageWireDto.serializer(), bodyText)
            .toConversation { _ -> },
        directMessages = emptyList(),
    )
}.getOrNull()

fun parseSharedChannelArchiveResult(
    bodyText: String,
    fallbackChannelId: ChannelId,
): ChannelArchiveResult? = runCatching {
    val channel = homeJson.decodeFromString(HomeChannelWireDto.serializer(), bodyText)
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

private fun HomeChannelWireDto.toConversation(): ConversationSummary =
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

private fun HomeDirectMessageWireDto.toConversation(warningLogger: (String) -> Unit): ConversationSummary {
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
