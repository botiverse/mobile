package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val draftChannelPlaceJson = Json { ignoreUnknownKeys = true }

/**
 * What the Drafts list needs from `GET /api/channels/:id` to place a draft this device cannot place
 * from Home or the task store (botiverse/slock #12159 `draftPlaces.ts` `loadConversationIdentity`;
 * slock `routes/channels.ts` spreads the `channels` row: `type`, and for a thread or a task
 * conversation `parentChannelId`, `parentMessageId`, `taskId`).
 */
@Serializable
internal data class RaftDraftChannelPlaceWireDto(
    val id: String,
    val type: String,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val taskId: String? = null,
)

/** A conversation as `GET /channels/:id` names it; [type] is lower-case (`channel`, `dm`, `thread`, `task`, ...). */
internal data class RaftDraftChannelPlace(
    val id: String,
    val type: String,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val taskId: String? = null,
)

/** Null for a body that is not a channel row (no id or type). */
internal fun parseRaftDraftChannelPlace(bodyText: String?): RaftDraftChannelPlace? {
    val text = bodyText?.takeIf(String::isNotBlank) ?: return null
    val wire =
        runCatching { draftChannelPlaceJson.decodeFromString(RaftDraftChannelPlaceWireDto.serializer(), text) }
            .getOrNull() ?: return null
    val id = wire.id.trim().takeIf(String::isNotBlank) ?: return null
    val type = wire.type.trim().lowercase().takeIf(String::isNotBlank) ?: return null
    return RaftDraftChannelPlace(
        id = id,
        type = type,
        parentChannelId = wire.parentChannelId?.trim()?.takeIf(String::isNotBlank),
        parentMessageId = wire.parentMessageId?.trim()?.takeIf(String::isNotBlank),
        taskId = wire.taskId?.trim()?.takeIf(String::isNotBlank),
    )
}
