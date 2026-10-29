package build.raft.app.data

import build.raft.app.sharedSettingsResolvedWebBaseUrl

/**
 * Workspace Artifacts links in a message's text (botiverse/slock feat/workspace-artifacts
 * 542e922d6: `packages/shared/src/artifactRef.ts` `parseArtifactRef`, web
 * `packages/web/src/apps/artifacts/artifactLinks.ts` `artifactLinkTarget`), behind the per-server
 * flag [WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY].
 *
 * A link names an Artifact only on one of Raft's own app hosts (`appHosts`, from
 * `GET /api/public/client-config`, [SharedClientConfigStore]), each compared whole:
 * `https://<app host>/artifacts/<id>[/v/<n>]` and the accepted `…/s/<server>/artifacts/<id>[/v/<n>]`,
 * `<id>` either `pt_…` or a short id (slock staging 380482116, `artifactShortId.ts`).
 * `evil.example/artifacts/pt_x` and `app.raft.build.evil.example/artifacts/pt_x` stay plain links.
 *
 * Like the shared parser, two fixed hosts name an Artifact by id whatever `appHosts` says: the
 * redirect host `artifacts.raft.build/<id>[/v/<n>]`, and the content origin
 * `a-pt-<uuid>.raftusercontent.com` (Artifact `pt_<uuid>`; only the id is read from the host, the
 * chip opens the Artifact in Raft, never the content domain). Nothing else on `raftusercontent.com`
 * (e.g. `<id>.w.raftusercontent.com`) is an Artifact link, and it is never an app host, whatever a
 * config says. The standalone app's legacy links (`raft-artifacts.com/p/<server>/<name>`) name an
 * Artifact by name, not id, and are not read (the shared parser doesn't resolve them to an id either).
 */
/**
 * An Artifact by id, pinned to a version or following the latest (null). [id] is as the link wrote
 * it: `pt_…` or a 22-character short id ([ArtifactShortId.kt]); the viewer's page gets it as it is.
 */
data class ArtifactLinkTarget(val id: String, val version: Int? = null) {
    /**
     * `pt_<uuid>` for a short id, else [id] (slock `parseArtifactId`): what the API's `id` fields
     * and `POST /api/objects/summaries` name the Artifact by (the summaries route doesn't read
     * short ids), and what tells two links to the same Artifact apart from two Artifacts.
     */
    val canonicalId: String get() = artifactCanonicalId(id)

    /**
     * The reference's key, as the web's `objectRefKey` spells it (`pt_x`, `pt_x;version=2`, on
     * [canonicalId] as the web's parsed id is): one answer per key, and the summaries answer
     * echoes the reference it answers.
     */
    val key: String get() = if (version == null) canonicalId else "$canonicalId;version=$version"
}

/**
 * A bare Artifact id exactly as written (no trimming), kept as written: `pt_…`, or a short id
 * (22 characters of Base58 that fit 16 bytes). Null for anything else. slock `parseArtifactId`,
 * which reads a short id as `pt_<uuid>`; here [artifactCanonicalId] does that.
 */
fun artifactIdOrNull(value: String): String? =
    value.takeIf { ARTIFACT_ID.matches(it) || decodeArtifactShortId(it) != null }

/** `pt_<uuid>` for a short id; anything else as it is. */
fun artifactCanonicalId(id: String): String = decodeArtifactShortId(id)?.let { "pt_$it" } ?: id

/** The content origin's registrable domain: Artifact bytes, never a Raft page. */
internal const val ARTIFACT_CONTENT_DOMAIN: String = "raftusercontent.com"

/** slock `artifactRef.ts`: the redirect host, `artifacts.raft.build/<id>[/v/<n>]`. */
internal const val ARTIFACT_REDIRECT_HOST: String = "artifacts.raft.build"

/** slock `artifactRef.ts` `CONTENT_HOST`: `a-pt-<uuid>.raftusercontent.com` serves Artifact `pt_<uuid>`. */
private val ARTIFACT_CONTENT_ORIGIN =
    Regex("^a-pt-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.raftusercontent\\.com$")

private val ARTIFACT_ID = Regex("^pt_[A-Za-z0-9-]{1,64}$")
private val ARTIFACT_VERSION = Regex("^[1-9][0-9]{0,8}$")

/** Whether [host] (a lowercase hostname) is the content domain or under it. */
internal fun isArtifactContentHost(host: String): Boolean =
    host == ARTIFACT_CONTENT_DOMAIN || host.endsWith(".$ARTIFACT_CONTENT_DOMAIN")

/**
 * The Artifact [link] names, or null. [appHosts] are hostnames (no port, lowercase); empty means
 * no link is an Artifact's.
 */
