package build.raft.app.network

import build.raft.app.data.featureFlagClientOs
import kotlin.random.Random

/**
 * Request semantics shared by the app's HTTP transport ([KtorRaftHttpExecutor]): upload budgets,
 * trace and identity headers, edge-block detection. Moved here from the NetworkKMM executor, which
 * was removed with the Ktor 3 switch.
 */
internal fun createPlatformRaftHttpExecutor(
    warningLogger: (String) -> Unit
): RaftHttpExecutor = KtorRaftHttpExecutor(warningLogger)

/**
 * task #783 (human ruling in `#proj-chat:8b673a88`): streaming fileRef
 * uploads get a 3-minute total budget instead of the generic 30s request
 * deadline — a healthy 190 MiB upload legitimately outlives 30s. Ordinary
 * requests and in-memory bytes multipart keep [RAFT_REQUEST_TIMEOUT_MILLIS];
 * on timeout the existing failed-attachment terminal UI (Upload failed /
 * Tap to retry, draft preserved, zero send side effects) stays as-is.
 */
internal const val RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS = 180_000L

/**
 * task #1135: keep connect at 10s and leave the total upload budget
 * unchanged. This presigned PUT path is normally pinned to curl via
 * http1Only, but Android's allowedEngines can fall back to Ktor; Ktor has no
 * Happy Eyeballs, so a connect timeout is the decision to abandon the whole
 * address family, and 30s would drag an IPv6-blackholed user's cold connect
 * to 30s.
 */
/**
 * task #321 / #783 follow-up (human ruling by @artin on 2026-09-11 in
 * `#proj-mobile:aaad615a`): the presigned direct-upload PUT carries the same
 * file stream as multipart, but a 160 MiB cross-region PUT can legitimately
 * outlive the multipart 3-minute budget. Direct-upload streaming file bodies
 * therefore get a 30-minute total budget; multipart streaming keeps
 * [RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS] unchanged.
 */
internal const val RAFT_DIRECT_UPLOAD_TOTAL_TIMEOUT_MILLIS = 1_800_000L

/**
 * Only multipart requests that stream a fileRef body get the upload budget;
 * a part without both an opener and a fileRef maps to NetworkBody.Bytes and
 * keeps the ordinary bound.
 */
internal fun List<MultipartFile>.hasStreamingFileRefParts(): Boolean =
    any { it.streamOpener != null && it.fileRef != null }

private const val RAFT_REQUEST_ID_HEADER = "x-request-id"
private const val RAFT_USER_AGENT_HEADER = "User-Agent"

/**
 * Raft API requests send the app User-Agent with its device token ([currentRaftNetworkUserAgent]):
 * the server reads the product-event `$device_type` from it. A caller's own User-Agent wins.
 */
internal fun withRaftNetworkUserAgent(
    headers: Map<String, String>,
    userAgent: () -> String = ::currentRaftNetworkUserAgent,
): Map<String, String> {
    if (headers.keys.any { it.equals(RAFT_USER_AGENT_HEADER, ignoreCase = true) }) return headers
    val value = runCatching(userAgent).getOrNull()?.takeIf { it.isNotBlank() } ?: return headers
    return headers + (RAFT_USER_AGENT_HEADER to value)
}

internal const val RAFT_CLIENT_PLATFORM_HEADER = "X-Raft-Client-Platform"

/**
 * Raft API requests name the native app they come from: `android` or `ios` ([featureFlagClientOs],
 * the app's one platform name). The server records it as the `account_created` platform (botiverse/slock
 * `signupClientPlatform`, which accepts exactly those lowercase values). Every API request carries it,
 * so no signup or sign-in route can miss it. A caller's own value wins.
 */
internal fun withRaftClientPlatform(
    headers: Map<String, String>,
    platform: () -> String = ::featureFlagClientOs,
): Map<String, String> {
    if (headers.keys.any { it.equals(RAFT_CLIENT_PLATFORM_HEADER, ignoreCase = true) }) return headers
    val value = runCatching(platform).getOrNull()?.takeIf { it.isNotBlank() } ?: return headers
    return headers + (RAFT_CLIENT_PLATFORM_HEADER to value)
}

/** The app-identity headers every Raft API request ([execute] and multipart) carries. */
internal fun withRaftApiClientHeaders(headers: Map<String, String>): Map<String, String> =
    withRaftClientPlatform(withRaftNetworkUserAgent(headers))
private const val RAFT_TRACEPARENT_HEADER = "traceparent"

internal fun raftTraceHeaders(
    headers: Map<String, String>,
    randomBytes: (Int) -> ByteArray = { size -> Random.nextBytes(size) }
): Map<String, String> {
    val requestIdEntry = headers.entries.firstOrNull { it.key.equals(RAFT_REQUEST_ID_HEADER, true) }
    val traceparentEntry = headers.entries.firstOrNull { it.key.equals(RAFT_TRACEPARENT_HEADER, true) }
    if (requestIdEntry != null && traceparentEntry != null) return headers

    val traceId = randomBytes(16).toNonZeroLowerHex()
    val spanId = randomBytes(8).toNonZeroLowerHex()
    return buildMap {
        putAll(headers)
        if (requestIdEntry == null) put(RAFT_REQUEST_ID_HEADER, "mobile-$traceId")
        if (traceparentEntry == null) put(RAFT_TRACEPARENT_HEADER, "00-$traceId-$spanId-01")
    }
}

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    byte.toUByte().toString(radix = 16).padStart(2, '0')
}

private fun ByteArray.toNonZeroLowerHex(): String {
    if (any { it != 0.toByte() }) return toLowerHex()
    return copyOf().apply { this[lastIndex] = 1 }.toLowerHex()
}

/**
 * True when a 403 response body is not the API's JSON error envelope but an
 * HTML block page produced by an edge layer (CDN/WAF/corporate proxy).
 *
 * Two signals, either is sufficient:
 * - a `Content-Type: text/html` response header, or
 * - a body that starts with `<` after whitespace — the API never emits XML/HTML
 *   payloads, and JSON bodies always start with `{` or `[`.
 */
internal fun isEdgeBlockedErrorBody(
    status: Int,
    headers: Map<String, List<String>>,
    bodyText: String?
): Boolean {
    if (status != 403) return false
    val contentType =
        headers.entries
            .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
    if (contentType != null && contentType.equals("text/html", ignoreCase = true)) return true
    return bodyText?.trimStart()?.startsWith("<") == true
}

internal fun String.sanitizeMultipartFileName(): String =
    replace("\"", "'").replace("\r", "").replace("\n", "")
