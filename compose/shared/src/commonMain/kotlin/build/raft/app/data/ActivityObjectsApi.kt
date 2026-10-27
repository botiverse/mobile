package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Activity rows for first-party objects (botiverse/slock feat/workspace-artifacts 542e922d6,
 * `packages/shared/src/activityObjectsContract.ts`), behind each app's server flag (Artifacts:
 * `workspace_artifacts_v0`). With every object app off the inbox and unread-summary answers are
 * exactly what they were: no object rows, none of the count fields.
 *
 * - `GET /api/channels/inbox` `items` may hold `ObjectInboxItem`s (`kind: "object"`), ordered and
 *   paged with the conversation rows by `lastActivityAt`; the answer adds `objectUnreadCount`,
 *   `objectMentionUnreadCount`, `objectCount` ([ActivityObjectCounts]). `totalCount`,
 *   `totalUnreadCount` and `activeUnreadCount` count no object rows.
 * - `GET /api/servers/unread-summary` adds `activityObjectUnreadCount` per server.
 * - `POST /api/objects/activity/{done,undone,read}` (`objectActivityWebPaths`):
 *   `{ kind, objectId, throughSeq? }` → `{ ok: true }`.
 * - Unfollow is the app's own route: Artifacts `DELETE /api/artifacts/:id/subscription`
 *   (`webArtifactsContract.ts` `artifactWebPaths.subscription`) → `{ following: false }`.
 * - Socket `activity:changed { serverId, source: "object" }` to the person's room: refetch.
 */
@Serializable
internal data class WireObjectInboxHome(
    val kind: String,
    val label: String? = null,
    val channelId: String? = null,
)

@Serializable
internal data class WireObjectInboxActor(
    val type: String,
    val id: String,
    val name: String? = null,
)

@Serializable
internal data class WireObjectInboxEvent(
    val eventKind: String,
    val reason: String,
    val actor: WireObjectInboxActor,
    val at: String,
)

@Serializable
internal data class WireObjectInboxPreview(
    val text: String,
    val truncated: Boolean = false,
    val author: WireObjectInboxActor? = null,
    val at: String? = null,
)

@Serializable
internal data class WireObjectActivityTarget(
    val version: Int,
    val comment: String? = null,
)

/** `ObjectInboxItem` (summary kept raw: its shape is the kind's, Artifacts `ArtifactCard`). */
@Serializable
internal data class WireObjectInboxItem(
    val kind: String,
    val objectKind: String,
    val objectId: String,
    val summary: JsonElement? = null,
    val home: WireObjectInboxHome,
    val latestEvent: WireObjectInboxEvent,
    val preview: WireObjectInboxPreview? = null,
    val target: WireObjectActivityTarget? = null,
    val unreadCount: Int,
    val hasMention: Boolean,
    val isFollowing: Boolean,
    val lastActivityAt: String,
    val doneFrontierSeq: String,
)

/** The inbox answer's object counts (`ObjectInboxFields`); null when the server sent none (app off). */
@Serializable
data class ActivityObjectCounts(
    val objectUnreadCount: Int = 0,
    val objectMentionUnreadCount: Int = 0,
    val objectCount: Int = 0,
) {
    /** The unread object rows the [filter] lists (web `objectUnreadCountForFilter`). */
    fun unreadFor(filter: InboxFilter): Int =
        when (filter) {
            InboxFilter.All -> objectUnreadCount
            InboxFilter.Mentions -> objectMentionUnreadCount
            // Every row Unread lists is unread.
            InboxFilter.Unread -> objectCount
            // A scope lists no object rows.
            InboxFilter.Pinned -> 0
        }
}

/** Why the newest event reached the person. */
enum class ActivityObjectReason { Mentioned, Following, Owner, Other }

/**
 * One object's Activity row, as [InboxItem.activityObject] carries it. Persisted with the Activity
 * snapshot, so every field has a default.
 */
