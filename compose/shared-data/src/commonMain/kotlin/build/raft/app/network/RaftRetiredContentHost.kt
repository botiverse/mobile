package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

/**
 * The production R2 public domain that served message attachments, thumbnails and avatars until
 * 2026-11-09. Since then the server hands out the same objects on [RaftContentCdnHost] (same path);
 * from 2026-11-17 the old host only 302-redirects there, and it is then switched off. Message
 * payloads stored on the device before the move (local database, cached snapshots) still carry the
 * old host.
 */
@RaftCoreInternalApi const val RaftRetiredContentHost = "content.raft.build"

/** The production CDN host that replaced [RaftRetiredContentHost]. */
@RaftCoreInternalApi const val RaftContentCdnHost = "cdn.raft.build"

private const val HttpsScheme = "https://"

/**
 * [url] with the retired production content host replaced by the CDN host, everything after the
 * host (path, query, fragment) kept byte for byte; any other URL is returned unchanged.
 *
 * Only `https://content.raft.build` with exactly that authority is rewritten (scheme and host
 * compared case-insensitively, as URL hosts are): subdomains, look-alikes, an explicit port, user
 * info and plain `http://` stay as they are. This is a production asset host, not the web app URL,
 * so it does not follow the Developer environment: staging and local environments serve their
 * assets from other hosts and are never touched.
 */
@RaftCoreInternalApi fun raftRewriteRetiredContentHost(url: String): String {
    if (!url.startsWith(HttpsScheme, ignoreCase = true)) return url
    val authorityStart = HttpsScheme.length
    val authorityEnd =
        url.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart).let { if (it < 0) url.length else it }
    if (authorityEnd - authorityStart != RaftRetiredContentHost.length) return url
    if (!url.regionMatches(authorityStart, RaftRetiredContentHost, 0, RaftRetiredContentHost.length, ignoreCase = true)) {
        return url
    }
    return HttpsScheme + RaftContentCdnHost + url.substring(authorityEnd)
}
