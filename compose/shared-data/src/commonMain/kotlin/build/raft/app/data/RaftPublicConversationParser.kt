package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The anonymous public-server read routes, read for a message-link preview of a public server the
 * account has not joined (botiverse/slock@175cf23e9 packages/server/src/routes/publicServer.ts,
 * mounted at `/api/public`; the server card as of botiverse/slock@0ea95d1cd):
 *
 * - `GET /api/public/servers/:slug` → `{ server: { id, name, slug, avatarUrl, translationEnabled },
 *   channels: [{ id, name, description }], translationAvailable, visitorIdMode? }`, where `channels`
 *   is every channel a stranger may read (`anonymousReadableChannel`: an ordinary, guest-visible,
 *   not archived or deleted channel), and 404 for a server that is missing, deleted or not public
 *   (never 403, so a private server cannot be told from one that does not exist);
 * - `GET /api/public/servers/:slug/channels/:channelId/messages?limit=&beforeMessageId=` and
 *   `GET /api/public/servers/:slug/threads/:threadChannelId/messages` → `{ messages: [{ id,
 *   senderType, sender: { displayName, handle, avatarUrl, description }, messageType, content,
 *   createdAt, attachments, mentions, reactions, threadId, replyCount }] }`, oldest first.
 *
 * Every route answers 404 `{ error: "Not found" }` for a server/channel a stranger may not read,
 * so "private", "missing" and "deleted" are one answer.
 */
@Serializable
private data class RaftPublicMessagesWire(
    val messages: List<RaftPublicMessageWire> = emptyList(),
)

@Serializable
private data class RaftPublicMessageWire(
    val id: String = "",
    val senderType: String? = null,
    val sender: RaftPublicMessageSenderWire? = null,
    val messageType: String? = null,
    val content: String? = null,
    val createdAt: String? = null,
    val threadId: String? = null,
    val replyCount: Int = 0,
)

@Serializable
private data class RaftPublicMessageSenderWire(
    val displayName: String? = null,
    val handle: String? = null,
)

@Serializable
private data class RaftPublicServerWire(
    val server: RaftPublicServerCardWire? = null,
    val channels: List<RaftPublicChannelWire> = emptyList(),
)

@Serializable
private data class RaftPublicServerCardWire(
    val name: String? = null,
)

@Serializable
private data class RaftPublicChannelWire(
    val id: String = "",
    val name: String? = null,
)

@RaftCoreInternalApi data class RaftPublicMessage(
    val id: String,
    val senderName: String,
    val content: String,
    val createdAt: String?,
    val threadId: String?,
    val replyCount: Int,
)

@RaftCoreInternalApi data class RaftPublicServerCard(
    val serverName: String?,
    /** Every listed channel, named or not: the conversations a stranger may read on this server. */
    val channelIds: Set<String>,
    val channelNames: Map<String, String>,
)

/** `GET /api/public/servers/:slug`, the slug as one path segment (the web's `encodeURIComponent(slug)`). */
@RaftCoreInternalApi fun raftPublicServerPath(serverSlug: String): String = "public/servers/${serverSlug.appLinkPathSegment()}"

private val publicConversationJson = Json { ignoreUnknownKeys = true }

/** Oldest-first public messages, or null when the body is not that shape. */
@RaftCoreInternalApi fun parseRaftPublicMessages(bodyText: String?): List<RaftPublicMessage>? {
    val text = bodyText?.takeIf(String::isNotBlank) ?: return null
    val wire =
        runCatching { publicConversationJson.decodeFromString(RaftPublicMessagesWire.serializer(), text) }
            .getOrNull() ?: return null
    return wire.messages.mapNotNull { message ->
        val id = message.id.takeIf(String::isNotBlank) ?: return@mapNotNull null
        RaftPublicMessage(
            id = id,
            senderName = message.sender?.displayName?.takeIf(String::isNotBlank) ?: "",
            content = message.content.orEmpty(),
            createdAt = message.createdAt,
            threadId = message.threadId?.takeIf(String::isNotBlank),
            replyCount = message.replyCount,
        )
    }
}

@RaftCoreInternalApi fun parseRaftPublicServerCard(bodyText: String?): RaftPublicServerCard? {
    val text = bodyText?.takeIf(String::isNotBlank) ?: return null
    val wire =
        runCatching { publicConversationJson.decodeFromString(RaftPublicServerWire.serializer(), text) }
            .getOrNull() ?: return null
    return RaftPublicServerCard(
        serverName = wire.server?.name?.takeIf(String::isNotBlank),
        channelIds = wire.channels.mapNotNull { it.id.takeIf(String::isNotBlank) }.toSet(),
        channelNames =
            wire.channels
                .filter { it.id.isNotBlank() && !it.name.isNullOrBlank() }
                .associate { it.id to it.name!! },
    )
}
