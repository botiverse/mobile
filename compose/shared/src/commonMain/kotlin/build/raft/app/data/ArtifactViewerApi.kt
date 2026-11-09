package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the Artifact viewer's native bar reads (botiverse/slock staging a2654a29f9,
 * `packages/shared/src/webArtifactsContract.ts` `artifactWebPaths`, the shapes in
 * `packages/shared/src/workspaceArtifacts.ts`; the handlers `packages/server/src/apps/artifacts/`
 * `mount.ts`, `webViewer.ts`, `webComments.ts`). Every route reads the reader's server
 * (X-Server-Id), as the viewer's summaries do.
 *
 * - `GET /api/artifacts/:id/details[?version=<n>]` (slock staging fc1a4089, #12366,
 *   `artifactWebPaths.details`, `webViewer.ts` `details`) → `ArtifactViewerData` without `frame`:
 *   `card` (`ArtifactCard`, the summaries' card: `commentCount`, `home`, `latestVersion`),
 *   `versions` (newest first, `ArtifactVersionSummary` `{ number, publisher, createdAt, note,
 *   changedPaths }`), `viewer` and `following`. Signs no ticket and moves no read position (the
 *   page opening does), so it is outside the frame budget; `?version=` and the refusals as the
 *   viewer route's (404 NOT_VISIBLE for anything unseen).
 * - `GET /api/artifacts/:id/presence` → `ArtifactPresence` `{ viewing, readCurrentVersion,
 *   readOtherVersion }` (the reader left out; each list at most 20, `PRESENCE_READERS_SHOWN`;
 *   `viewing` always empty until the socket exists). The bar counts it as the web's summary line
 *   does ([artifactPresenceEntries], web `ArtifactPresence.tsx` `presenceEntries`).
 * - `PUT|DELETE /api/artifacts/:id/subscription` → `{ following: boolean }` (60 writes a minute).
 */
@Serializable
data class ArtifactVersionSummaryWireDto(
    val number: Int,
    /** `ArtifactShownActor` (no `id` outside the Artifact's server). */
    val publisher: ArtifactActorWireDto,
    val createdAt: String,
    val note: String? = null,
)

/** `ArtifactViewerData`, the fields the native bar reads. */
@Serializable
data class ArtifactViewerDataWireDto(
    val card: ArtifactCardWireDto,
    val versions: List<ArtifactVersionSummaryWireDto>,
    /** Who is signed in (`ArtifactActor`): left out of presence, added back as "You" ([artifactPresenceEntries]). */
    val viewer: ArtifactActorWireDto,
    val following: Boolean,
)

/** `ArtifactPresence.readCurrentVersion[]`: `{ actor: ArtifactActor, at }`. */
@Serializable
data class ArtifactPresenceReadWireDto(
    val actor: ArtifactActorWireDto,
    val at: String,
)

/**
 * `ArtifactPresence.viewing[]`: a person `{ actor (user), state: "viewing", path, since }` or an
 * agent `{ actor (agent), state: "reading" | "writing", path, since }`.
 */
@Serializable
data class ArtifactPresenceViewingWireDto(
    val actor: ArtifactActorWireDto,
    val state: String,
    val path: String? = null,
    val since: String,
)

/** `ArtifactPresence.readOtherVersion[]`: `{ actor, at, version }` (the last version it reached). */
@Serializable
data class ArtifactPresenceReadOtherWireDto(
    val actor: ArtifactActorWireDto,
    val at: String,
    val version: Int,
)

/** `ArtifactPresence` (`packages/shared/src/workspaceArtifacts.ts`). */
@Serializable
data class ArtifactPresenceWireDto(
    val viewing: List<ArtifactPresenceViewingWireDto>,
    val readCurrentVersion: List<ArtifactPresenceReadWireDto>,
    val readOtherVersion: List<ArtifactPresenceReadOtherWireDto>,
)

/** `ArtifactPresence` by actor key (web `actorKey`: `<type>:<id>`), in the route's order. */
data class ArtifactPresence(
    /** Here now: key and state (`viewing`, `reading`, `writing`). */
    val viewing: List<Pair<String, String>> = emptyList(),
    val readCurrentVersion: List<String> = emptyList(),
    val readOtherVersion: List<String> = emptyList(),
)

/** One presence entry, as web `presenceEntries` makes it. */
data class ArtifactPresenceEntry(val key: String, val state: String, val self: Boolean = false)

/**
 * web `ArtifactPresence.tsx` `presenceEntries` (slock staging a2654a29f9, lines 38-64), ported
 * as is: here now, then who read the current version, then who read only an older one, each
 * actor once, the viewer ([viewerKey]) never among them; the viewer is added last, as "You" read,
 * when on the current version ([viewerOnCurrentVersion]) or listed as having read it, and only
 * beside someone else (alone, no presence at all). No viewer (null): none added.
 */
fun artifactPresenceEntries(presence: ArtifactPresence, viewerKey: String?, viewerOnCurrentVersion: Boolean): List<ArtifactPresenceEntry> {
    val seen = mutableSetOf<String>().apply { viewerKey?.let(::add) }
    val out = mutableListOf<ArtifactPresenceEntry>()
    for ((key, state) in presence.viewing) {
        if (!seen.add(key)) continue
        out += ArtifactPresenceEntry(key, state)
    }
    var selfRead = viewerKey != null && viewerOnCurrentVersion
    for (key in presence.readCurrentVersion) {
        if (key == viewerKey) selfRead = true
        if (!seen.add(key)) continue
        out += ArtifactPresenceEntry(key, "read")
    }
    val older = mutableListOf<ArtifactPresenceEntry>()
    for (key in presence.readOtherVersion) {
        if (key == viewerKey || !seen.add(key)) continue
        older += ArtifactPresenceEntry(key, "readOlder")
    }
    if (viewerKey != null && selfRead && out.size + older.size > 0) out += ArtifactPresenceEntry(viewerKey, "read", self = true)
    return out + older
}

/**
 * The summary line's "N read" (web `ArtifactPresence`: `readCount`, everyone who read this version
 * or an older one, "You" included), as is (no "+": the web shows none); null when the web shows
 * no presence at all (no entries) or none read.
 */
fun artifactPresenceReadCount(entries: List<ArtifactPresenceEntry>): Int? =
    entries.count { it.state == "read" || it.state == "readOlder" }.takeIf { it > 0 }

/** web `actorKey`. */
internal fun artifactActorKey(type: String, id: String): String = "$type:$id"

/** The subscription route's answer. */
@Serializable
data class ArtifactSubscriptionWireDto(
    val following: Boolean,
)

/** One version in the native version list. */
data class ArtifactViewerVersion(
    val number: Int,
    /** `publisher.displayName`. */
    val publisherName: String,
    /** ISO 8601. */
    val createdAt: String,
)

/** What the native bar shows of `ArtifactViewerData`. */
data class ArtifactViewerDetails(
    val summary: ArtifactSummary,
    /** Newest first, as the server sends them. */
    val versions: List<ArtifactViewerVersion>,
    val following: Boolean,
    /** The signed-in viewer's actor key ([artifactActorKey]); null when the answer names no id. */
    val viewerKey: String? = null,
    /** `card.version === card.latestVersion`: the viewer is on the current version. */
    val onCurrentVersion: Boolean = true,
)

private val artifactViewerJson = Json { ignoreUnknownKeys = true }

/** The viewer route's answer; null when it can't be read (the bar then shows no counts). */
fun parseArtifactViewerDetails(bodyText: String?): ArtifactViewerDetails? {
    val wire = bodyText?.let { runCatching { artifactViewerJson.decodeFromString(ArtifactViewerDataWireDto.serializer(), it) }.getOrNull() } ?: return null
    val summary = wire.card.toArtifactSummaryOrNull() ?: return null
    return ArtifactViewerDetails(
        summary = summary,
        versions = wire.versions.filter { it.number > 0 }.map { ArtifactViewerVersion(it.number, it.publisher.displayName, it.createdAt) },
        following = wire.following,
        viewerKey = wire.viewer.id?.let { artifactActorKey(wire.viewer.type, it) },
        onCurrentVersion = wire.card.version == wire.card.latestVersion,
    )
}

/** The presence route's answer by actor key; null when it can't be read. An actor without an id is left out. */
fun parseArtifactPresence(bodyText: String?): ArtifactPresence? {
    val wire = bodyText?.let { runCatching { artifactViewerJson.decodeFromString(ArtifactPresenceWireDto.serializer(), it) }.getOrNull() } ?: return null
    fun key(actor: ArtifactActorWireDto) = actor.id?.let { artifactActorKey(actor.type, it) }
    return ArtifactPresence(
        viewing = wire.viewing.mapNotNull { entry -> key(entry.actor)?.let { it to entry.state } },
        readCurrentVersion = wire.readCurrentVersion.mapNotNull { key(it.actor) },
        readOtherVersion = wire.readOtherVersion.mapNotNull { key(it.actor) },
    )
}

/** `{ following }` from the subscription route; null when it can't be read. */
fun parseArtifactSubscription(bodyText: String?): Boolean? =
    bodyText?.let { runCatching { artifactViewerJson.decodeFromString(ArtifactSubscriptionWireDto.serializer(), it) }.getOrNull() }?.following

/** The native bar's reads and its one write, for one Server. Answers [DataResult]; nothing raw leaks upward. */
interface ArtifactViewerRepository {
    /** `GET /api/artifacts/:id/details[?version=<n>]` ([version]: the pinned version shown, null for the latest). */
    suspend fun details(serverId: String, artifactId: String, version: Int?): DataResult<ArtifactViewerDetails>

    /** `GET /api/artifacts/:id/presence`: who is here and who read it. */
    suspend fun presence(serverId: String, artifactId: String): DataResult<ArtifactPresence>

    /** `PUT` (follow) or `DELETE` (unfollow) `/api/artifacts/:id/subscription`: the following it answers. */
    suspend fun setFollowing(serverId: String, artifactId: String, following: Boolean): DataResult<Boolean>
}

internal fun artifactViewerPath(artifactId: String): String = "artifacts/${artifactCanonicalId(artifactId).appLinkPathSegment()}"

internal class NetworkArtifactViewerRepository(private val networkClient: RaftNetworkClient) : ArtifactViewerRepository {
    override suspend fun details(serverId: String, artifactId: String, version: Int?): DataResult<ArtifactViewerDetails> =
        read(
            parse = ::parseArtifactViewerDetails,
        ) {
            networkClient.request(
                // The on-screen bar's counts, beside the page that is already showing.
                priority = DataRequestPriority.Visible,
                path = artifactViewerPath(artifactId) + "/details",
                query = version?.takeIf { it > 0 }?.let { listOf("version" to it.toString()) }.orEmpty(),
                serverIdOverride = ServerId(serverId),
            )
        }

    override suspend fun presence(serverId: String, artifactId: String): DataResult<ArtifactPresence> =
        read(parse = ::parseArtifactPresence) {
            networkClient.request(
                priority = DataRequestPriority.Visible,
                path = artifactViewerPath(artifactId) + "/presence",
                serverIdOverride = ServerId(serverId),
            )
        }

    override suspend fun setFollowing(serverId: String, artifactId: String, following: Boolean): DataResult<Boolean> =
        read(parse = ::parseArtifactSubscription) {
            networkClient.request(
                // The person's own write.
                priority = DataRequestPriority.Foreground,
                path = artifactViewerPath(artifactId) + "/subscription",
                method = if (following) "PUT" else "DELETE",
                serverIdOverride = ServerId(serverId),
            )
        }

    private suspend fun <T : Any> read(parse: (String?) -> T?, block: suspend () -> HomeNetworkResponse): DataResult<T> {
        val response =
            try {
                block()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                return DataResult.Error(ScreenError(code = "network"))
            }
        if (!response.isSuccessful) {
            return DataResult.Error(ScreenError(code = "http_${response.code}", retryable = response.code >= 500 || response.code == 429))
        }
        return parse(response.bodyText)?.let { DataResult.Content(it) } ?: DataResult.Error(ScreenError(code = "parse", retryable = false))
    }
}
