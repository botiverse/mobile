package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/** Production trace-upload receiver (matches `slock` `DEFAULT_TRACE_UPLOAD_URL`). */
const val RAFT_DEFAULT_TRACE_UPLOAD_URL = "https://slock-trace-upload.botiverse.dev"

/**
 * Staging trace-upload receiver routed via `/__trace` on the AWS staging API host
 * (matches `slock` `STAGING_TRACE_UPLOAD_URL`).
 */
const val RAFT_STAGING_TRACE_UPLOAD_URL = "https://api-aws-staging.botiverse.dev/__trace"

private const val RAFT_STAGING_SERVER_HOST = "api-aws-staging.botiverse.dev"
private val RAFT_PRODUCTION_SERVER_HOSTS: Set<String> = setOf("api.raft.build", "api.slock.ai")

/**
 * Default trace / feedback upload receiver for a client connected to [apiBaseUrl].
 *
 * Parity with `botiverse/slock` `packages/shared/src/traceUploadUrl.ts` (`defaultTraceUploadUrlForServer`):
 * - `https://api-aws-staging.botiverse.dev` (HTTPS on default port 443, no userinfo) -> [RAFT_STAGING_TRACE_UPLOAD_URL]
 * - `https://api.raft.build` / `https://api.slock.ai` (HTTPS on default port 443, no userinfo) -> [RAFT_DEFAULT_TRACE_UPLOAD_URL]
 * - Any other server (custom / self-hosted / dev / localhost / http / non-443 port / userinfo / malformed) -> `null`
 *   (skip upload cleanly rather than posting to another environment's receiver).
 */
@RaftCoreInternalApi
fun defaultTraceUploadUrlForServer(apiBaseUrl: String?): String? {
    val raw = apiBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (raw.any { it.code < 0x20 || it.code == 0x7f }) return null
    val schemeSep = raw.indexOf("://")
    if (schemeSep <= 0) return null
    if (!raw.substring(0, schemeSep).equals("https", ignoreCase = true)) return null

    val afterScheme = raw.substring(schemeSep + 3)
    if (afterScheme.isEmpty()) return null
    val authorityEnd =
        afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) afterScheme.length else it }
    val authority = afterScheme.substring(0, authorityEnd)
    if (authority.isEmpty() || '@' in authority || '\\' in authority) return null
    if (authority.any { it.code < 0x21 || it.code > 0x7e }) return null

    val colon = authority.indexOf(':')
    val host: String
    if (colon >= 0) {
        if (authority.indexOf(':', colon + 1) >= 0) return null
        host = authority.substring(0, colon).lowercase()
        val port = authority.substring(colon + 1)
        // WHATWG URL treats empty port after ':' or numeric 443 (including leading zeros) as default HTTPS port "".
        if (port.isNotEmpty() && !(port.all { it in '0'..'9' } && port.trimStart('0') == "443")) {
            return null
        }
    } else {
        host = authority.lowercase()
    }

    return when {
        host == RAFT_STAGING_SERVER_HOST -> RAFT_STAGING_TRACE_UPLOAD_URL
        host in RAFT_PRODUCTION_SERVER_HOSTS -> RAFT_DEFAULT_TRACE_UPLOAD_URL
        else -> null
    }
}
