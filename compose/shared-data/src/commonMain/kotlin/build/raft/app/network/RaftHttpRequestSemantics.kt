package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.featureFlagClientOs
import kotlin.random.Random

/**
 * Request semantics shared by the app's HTTP transport ([KtorRaftHttpExecutor]): upload budgets,
 * trace and identity headers, edge-block detection. Moved here from the NetworkKMM executor, which
 * was removed with the Ktor 3 switch.
 */
@RaftCoreInternalApi fun createPlatformRaftHttpExecutor(
    warningLogger: (String) -> Unit
): RaftHttpExecutor =
    // PROTOTYPE: the login-free capture's in-process fake server (fixture builds only).
    RaftHttpExecutorOverrides.executorFactory?.invoke() ?: KtorRaftHttpExecutor(warningLogger)

/**
 * task #783 (human ruling in `#proj-chat:8b673a88`): streaming fileRef
 * uploads get a 3-minute total budget instead of the generic 30s request
 * deadline — a healthy 190 MiB upload legitimately outlives 30s. Ordinary
 * requests and in-memory bytes multipart keep [RAFT_REQUEST_TIMEOUT_MILLIS];
 * on timeout the existing failed-attachment terminal UI (Upload failed /
 * Tap to retry, draft preserved, zero send side effects) stays as-is.
 */
@RaftCoreInternalApi const val RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS = 180_000L

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
@RaftCoreInternalApi const val RAFT_DIRECT_UPLOAD_TOTAL_TIMEOUT_MILLIS = 1_800_000L

/**
 * The upload session's complete call (`POST attachments/upload-sessions/:id/complete`) gets 45 s, as
 * on the web (`ATTACHMENT_UPLOAD_COMPLETE_TIMEOUT_MS`, botiverse/slock #15743). The server checks the
 * stored object under its own 30 s storage request timeout, so the 30 s of the other control calls
 * (capabilities, create, status) would cut its answer off; this waits past it. The scheduler and the
 * transport both use it, so neither ends the call first.
 */
@RaftCoreInternalApi const val RAFT_ATTACHMENT_UPLOAD_COMPLETE_TIMEOUT_MILLIS = 45_000L

/**
 * Releasing a completed draft attachment (`DELETE attachments/:id/draft`, botiverse/slock #17244)
 * gets a 15 s whole-call budget, matching web `discardDraftAttachment.ts` (`timeout: 15_000`).
 */
@RaftCoreInternalApi const val RAFT_ATTACHMENT_DRAFT_DISCARD_TIMEOUT_MILLIS = 15_000L

/**
 * Only multipart requests that stream a fileRef body get the upload budget;
 * a part without both an opener and a fileRef maps to NetworkBody.Bytes and
 * keeps the ordinary bound.
 */
@RaftCoreInternalApi fun List<MultipartFile>.hasStreamingFileRefParts(): Boolean =
    any { it.streamOpener != null && it.fileRef != null }

private const val RAFT_REQUEST_ID_HEADER = "x-request-id"
private const val RAFT_USER_AGENT_HEADER = "User-Agent"

/**
 * Raft API requests send the app User-Agent with its device token ([currentRaftNetworkUserAgent]):
 * the server reads the product-event `$device_type` from it. A caller's own User-Agent wins.
 */
@RaftCoreInternalApi fun withRaftNetworkUserAgent(
    headers: Map<String, String>,
    userAgent: () -> String = ::currentRaftNetworkUserAgent,
): Map<String, String> {
    if (headers.keys.any { it.equals(RAFT_USER_AGENT_HEADER, ignoreCase = true) }) return headers
    val value = runCatching(userAgent).getOrNull()?.takeIf { it.isNotBlank() } ?: return headers
    return headers + (RAFT_USER_AGENT_HEADER to value)
}

@RaftCoreInternalApi const val RAFT_CLIENT_PLATFORM_HEADER = "X-Raft-Client-Platform"

/**
 * Raft API requests name the native app they come from: `android` or `ios` ([featureFlagClientOs],
 * the app's one platform name). The server records it as the `account_created` platform (botiverse/slock
 * `signupClientPlatform`, which accepts exactly those lowercase values). Every API request carries it,
 * so no signup or sign-in route can miss it. A caller's own value wins.
 */
@RaftCoreInternalApi fun withRaftClientPlatform(
    headers: Map<String, String>,
    platform: () -> String = ::featureFlagClientOs,
): Map<String, String> {
    if (headers.keys.any { it.equals(RAFT_CLIENT_PLATFORM_HEADER, ignoreCase = true) }) return headers
    val value = runCatching(platform).getOrNull()?.takeIf { it.isNotBlank() } ?: return headers
    return headers + (RAFT_CLIENT_PLATFORM_HEADER to value)
}

