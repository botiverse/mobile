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
 * `https://<app host>/artifacts/<id>[/v/<n>]` and the accepted `…/s/<server>/artifacts/<id>[/v/<n>]`.
 * `evil.example/artifacts/pt_x` and `app.raft.build.evil.example/artifacts/pt_x` stay plain links.
 *
 * Deliberately narrower than the shared parser: the content domain (`raftusercontent.com`, as
 * `<id>.w.raftusercontent.com`) is never Raft's own here, whatever a config says, and the fixed
 * redirect / legacy hosts (`artifacts.raft.build`, `raft-artifacts.com`) are not read.
 */
/** An Artifact by id, pinned to a version or following the latest (null). */
data class ArtifactLinkTarget(val id: String, val version: Int? = null) {
    /**
     * The reference's key, as the web's `objectRefKey` spells it (`pt_x`, `pt_x;version=2`): one
     * answer per key, and the summaries answer echoes the reference it answers.
     */
    val key: String get() = if (version == null) id else "$id;version=$version"
}

/** The content origin's registrable domain: Artifact bytes, never a Raft page. */
internal const val ARTIFACT_CONTENT_DOMAIN: String = "raftusercontent.com"

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
    if (host.isEmpty() || isArtifactContentHost(host)) return null
    if (appHosts.none { it.trim().lowercase() == host }) return null
    val path = rest.substring(authorityEnd).substringBefore('#').substringBefore('?')
    val segments = path.split('/').filter { it.isNotEmpty() }.map { it.artifactPercentDecoded() ?: return null }
    return when {
        segments.firstOrNull() == "artifacts" -> artifactIdWithVersion(segments.drop(1))
        segments.size > 2 && segments[0] == "s" && segments[2] == "artifacts" -> artifactIdWithVersion(segments.drop(3))
        else -> null
    }
}

/** An id, then an optional `v/<n>`; anything after that (a file path, `raw`) is ignored. */
private fun artifactIdWithVersion(segments: List<String>): ArtifactLinkTarget? {
    val id = segments.getOrNull(0)?.takeIf(ARTIFACT_ID::matches) ?: return null
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
 * The Artifacts a message's bare links name, in message order, each reference once, at most
 * [OBJECT_SUMMARIES_MAX_REFS]: links whose text is their own address (an autolink, `<url>`, or
 * `[url](url)`), not links in code.
 */
fun artifactMessageLinkTargets(content: String, appHosts: Collection<String>): List<ArtifactLinkTarget> {
    if (appHosts.isEmpty() || "/artifacts/" !in content) return emptyList()
    return appLinkMessageBareLinks(content, limit = Int.MAX_VALUE) { raw ->
        raw.takeIf { parseArtifactLinkTarget(it, appHosts) != null }
    }
        .mapNotNull { parseArtifactLinkTarget(it, appHosts) }
        .distinctBy { it.key }
        .take(OBJECT_SUMMARIES_MAX_REFS)
}

/**
 * The Artifact's page in Raft on this environment's web origin ([webBaseUrl], AGENTS.md
 * "Environment Web Link URL Rule"): `<web>/artifacts/<id>`, with `/v/<n>` when pinned.
 */
fun artifactWebUrl(webBaseUrl: String, target: ArtifactLinkTarget): String {
    val base = sharedSettingsResolvedWebBaseUrl(webBaseUrl).trimEnd('/')
    return "$base/artifacts/${target.id}" + (target.version?.let { "/v/$it" } ?: "")
}