@Serializable
data class ActivityObjectRow(
    val objectKind: String = "",
    val objectId: String = "",
    /** The summary's title (Artifacts `ArtifactCard.title`); null when there is no summary now. */
    val title: String? = null,
    /** Artifacts `ArtifactCard.kind`, for the row's icon. */
    val artifactKind: String? = null,
    val latestVersion: Int? = null,
    val homeKind: String = "",
    val homeLabel: String? = null,
    val homeChannelId: String? = null,
    val eventKind: String = "",
    val reason: String = "",
    val actorType: String = "",
    val actorId: String = "",
    val actorName: String? = null,
    val eventAt: String = "",
    val previewText: String? = null,
    val targetVersion: Int? = null,
    val targetComment: String? = null,
    val isFollowing: Boolean = true,
    val doneFrontierSeq: String = "",
) {
    val reasonKind: ActivityObjectReason
        get() = when (reason) {
            "mentioned" -> ActivityObjectReason.Mentioned
            "following" -> ActivityObjectReason.Following
            "owner" -> ActivityObjectReason.Owner
            else -> ActivityObjectReason.Other
        }

    /** The row's second line's place (web `objectRowHome`): `#channel`, `@peer`; null otherwise. */
    val homeText: String?
        get() {
            val label = homeLabel?.takeIf { it.isNotBlank() } ?: return null
            return when (homeKind) {
                "channel" -> "#$label"
                "dm" -> "@$label"
                else -> null
            }
        }

    /** The version an open lands on (web `artifactActivityOpenPath`): pinned only when not the latest. */
    val openVersion: Int?
        get() = targetVersion?.takeIf { latestVersion != null && it != latestVersion }
}

/** What the row says the newest event was (web `artifactActivityRowParts`). */
enum class ActivityObjectEventVerb { MentionedYou, Commented, EditedComment, PublishedVersion, Published, Updated }

/** web `artifactActivityRowParts`: the verb from (`eventKind`, `reason`), and the version a publish names. */
fun ActivityObjectRow.eventVerb(): Pair<ActivityObjectEventVerb, Int?> {
    val version = targetVersion ?: latestVersion
    return when {
        reasonKind == ActivityObjectReason.Mentioned && eventKind != "version" -> ActivityObjectEventVerb.MentionedYou to null
        eventKind == "comment" -> ActivityObjectEventVerb.Commented to null
        eventKind == "comment_edited" -> ActivityObjectEventVerb.EditedComment to null
        eventKind == "version" && version != null -> ActivityObjectEventVerb.PublishedVersion to version
        eventKind == "version" -> ActivityObjectEventVerb.Published to null
        else -> ActivityObjectEventVerb.Updated to null
    }
}

private val activityObjectsJson = Json {
    ignoreUnknownKeys = true
}

/** Activity's key for an object row (web `getInboxItemKey`: `object:<kind>:<id>`). */
fun activityObjectRowKey(objectKind: String, objectId: String): String = "object:$objectKind:$objectId"

/**
 * One `kind: "object"` inbox row as an [InboxItem] of kind [InboxItemKind.Object], or null when it
 * can't be read (dropped, as older Apps drop it). Only Artifacts rows are read; another object kind
 * is dropped until this client knows how to draw it. The row's [InboxItem.channelId] is a
 * placeholder that names no conversation (`object:<id>`), so no conversation patch ever lands on it.
 */