@RaftCoreInternalApi const val RAFT_CLIENT_VERSION_HEADER = "X-Raft-Client-Version"

/**
 * Raft API requests name the app version they come from: the version name alone (`1.26.0`, the
 * Android `versionName` / iOS `CFBundleShortVersionString`, no build number). botiverse/slock reads it
 * for the signed-in devices list (`GET /api/me/sessions`, `services/sessionClient.ts`): the sign-in and
 * every refresh stamp it on the session, and the server's own fallback (the `Raft/<version>+<build>`
 * User-Agent) keeps the version name only, so this sends the same value. No version installed, no
 * header. A caller's own value wins.
 */
@RaftCoreInternalApi fun withRaftClientVersion(
    headers: Map<String, String>,
    version: () -> String? = { RaftAppIdentityRegistry.current()?.version },
): Map<String, String> {
    if (headers.keys.any { it.equals(RAFT_CLIENT_VERSION_HEADER, ignoreCase = true) }) return headers
    val value = runCatching(version).getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return headers
    return headers + (RAFT_CLIENT_VERSION_HEADER to value)
}

/** The app-identity headers every Raft API request ([execute] and multipart) carries. */
@RaftCoreInternalApi fun withRaftApiClientHeaders(headers: Map<String, String>): Map<String, String> =
    withRaftClientVersion(withRaftClientPlatform(withRaftNetworkUserAgent(headers)))
private const val RAFT_TRACEPARENT_HEADER = "traceparent"

/** A W3C trace id (32 hex digits) and span id (16), neither all zero. */
@RaftCoreInternalApi class RaftTraceIds(val traceId: String, val spanId: String) {
    /** `traceparent` with the sampled flag, as every app API request sends it. */
    val traceparent: String get() = "00-$traceId-$spanId-01"
}

@RaftCoreInternalApi fun newRaftTraceIds(randomBytes: (Int) -> ByteArray = { size -> Random.nextBytes(size) }): RaftTraceIds =
    RaftTraceIds(traceId = randomBytes(16).toNonZeroLowerHex(), spanId = randomBytes(8).toNonZeroLowerHex())

@RaftCoreInternalApi fun raftTraceHeaders(
    headers: Map<String, String>,
    randomBytes: (Int) -> ByteArray = { size -> Random.nextBytes(size) }
): Map<String, String> {
    val requestIdEntry = headers.entries.firstOrNull { it.key.equals(RAFT_REQUEST_ID_HEADER, true) }
    val traceparentEntry = headers.entries.firstOrNull { it.key.equals(RAFT_TRACEPARENT_HEADER, true) }
    if (requestIdEntry != null && traceparentEntry != null) return headers

    val ids = newRaftTraceIds(randomBytes)
    return buildMap {
        putAll(headers)
        if (requestIdEntry == null) put(RAFT_REQUEST_ID_HEADER, "mobile-${ids.traceId}")
        if (traceparentEntry == null) put(RAFT_TRACEPARENT_HEADER, ids.traceparent)
    }
}

/**
 * Headers of a presigned object-store PUT: the `x-request-id` the engine keys its connection facts
 * by, and no `traceparent`. The storage host is a third party and no server span of ours answers it;
 * the web's direct PUT sends none either (botiverse/slock `directAttachmentUpload.ts`).
 */
/** The trace id of a W3C `traceparent` header among [headers], for the request's log line. */
@RaftCoreInternalApi fun Map<String, String>.raftSentTraceIdOrNull(): String? =
    entries.firstOrNull { it.key.equals(RAFT_TRACEPARENT_HEADER, true) }
        ?.value
        ?.split('-')
        ?.getOrNull(1)
        ?.takeIf { id -> id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' } }

@RaftCoreInternalApi fun raftRequestIdHeaders(
    headers: Map<String, String>,
    randomBytes: (Int) -> ByteArray = { size -> Random.nextBytes(size) }
): Map<String, String> {
    // The session's PUT headers are Content-Type and If-None-Match only
    // (requireValidAttachmentUploadPutHeaders); a traceparent from any other caller is dropped here.
    val kept = headers.filterKeys { !it.equals(RAFT_TRACEPARENT_HEADER, true) }
    if (kept.keys.any { it.equals(RAFT_REQUEST_ID_HEADER, true) }) return kept
    return kept + (RAFT_REQUEST_ID_HEADER to "mobile-${randomBytes(16).toNonZeroLowerHex()}")
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
@RaftCoreInternalApi fun isEdgeBlockedErrorBody(
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

@RaftCoreInternalApi fun String.sanitizeMultipartFileName(): String =
    replace("\"", "'").replace("\r", "").replace("\n", "")