fun parseArtifactLinkTarget(link: String, appHosts: Collection<String>): ArtifactLinkTarget? {
    val value = link.trim()
    val rest =
        when {
            value.startsWith("https://", ignoreCase = true) -> value.substring(8)
            value.startsWith("http://", ignoreCase = true) -> value.substring(7)
            else -> return null
        }
    if (rest.any { it.isWhitespace() || it == '\\' }) return null
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    val authority = rest.substring(0, authorityEnd)
    // `user@host`: the host is after the last `@` (a URL parser's hostname), never before it.
    val hostAndPort = authority.substringAfterLast('@')
    if (hostAndPort.startsWith("[")) return null
    val host = hostAndPort.substringBefore(':').lowercase().removeSuffix(".")
    if (host.isEmpty()) return null
    // The content origin names its Artifact by host alone (shared parser order: first).
    ARTIFACT_CONTENT_ORIGIN.matchEntire(host)?.let { return ArtifactLinkTarget("pt_${it.groupValues[1]}") }
    if (isArtifactContentHost(host)) return null
    val path = rest.substring(authorityEnd).substringBefore('#').substringBefore('?')
    val segments = path.split('/').filter { it.isNotEmpty() }.map { it.artifactPercentDecoded() ?: return null }
    if (host == ARTIFACT_REDIRECT_HOST) return artifactIdWithVersion(segments)
    if (appHosts.none { it.trim().lowercase().removeSuffix(".") == host }) return null
    return when {
        segments.firstOrNull() == "artifacts" -> artifactIdWithVersion(segments.drop(1))
        segments.size > 2 && segments[0] == "s" && segments[2] == "artifacts" -> artifactIdWithVersion(segments.drop(3))
        else -> null
    }
}

/** An id, then an optional `v/<n>`; anything after that (a file path, `raw`) is ignored. */
private fun artifactIdWithVersion(segments: List<String>): ArtifactLinkTarget? {
    val id = segments.getOrNull(0)?.let(::artifactIdOrNull) ?: return null
    val v = segments.getOrNull(1)
    val n = segments.getOrNull(2)
    if (v == "v" && n != null) {
        if (!ARTIFACT_VERSION.matches(n)) return null
        return ArtifactLinkTarget(id, n.toInt())
    }
    return ArtifactLinkTarget(id)
}

/** `decodeURIComponent` for one path segment; null on a malformed escape. */
private fun String.artifactPercentDecoded(): String? {
    if ('%' !in this) return this
    val bytes = ArrayList<Byte>(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%') {
            if (index + 2 >= length) return null
            val byte = substring(index + 1, index + 3).toIntOrNull(16) ?: return null
            bytes += byte.toByte()
            index += 3
        } else {
            char.toString().encodeToByteArray().forEach { bytes += it }
            index++
        }
    }
    return runCatching { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}

/** At most this many references in one summaries request (slock `OBJECT_SUMMARIES_MAX_REFS`). */
const val OBJECT_SUMMARIES_MAX_REFS: Int = 50

/**
 * At most this many Artifact references a message reads, duplicates dropped, in message order (web
 * `useMessageLinkCards.ts` `MESSAGE_OBJECT_LINK_LIMIT`, as the App link read takes); links past it
 * stay the author's links.
 */
const val MESSAGE_OBJECT_LINK_LIMIT: Int = 10

/**
 * Whether [link] is an Artifact's by id (shared `parseArtifactRef(...).kind === "id"`): the links the
 * server's App link read leaves out before it takes its first three (`appLinkReadUrls`), whatever
 * the Artifacts flag says.
 */
fun isArtifactIdLink(link: String, appHosts: Collection<String>): Boolean = parseArtifactLinkTarget(link, appHosts) != null

/**
 * The Artifacts a message's bare links name, in message order, each reference once, at most
 * [MESSAGE_OBJECT_LINK_LIMIT]: links whose text is their own address (an autolink, `<url>`, or
 * `[url](url)`), not links in code.
 */
fun artifactMessageLinkTargets(content: String, appHosts: Collection<String>): List<ArtifactLinkTarget> {
    if ("://" !in content) return emptyList()
    return appLinkMessageBareLinks(content, limit = Int.MAX_VALUE) { raw ->
        raw.takeIf { parseArtifactLinkTarget(it, appHosts) != null }
    }
        .mapNotNull { parseArtifactLinkTarget(it, appHosts) }
        .distinctBy { it.key }
        .take(MESSAGE_OBJECT_LINK_LIMIT)
}

/**
 * The Artifact's page in Raft on this environment's web origin ([webBaseUrl], AGENTS.md
 * "Environment Web Link URL Rule"): `<web>/artifacts/<id>`, with `/v/<n>` when pinned.
 */
fun artifactWebUrl(webBaseUrl: String, target: ArtifactLinkTarget): String {
    val base = sharedSettingsResolvedWebBaseUrl(webBaseUrl).trimEnd('/')
    return "$base/artifacts/${target.id}" + (target.version?.let { "/v/$it" } ?: "")
}
