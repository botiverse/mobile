package build.raft.app.data

import build.raft.app.reportUnknownTypeDropped
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Channel announcements (#proj-chat:#29, flag [CHANNEL_ANNOUNCEMENT_FEATURE_FLAG_KEY]).
 *
 * A channel's announcement is one Artifact homed in that channel, set by the channel's owner (one
 * of its channel admins) or a server owner or admin. Its versions are the edit history, whether a
 * person has seen it is the Artifact's own read state, and it can't be hidden ("a pin is a pin").
 *
 * Contract: botiverse/slock 6a848d0a0bdbd2a5ad64fb3b8cf314245f11093c
 *  - `packages/shared/src/channelAnnouncement.ts`: `ChannelAnnouncement`,
 *    `ChannelAnnouncementForPerson` (`updatedBy`, `note`, `seen`, `excerpt`),
 *    `ChannelAnnouncementNotice` and `readChannelAnnouncementNotice`,
 *    `channelTypeSupportsAnnouncement`.
 *  - `packages/server/src/routes/channels.ts`:
 *    `GET /api/channels/:id/announcement` answers `{ announcement: ChannelAnnouncementForPerson | null }`
 *    (null also while the flag is off; 404 `channel_not_found` for a channel the person can't see,
 *    through the unified visibility entry, #14059);
 *    `PATCH /api/channels/:id` with `{ announcement: "<artifact id>" | null }` sets, replaces or
 *    removes it (editChannelMetadata) and answers the channel, whose `announcement` is the person's
 *    announcement while the flag is on (absent while off).
 *  - `packages/server/src/services/channelAnnouncements.ts` `channelAnnouncementChange`: the
 *    refusals, `{ code, error, suggestedNextAction? }` (400 `channel_announcement_invalid`,
 *    `channel_announcement_unsupported_channel`, `channel_announcement_not_in_channel`; 403
 *    `channel_announcement_disabled`, `channel_announcement_forbidden`; 404
 *    `channel_announcement_artifact_not_found`).
 *  - `packages/server/src/services/channelAnnouncementMessages.ts`: a set, replacement or removal
 *    posts one system message whose `actionMetadata` is the notice ([readChannelAnnouncementNotice]).
 *  - Web: `packages/web/src/store/channelAnnouncementStore.ts` (#14039, #14069).
 */

/** Channel types that may have an announcement (`channelTypeSupportsAnnouncement`): never a DM, thread or task. */
fun channelTypeSupportsAnnouncement(type: String?): Boolean = type == "channel" || type == "private" || type == "joint"

/** Who made a version, or a change (`{ type: "user" | "agent", id, name }`). */
data class RaftChannelAnnouncementActor(
    val type: RaftChannelAnnouncementActorType,
    val id: String,
    /** A display name; null when the server couldn't resolve one. */
    val name: String?,
)

enum class RaftChannelAnnouncementActorType(val wireValue: String) {
    User("user"),
    Agent("agent"),
    ;

    companion object {
        fun fromWire(value: String?): RaftChannelAnnouncementActorType? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * A channel's announcement as this person sees it (`ChannelAnnouncementForPerson`): the Artifact,
 * its current version, who made that version and its note, whether the person has seen it, and the
 * first line of its text ([excerpt]; null for an HTML page or a version from before excerpts).
 */
data class RaftChannelAnnouncement(
    val artifactId: String,
    val title: String,
    val version: Int,
    /** ISO 8601. */
    val updatedAt: String,
    val updatedBy: RaftChannelAnnouncementActor,
    val note: String?,
    val seen: Boolean,
    val excerpt: String?,
)

@Serializable
internal data class ChannelAnnouncementActorWireDto(
    val type: String,
    val id: String,
    val name: String? = null,
)

@Serializable
internal data class ChannelAnnouncementForPersonWireDto(
    val artifactId: String,
    val title: String,
    val version: Int,
    val updatedAt: String,
    val updatedBy: ChannelAnnouncementActorWireDto,
    val note: String? = null,
    val seen: Boolean,
    /** Absent from an older server. */
    val excerpt: String? = null,
)

/** `GET /api/channels/:id/announcement`: `{ announcement }`, null for none. */
@Serializable
internal data class ChannelAnnouncementReadWireDto(
    val announcement: ChannelAnnouncementForPersonWireDto? = null,
)

/**
 * The `PATCH /api/channels/:id` answer, read for its `announcement` only (the rest of the channel
 * row is the channel repository's): the person's announcement after the change, null for none.
 */
@Serializable
internal data class ChannelAnnouncementChannelWireDto(
    val id: String,
    val announcement: ChannelAnnouncementForPersonWireDto? = null,
)

/** `PATCH /api/channels/:id` body: `{ announcement: "<artifact id>" }` to set or replace, `{ announcement: null }` to remove. */
@Serializable
internal data class ChannelAnnouncementUpdateBody(
    val announcement: String?,
)

/** A refusal's body (`refuse()` in `packages/server/src/lib/refusal.ts`). */
@Serializable
internal data class ChannelAnnouncementRefusalWireDto(
    val error: String? = null,
    val code: String? = null,
    val suggestedNextAction: String? = null,
)

private val channelAnnouncementJson = Json {
    ignoreUnknownKeys = true
    // `{ "announcement": null }` is the removal: the null must be sent.
    explicitNulls = true
    encodeDefaults = true
}

/** The announcement a read answered; [announcement] null: the channel has none. */
data class ChannelAnnouncementRead(val announcement: RaftChannelAnnouncement?)

private fun ChannelAnnouncementForPersonWireDto.toDomainOrNull(): RaftChannelAnnouncement? {
    val actorType =
        RaftChannelAnnouncementActorType.fromWire(updatedBy.type)
            ?: run { reportUnknownTypeDropped("channel_announcement", "updated_by_type", updatedBy.type); return null }
    if (artifactId.isBlank() || version < 1) return null
    return RaftChannelAnnouncement(
        artifactId = artifactId,
        title = title,
        version = version,
        updatedAt = updatedAt,
        updatedBy = RaftChannelAnnouncementActor(actorType, updatedBy.id, updatedBy.name?.takeIf { it.isNotBlank() }),
        note = note?.takeIf { it.isNotBlank() },
        seen = seen,
        excerpt = excerpt?.takeIf { it.isNotBlank() },
    )
}

/**
 * `GET /api/channels/:id/announcement`; null when the answer can't be read (the caller keeps what
 * it shows, as the web's store does on a failure).
 */
fun parseSharedChannelAnnouncementRead(bodyText: String?): ChannelAnnouncementRead? {
    val wire =
        bodyText?.let {
            runCatching { channelAnnouncementJson.decodeFromString(ChannelAnnouncementReadWireDto.serializer(), it) }.getOrNull()
        } ?: return null
    val announcement = wire.announcement ?: return ChannelAnnouncementRead(null)
    return announcement.toDomainOrNull()?.let(::ChannelAnnouncementRead)
}

/**
 * The announcement in a `PATCH /api/channels/:id` answer for [channelId]; null when the answer can't
 * be read or names another channel. An absent `announcement` reads as none, as the web's
 * `setChannelAnnouncement` puts `updated.announcement ?? null`.
 */
fun parseSharedChannelAnnouncementUpdate(bodyText: String?, channelId: ChannelId): ChannelAnnouncementRead? {
    val wire =
        bodyText?.let {
            runCatching { channelAnnouncementJson.decodeFromString(ChannelAnnouncementChannelWireDto.serializer(), it) }.getOrNull()
        } ?: return null
    if (wire.id != channelId.value) return null
    val announcement = wire.announcement ?: return ChannelAnnouncementRead(null)
    return announcement.toDomainOrNull()?.let(::ChannelAnnouncementRead)
}

/** The PATCH body that sets or replaces ([artifactId]) or removes (null) the announcement. */
fun encodeChannelAnnouncementUpdateBody(artifactId: String?): String =
    channelAnnouncementJson.encodeToString(ChannelAnnouncementUpdateBody.serializer(), ChannelAnnouncementUpdateBody(artifactId))

/** The routes without the `/api/` prefix the network client adds. */
internal fun channelAnnouncementPath(channelId: ChannelId): String = "channels/${channelId.value.appLinkPathSegment()}/announcement"

internal fun channelAnnouncementUpdatePath(channelId: ChannelId): String = "channels/${channelId.value.appLinkPathSegment()}"

/** The two requests, through the shared network client. */
internal interface RaftChannelAnnouncementApi {
    suspend fun read(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse

    suspend fun update(serverId: ServerId, channelId: ChannelId, artifactId: String?): HomeNetworkResponse
}

internal class NetworkRaftChannelAnnouncementApi(private val networkClient: RaftNetworkClient) : RaftChannelAnnouncementApi {
    override suspend fun read(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = channelAnnouncementPath(channelId),
            serverIdOverride = serverId,
        )

    override suspend fun update(serverId: ServerId, channelId: ChannelId, artifactId: String?): HomeNetworkResponse =
        networkClient.request(
            // A person's set, replace or remove: they wait for it.
            priority = DataRequestPriority.Foreground,
            path = channelAnnouncementUpdatePath(channelId),
            method = "PATCH",
            bodyText = encodeChannelAnnouncementUpdateBody(artifactId),
            serverIdOverride = serverId,
        )
}

/** A refused or failed request as the UI shows it: the server's sentence (when it sent one) and the trace id. */
internal fun HomeNetworkResponse.toChannelAnnouncementError(): ScreenError {
    val refusal =
        (bodyText ?: errorBodyText)?.takeIf { it.isNotBlank() }?.let {
            runCatching { channelAnnouncementJson.decodeFromString(ChannelAnnouncementRefusalWireDto.serializer(), it) }.getOrNull()
        }
    val serverCode = refusal?.code?.trim()?.takeIf { it.isNotBlank() }
    return ScreenError(
        code = serverCode ?: if (code in 500..599) "server_unavailable" else "channel_announcement_http_$code",
        retryable = code in 500..599 || code == 429,
        serverMessage = refusal?.error?.trim()?.takeIf { it.isNotBlank() },
        serverCode = serverCode,
        traceId = traceId,
    )
}

/** Reads and writes channel announcements; every failure is a typed [DataResult], never a thrown one. */
internal class ChannelAnnouncementRepository(private val api: RaftChannelAnnouncementApi) {
    suspend fun read(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority): DataResult<ChannelAnnouncementRead> {
        val response =
            runCatchingPreservingCancellation { api.read(serverId, channelId, priority) }.getOrElse { error ->
                return DataResult.Error(ScreenError("channel_announcement_network", retryable = true, serverMessage = error.message, traceId = null))
            }
        if (!response.isSuccessful) return DataResult.Error(response.toChannelAnnouncementError())
        val read =
            parseSharedChannelAnnouncementRead(response.bodyText)
                ?: return DataResult.Error(ScreenError("channel_announcement_unreadable", retryable = true, traceId = response.traceId))
        return DataResult.Content(read)
    }

    suspend fun update(serverId: ServerId, channelId: ChannelId, artifactId: String?): DataResult<ChannelAnnouncementRead> {
        val response =
            runCatchingPreservingCancellation { api.update(serverId, channelId, artifactId) }.getOrElse { error ->
                return DataResult.Error(ScreenError("channel_announcement_network", retryable = true, serverMessage = error.message, traceId = null))
            }
        if (!response.isSuccessful) return DataResult.Error(response.toChannelAnnouncementError())
        val read =
            parseSharedChannelAnnouncementUpdate(response.bodyText, channelId)
                ?: return DataResult.Error(ScreenError("channel_announcement_unreadable", retryable = false, traceId = response.traceId))
        return DataResult.Content(read)
    }
}

// ---- The event line a change posts --------------------------------------------------------------

/** `CHANNEL_ANNOUNCEMENT_NOTICE_KIND`: the system message's `actionMetadata.kind`. */
const val CHANNEL_ANNOUNCEMENT_NOTICE_KIND: String = "channel-announcement"

/** `ChannelAnnouncementChangeKind`. A new version posts nothing. */
enum class ChannelAnnouncementChangeKind(val wireValue: String) {
    Set("set"),
    Replaced("replaced"),
    Removed("removed"),
    ;

    companion object {
        fun fromWire(value: String?): ChannelAnnouncementChangeKind? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * `ChannelAnnouncementNotice`: the change a system message records, from its `actionMetadata`, so
 * the line can be said in the reader's language (the message's text is the English record).
 */
data class ChannelAnnouncementNotice(
    val change: ChannelAnnouncementChangeKind,
    val actor: RaftChannelAnnouncementActor,
    /** The Artifact now the announcement (set, replaced), or the one removed. */
    val artifactId: String,
    /** Its title when the change was made; null when it couldn't be read. */
    val title: String?,
)

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** `readChannelAnnouncementNotice`, rule for rule: null for anything that isn't the notice. */
fun readChannelAnnouncementNotice(actionMetadata: JsonElement?): ChannelAnnouncementNotice? {
    val metadata = actionMetadata as? JsonObject ?: return null
    if (metadata["kind"].stringOrNull() != CHANNEL_ANNOUNCEMENT_NOTICE_KIND) return null
    val change =
        ChannelAnnouncementChangeKind.fromWire(metadata["change"].stringOrNull())
            ?: run { reportUnknownTypeDropped("channel_announcement_notice", "change", metadata["change"].stringOrNull()); return null }
    val artifactId = metadata["artifactId"].stringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val actor = metadata["actor"] as? JsonObject ?: return null
    val actorType =
        RaftChannelAnnouncementActorType.fromWire(actor["type"].stringOrNull())
            ?: run { reportUnknownTypeDropped("channel_announcement_notice", "actor_type", actor["type"].stringOrNull()); return null }
    val actorId = actor["id"].stringOrNull() ?: return null
    return ChannelAnnouncementNotice(
        change = change,
        actor = RaftChannelAnnouncementActor(actorType, actorId, actor["name"].stringOrNull()?.takeIf { it.isNotEmpty() }),
        artifactId = artifactId,
        title = metadata["title"].stringOrNull()?.takeIf { it.isNotEmpty() },
    )
}

/*
 * The notice travels on a projected message as metadata strings, like an action card's or a card
 * message's (MessageCard.kt), and is read back where the line is drawn.
 */
private const val NoticeMetadataPrefix = "channelAnnouncement."
private const val NoticeMetadataChange = "${NoticeMetadataPrefix}change"
private const val NoticeMetadataActorType = "${NoticeMetadataPrefix}actorType"
private const val NoticeMetadataActorId = "${NoticeMetadataPrefix}actorId"
private const val NoticeMetadataActorName = "${NoticeMetadataPrefix}actorName"
private const val NoticeMetadataArtifactId = "${NoticeMetadataPrefix}artifactId"
private const val NoticeMetadataTitle = "${NoticeMetadataPrefix}title"

/** Whether [key] is one of the notice's metadata strings (left out of a system line's details). */
internal fun isChannelAnnouncementNoticeMetadataKey(key: String): Boolean = key.startsWith(NoticeMetadataPrefix)

/** The metadata strings the notice projects to. */
fun ChannelAnnouncementNotice.toMessageMetadata(): Map<String, String> =
    buildMap {
        put(NoticeMetadataChange, change.wireValue)
        put(NoticeMetadataActorType, actor.type.wireValue)
        put(NoticeMetadataActorId, actor.id)
        actor.name?.let { put(NoticeMetadataActorName, it) }
        put(NoticeMetadataArtifactId, artifactId)
        title?.let { put(NoticeMetadataTitle, it) }
    }

/** The notice a projected message's metadata carries; null for any other message. */
fun channelAnnouncementNoticeOf(metadata: Map<String, String>): ChannelAnnouncementNotice? {
    val change = ChannelAnnouncementChangeKind.fromWire(metadata[NoticeMetadataChange]) ?: return null
    val actorType = RaftChannelAnnouncementActorType.fromWire(metadata[NoticeMetadataActorType]) ?: return null
    val actorId = metadata[NoticeMetadataActorId] ?: return null
    val artifactId = metadata[NoticeMetadataArtifactId]?.takeIf { it.isNotEmpty() } ?: return null
    return ChannelAnnouncementNotice(
        change = change,
        actor = RaftChannelAnnouncementActor(actorType, actorId, metadata[NoticeMetadataActorName]?.takeIf { it.isNotEmpty() }),
        artifactId = artifactId,
        title = metadata[NoticeMetadataTitle]?.takeIf { it.isNotEmpty() },
    )
}
