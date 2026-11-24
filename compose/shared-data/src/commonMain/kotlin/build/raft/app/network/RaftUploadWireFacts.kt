package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

/**
 * What the transport measured for one upload request (the multipart POST to the API, or the presigned
 * PUT to the object store), for `slock.attachment.upload_finished`. A value the platform could not
 * measure is null, never 0.
 *
 * - [requestBytes]: the length of the file body sent (the file parts of a multipart POST, without the
 *   multipart framing, so nothing of a file name's length is in it), a number only.
 * - [connectMs]: setting up a new connection for this request (DNS, TCP and TLS); null when a pooled
 *   connection was reused. Android / desktop (OkHttp `EventListener`): the first of `dnsStart` /
 *   `connectStart` to `connectEnd`, which OkHttp reports after the TLS handshake (with fast fallback
 *   the span covers the raced attempts). iOS (`NSURLSessionTaskTransactionMetrics`, not
 *   `reusedConnection`): `domainLookupStartDate` (or `connectStartDate`) to `connectEndDate`, which
 *   NSURLSession reports after the TLS handshake.
 * - [requestBodyMs]: the time spent sending the request. Android / desktop: `requestBodyStart` to
 *   `requestBodyEnd`. iOS: `requestStartDate` to `requestEndDate`, which also covers the request
 *   headers. Both end when the last byte was handed to the socket, not when it left the device: a
 *   body smaller than the socket's send buffer reads as almost 0 and its real send time shows up in
 *   [responseWaitMs]. The sum of the two is exact; the split is reliable only for bodies well above
 *   the send buffer.
 * - [responseWaitMs]: from that last body byte to the first byte of the response (Android:
 *   `requestBodyEnd` to `responseHeadersStart`; iOS: `requestEndDate` to `responseStartDate`).
 *   Never the time from the request's start. Only with a [requestBodyMs].
 * - [requestBodyPartialMs]: Android / desktop only, when the request ended (failed, timed out, was
 *   cancelled) while its body was still being sent: `requestBodyStart` to that end. Only without a
 *   [requestBodyMs].
 * - [edgePop]: the CloudFront edge location that answered, from `X-Amz-Cf-Pop`.
 * - [failureKind] / [transportError]: set when the request got no HTTP answer: the transport's
 *   classification, and a bounded token for the platform error ([raftTransportErrorToken]).
 * - [sends]: how many times the transport sent the body for this one call (2 after the multipart
 *   connection-reset retry); the times describe the last send.
 */
@RaftCoreInternalApi data class RaftUploadWireFacts(
    val requestBytes: Long? = null,
    val connectMs: Long? = null,
    val requestBodyMs: Long? = null,
    val responseWaitMs: Long? = null,
    val requestBodyPartialMs: Long? = null,
    val edgePop: String? = null,
    val failureKind: RaftTransportFailureKind? = null,
    val transportError: String? = null,
    val sends: Int = 1,
)

/** The engine's fact tokens ([takeRaftKtorTransportFacts]) the upload times travel in. */
@RaftCoreInternalApi const val RAFT_FACT_CONNECT_SETUP_MS = "connectSetupMs"
@RaftCoreInternalApi const val RAFT_FACT_REQUEST_BODY_MS = "requestBodyMs"
@RaftCoreInternalApi const val RAFT_FACT_RESPONSE_WAIT_MS = "responseWaitMs"
@RaftCoreInternalApi const val RAFT_FACT_REQUEST_BODY_PARTIAL_MS = "requestBodyPartialMs"

/**
 * Reads the upload times out of the engine's `key=value` facts. A missing, malformed or negative
 * token is "not measured"; a response wait without a body time is dropped (there is no last byte to
 * measure it from), and a partial body time is kept only when the body never finished.
 */
@RaftCoreInternalApi fun raftUploadWireFacts(
    requestBytes: Long?,
    facts: String?,
    edgePop: String?,
    failureKind: RaftTransportFailureKind? = null,
    transportError: String? = null,
): RaftUploadWireFacts {
    val requestBodyMs = facts.raftFactMillisOrNull(RAFT_FACT_REQUEST_BODY_MS)
    return RaftUploadWireFacts(
        requestBytes = requestBytes?.takeIf { it > 0L },
        connectMs = facts.raftFactMillisOrNull(RAFT_FACT_CONNECT_SETUP_MS),
        requestBodyMs = requestBodyMs,
        responseWaitMs = if (requestBodyMs == null) null else facts.raftFactMillisOrNull(RAFT_FACT_RESPONSE_WAIT_MS),
        requestBodyPartialMs = if (requestBodyMs != null) null else facts.raftFactMillisOrNull(RAFT_FACT_REQUEST_BODY_PARTIAL_MS),
        edgePop = edgePop,
        failureKind = failureKind,
        transportError = transportError,
    )
}

private fun String?.raftFactMillisOrNull(key: String): Long? {
    if (this == null) return null
    val prefix = "$key="
    val token = split(' ').lastOrNull { it.startsWith(prefix) } ?: return null
    return token.substring(prefix.length).toLongOrNull()?.takeIf { it >= 0L }
}

private const val RAFT_EDGE_POP_HEADER = "x-amz-cf-pop"

/** The shape the web keeps (`edgePopOf`, botiverse/slock `observability/resourceTiming.ts`): `NRT57-P2`. */
private val RaftEdgePopPattern = Regex("""^[A-Z]{3}\d{1,3}(?:-[A-Z]\d{1,2})?$""")

/**
 * The CloudFront edge location that answered (`X-Amz-Cf-Pop`), as the web's `edge_pop`. Only a value
 * of that shape is kept; absent when the answer did not pass CloudFront.
 */
@RaftCoreInternalApi fun raftEdgePopOrNull(headers: Map<String, List<String>>): String? =
    headers.entries
        .firstOrNull { (name, _) -> name.equals(RAFT_EDGE_POP_HEADER, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { RaftEdgePopPattern.matches(it) }

@RaftCoreInternalApi const val RAFT_TRANSPORT_ERROR_OTHER = "other"

private val RaftKtorTimeoutNames = setOf("HttpRequestTimeoutException", "ConnectTimeoutException", "SocketTimeoutException")

/**
 * A bounded name for a transport failure, for telemetry: the platform's own
 * ([raftPlatformTransportErrorToken]: `okhttp:<exception class>` from a fixed list, or
 * `<NSError domain>:<code>` from a fixed list of domains), else `ktor:<timeout class>`, else `other`.
 * Never the error's message: it can hold a URL or a file path.
 */
@RaftCoreInternalApi fun raftTransportErrorToken(error: Throwable): String {
    raftPlatformTransportErrorToken(error)?.let { return it }
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 6) {
        val name = current::class.simpleName.orEmpty()
        if (name in RaftKtorTimeoutNames) return "ktor:$name"
        current = current.cause.takeIf { it !== current }
        depth += 1
    }
    return RAFT_TRANSPORT_ERROR_OTHER
}

/** The platform's bounded error name, or null when the throwable is not one this platform lists. */
@RaftCoreInternalApi expect fun raftPlatformTransportErrorToken(error: Throwable): String?
