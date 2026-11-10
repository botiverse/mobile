package build.raft.app.data

// The Artifact viewer's native bar models (web `ArtifactPresence.tsx`, `workspaceArtifacts.ts`). Only
// SharedArtifactViewerBarStore uses them, and the app never builds that store; the network repository and
// its parsers are gone.

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
    /** The signed-in viewer's actor key (web `actorKey`: `<type>:<id>`); null when the answer names no id. */
    val viewerKey: String? = null,
    /** `card.version === card.latestVersion`: the viewer is on the current version. */
    val onCurrentVersion: Boolean = true,
)

/** The native bar's reads and its one write, for one Server. Answers [DataResult]; nothing raw leaks upward. */
interface ArtifactViewerRepository {
    /** `GET /api/artifacts/:id/details[?version=<n>]` ([version]: the pinned version shown, null for the latest). */
    suspend fun details(serverId: String, artifactId: String, version: Int?): DataResult<ArtifactViewerDetails>

    /** `GET /api/artifacts/:id/presence`: who is here and who read it. */
    suspend fun presence(serverId: String, artifactId: String): DataResult<ArtifactPresence>

    /** `PUT` (follow) or `DELETE` (unfollow) `/api/artifacts/:id/subscription`: the following it answers. */
    suspend fun setFollowing(serverId: String, artifactId: String, following: Boolean): DataResult<Boolean>
}
