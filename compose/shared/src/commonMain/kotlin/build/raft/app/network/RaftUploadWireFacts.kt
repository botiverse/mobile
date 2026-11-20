package build.raft.app.network

/**
 * What the transport measured for one upload request (the multipart POST to the API, or the presigned
 * PUT to the object store), for `slock.attachment.upload_finished`. A value the platform could not
 * measure is null, never 0.
 *
 * - [requestBytes]: the length of the request body as declared to the engine (multipart framing
 *   included), a number only.
 * - [requestBodyMs]: the time spent sending the request. Android / desktop (OkHttp `EventListener`):
 *   `requestBodyStart` to `requestBodyEnd`. iOS (`NSURLSessionTaskTransactionMetrics`):
 *   `requestStartDate` to `requestEndDate`, which also covers the request headers.
 * - [responseWaitMs]: from the last body byte sent to the first byte of the response (Android:
 *   `requestBodyEnd` to `responseHeadersStart`; iOS: `requestEndDate` to `responseStartDate`).
 *   Never the time from the request's start. Only with a [requestBodyMs].
 * - [edgePop]: the CloudFront edge location that answered, from `X-Amz-Cf-Pop`.
 */
internal data class RaftUploadWireFacts(
    val requestBytes: Long? = null,
    val requestBodyMs: Long? = null,
    val responseWaitMs: Long? = null,
    val edgePop: String? = null,
)

/** The engine's fact tokens ([takeRaftKtorTransportFacts]) the two phase times travel in. */
internal const val RAFT_FACT_REQUEST_BODY_MS = "requestBodyMs"
internal const val RAFT_FACT_RESPONSE_WAIT_MS = "responseWaitMs"

/**
 * Reads the phase times out of the engine's `key=value` facts. A missing, malformed or negative
 * token is "not measured"; a response wait without a body time is dropped (there is no last byte to
 * measure it from).
 */
internal fun raftUploadWireFacts(requestBytes: Long?, facts: String?, edgePop: String?): RaftUploadWireFacts {
    val requestBodyMs = facts.raftFactMillisOrNull(RAFT_FACT_REQUEST_BODY_MS)
    return RaftUploadWireFacts(
        requestBytes = requestBytes?.takeIf { it >= 0L },
        requestBodyMs = requestBodyMs,
        responseWaitMs = if (requestBodyMs == null) null else facts.raftFactMillisOrNull(RAFT_FACT_RESPONSE_WAIT_MS),
        edgePop = edgePop,
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
internal fun raftEdgePopOrNull(headers: Map<String, List<String>>): String? =
    headers.entries
        .firstOrNull { (name, _) -> name.equals(RAFT_EDGE_POP_HEADER, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { RaftEdgePopPattern.matches(it) }