internal fun parseActivityObjectInboxItem(element: JsonElement): InboxItem? {
    val wire = runCatching { activityObjectsJson.decodeFromJsonElement(WireObjectInboxItem.serializer(), element) }.getOrNull() ?: return null
    if (wire.kind != "object" || wire.objectKind != "artifact" || wire.objectId.isBlank()) return null
    val card = wire.summary?.let { runCatching { activityObjectsJson.decodeFromJsonElement(ArtifactCardWireDto.serializer(), it) }.getOrNull() }
    val row =
        ActivityObjectRow(
            objectKind = wire.objectKind,
            objectId = wire.objectId,
            title = card?.title,
            artifactKind = card?.kind,
            latestVersion = card?.latestVersion,
            homeKind = wire.home.kind,
            homeLabel = wire.home.label,
            homeChannelId = wire.home.channelId,
            eventKind = wire.latestEvent.eventKind,
            reason = wire.latestEvent.reason,
            actorType = wire.latestEvent.actor.type,
            actorId = wire.latestEvent.actor.id,
            actorName = wire.latestEvent.actor.name,
            eventAt = wire.latestEvent.at,
            previewText = wire.preview?.text,
            targetVersion = wire.target?.version,
            targetComment = wire.target?.comment,
            isFollowing = wire.isFollowing,
            doneFrontierSeq = wire.doneFrontierSeq,
        )
    return InboxItem(
        key = activityObjectRowKey(wire.objectKind, wire.objectId),
        kind = InboxItemKind.Object,
        channelId = ChannelId("object:${wire.objectId}"),
        channelName = card?.title.orEmpty(),
        unreadCount = wire.unreadCount.coerceAtLeast(0),
        hasMention = wire.hasMention,
        lastMessageAt = wire.lastActivityAt,
        latestActivityAt = wire.lastActivityAt,
        lastMessagePreview = wire.preview?.text,
        isFollowing = wire.isFollowing,
        activityObject = row,
    )
}

/** `ObjectActivityInput` / `ObjectActivityDoneInput`. */
@Serializable
internal data class ObjectActivityInputWireDto(
    val kind: String,
    val objectId: String,
    val throughSeq: String? = null,
)

private val objectActivityRequestJson = Json { explicitNulls = false }

internal fun encodeObjectActivityInput(row: ActivityObjectRow, withThroughSeq: Boolean): String =
    objectActivityRequestJson.encodeToString(
        ObjectActivityInputWireDto.serializer(),
        ObjectActivityInputWireDto(
            kind = row.objectKind,
            objectId = row.objectId,
            throughSeq = row.doneFrontierSeq.takeIf { withThroughSeq && it.isNotBlank() },
        ),
    )

/** The core routes on an object row. */
enum class ActivityObjectAction(val path: String) {
    Done("objects/activity/done"),
    Undone("objects/activity/undone"),
    Read("objects/activity/read"),
}

/** Object-row writes for one Server. Repositories answer [DataResult]; nothing raw leaks upward. */
interface ActivityObjectsRepository {
    suspend fun act(serverId: String, action: ActivityObjectAction, row: ActivityObjectRow): DataResult<Unit>

    /** Artifacts `DELETE /api/artifacts/:id/subscription` (other kinds: no route yet). */
    suspend fun unfollow(serverId: String, row: ActivityObjectRow): DataResult<Unit>
}

internal class NetworkActivityObjectsRepository(private val networkClient: RaftNetworkClient) : ActivityObjectsRepository {
    override suspend fun act(serverId: String, action: ActivityObjectAction, row: ActivityObjectRow): DataResult<Unit> =
        result {
            networkClient.request(
                priority = DataRequestPriority.Foreground,
                path = action.path,
                method = "POST",
                serverIdOverride = ServerId(serverId),
                bodyText = encodeObjectActivityInput(row, withThroughSeq = action == ActivityObjectAction.Done),
            )
        }

    override suspend fun unfollow(serverId: String, row: ActivityObjectRow): DataResult<Unit> {
        if (row.objectKind != "artifact") return DataResult.Empty(EmptyReason.NoInboxItems)
        return result {
            networkClient.request(
                priority = DataRequestPriority.Foreground,
                path = "artifacts/${row.objectId.appLinkPathSegment()}/subscription",
                method = "DELETE",
                serverIdOverride = ServerId(serverId),
            )
        }
    }

    private suspend fun result(block: suspend () -> HomeNetworkResponse): DataResult<Unit> {
        val response =
            try {
                block()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                return DataResult.Error(ScreenError(code = "network"))
            }
        return if (response.isSuccessful) DataResult.Content(Unit) else DataResult.Error(ScreenError(code = "http_${response.code}", retryable = response.code >= 500 || response.code == 429))
    }
}
