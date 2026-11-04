package build.raft.app.data

import build.raft.app.model.ChannelId

internal const val ARCHIVED_CHANNEL_ID_FIELD = "archivedChannelId"
internal const val ARCHIVED_CHANNEL_NAME_FIELD = "archivedChannelName"
internal const val ARCHIVED_CHANNEL_TYPE_FIELD = "archivedChannelType"
internal const val CAN_UNARCHIVE_ARCHIVED_CHANNEL_FIELD = "canUnarchiveArchivedChannel"

/**
 * Web `archivedNameCollision.ts`: the server's 409 `archived_name_collision` on create: the name
 * is held by an archived channel the caller can see. Someone who cannot see it gets the plain
 * "already taken" refusal instead, which is no collision here.
 */
data class ArchivedNameCollision(
    val archivedChannelId: ChannelId,
    val archivedChannelName: String,
    /** `channel`, `private` or `joint`. */
    val archivedChannelType: String,
    val canUnarchive: Boolean,
)

/** The kinds Create Channel offers: it never names an archived joint channel (web `["channel", "private"]`). */
val CREATE_CHANNEL_ARCHIVED_TYPES: Set<String> = setOf("channel", "private")

/**
 * The collision a create refusal carries, or null (web `parseArchivedNameCollision`): only an
 * `archived_name_collision` naming an archived channel of one of [types], with its id and name.
 */
fun ScreenError.archivedNameCollisionOrNull(types: Set<String> = CREATE_CHANNEL_ARCHIVED_TYPES): ArchivedNameCollision? {
    if (code != ARCHIVED_NAME_COLLISION) return null
    val id = serverFields[ARCHIVED_CHANNEL_ID_FIELD]?.takeIf(String::isNotBlank) ?: return null
    val name = serverFields[ARCHIVED_CHANNEL_NAME_FIELD]?.takeIf(String::isNotBlank) ?: return null
    val type = serverFields[ARCHIVED_CHANNEL_TYPE_FIELD]?.takeIf { it in types } ?: return null
    return ArchivedNameCollision(
        archivedChannelId = ChannelId(id),
        archivedChannelName = name,
        archivedChannelType = type,
        canUnarchive = serverFields[CAN_UNARCHIVE_ARCHIVED_CHANNEL_FIELD] == "true",
    )
}
