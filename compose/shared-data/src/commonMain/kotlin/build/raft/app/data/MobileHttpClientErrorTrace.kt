package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.newRaftTraceIds
import build.raft.app.network.raftIsTelemetrySelfApiRoute
import build.raft.app.network.raftMobileApiRouteTemplate
import build.raft.app.network.raftValidTraceIdOrNull
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mobile HTTP client failure telemetry (`#proj-mobile:#399`, `botiverse/slock#16971`):
 * emits `mobile.http.client` spans (`otel.traces`) for failed requests (`status == 0` or `>= 400`),
 * excluding caller cancellations, presigned storage URLs, and self-reporting `/api/web-traces` /
 * scope-attestation calls.
 *
 * Reuses the request's `traceparent` 32-hex trace id (with a fresh 16-hex span id) so client
 * failures join the server span when the request reached Raft.
 */
@RaftCoreInternalApi const val MOBILE_HTTP_CLIENT_SPAN_NAME: String = "mobile.http.client"
@RaftCoreInternalApi const val MOBILE_ERROR_THROTTLE_WINDOW_MS: Long = 60_000L
@RaftCoreInternalApi const val MOBILE_ERROR_THROTTLE_MAX_KEYS: Int = 256

/** Closed-set `error_code` values for `mobile.http.client` (`status_code == 0` or `edge_blocked`). */
@RaftCoreInternalApi enum class MobileHttpErrorCode(val wireValue: String) {
    Dns("dns"),
    Connect("connect"),
    Timeout("timeout"),
    Tls("tls"),
    Reset("reset"),
    Offline("offline"),
    EdgeBlocked("edge_blocked"),
    Other("other"),
}

@RaftCoreInternalApi enum class MobileHttpStatusBucket(val wireValue: String) {
    Client4xx("4xx"),
    Server5xx("5xx"),
    NetworkError("network_error"),
}

@RaftCoreInternalApi data class MobileHttpFailureClassification(
    val statusBucket: MobileHttpStatusBucket,
    val statusCode: Int,
    val errorCode: MobileHttpErrorCode?,
)

/**
 * Classifies an HTTP outcome for `mobile.http.client`. Returns `null` for 2xx/3xx responses or
 * caller/lifecycle cancellations. Edge-blocked responses (e.g. Cloudflare 403/503 HTML) use
 * `status_bucket = "network_error"`, `status_code = code`, `error_code = "edge_blocked"` so they
 * are not miscounted as Raft server errors (`botiverse/slock#16971`).
 */
@RaftCoreInternalApi fun classifyMobileHttpFailure(
    statusCode: Int,
    errorBodyText: String?,
    transportDiagnostics: String?,
): MobileHttpFailureClassification? {
    if (statusCode in 200..399) return null
    val diagLower = transportDiagnostics?.lowercase().orEmpty()
    val bodyLower = errorBodyText?.lowercase().orEmpty()
    if (
        "kind=cancelled" in diagLower ||
        "detail=caller_cancelled" in diagLower ||
        "detail=lifecycle_retired" in diagLower ||
        "detail=mutation_outcome_unknown" in diagLower ||
        bodyLower.startsWith("network_request_lifecycle_retired") ||
        bodyLower.startsWith("network_request_mutation_outcome_unknown")
    ) {
        return null
    }
    if ("\"edge_blocked\"" in bodyLower || bodyLower == "edge_blocked") {
        return MobileHttpFailureClassification(
            statusBucket = MobileHttpStatusBucket.NetworkError,
            statusCode = statusCode.coerceAtLeast(0),
            errorCode = MobileHttpErrorCode.EdgeBlocked,
        )
    }
    if (statusCode <= 0) {
        val errorCode = classifyNetworkTransportErrorCode(diagLower, bodyLower)
        return MobileHttpFailureClassification(
            statusBucket = MobileHttpStatusBucket.NetworkError,
            statusCode = 0,
            errorCode = errorCode,
        )
    }
    return when {
        statusCode in 400..499 ->
            MobileHttpFailureClassification(
                statusBucket = MobileHttpStatusBucket.Client4xx,
                statusCode = statusCode,
                errorCode = null,
            )
        statusCode >= 500 ->
            MobileHttpFailureClassification(
                statusBucket = MobileHttpStatusBucket.Server5xx,
                statusCode = statusCode,
                errorCode = null,
            )
        else -> null
    }
}

private fun classifyNetworkTransportErrorCode(diagLower: String, bodyLower: String): MobileHttpErrorCode {
    val combined = "$diagLower $bodyLower"
    return when {
        "offline" in combined ||
            "not_connected_to_internet" in combined ||
            "network_unreachable" in combined ||
            "no_internet" in combined ||
            "nsurlerrornotconnectedtointernet" in combined -> MobileHttpErrorCode.Offline
        "kind=timeout" in diagLower ||
            "network_timeout" in bodyLower ||
            "timed_out" in combined ||
            "timeout" in combined -> MobileHttpErrorCode.Timeout
        "kind=dns" in diagLower ||
            "network_dns" in bodyLower ||
            "unknownhost" in combined ||
            "cannotfindhost" in combined ||
            "dns" in combined -> MobileHttpErrorCode.Dns
        "kind=tls" in diagLower ||
            "network_tls" in bodyLower ||
            "ssl" in combined ||
            "tls" in combined ||
            "certificate" in combined -> MobileHttpErrorCode.Tls
        "connection_reset" in combined ||
            "stream_reset" in combined ||
            "rst_stream" in combined ||
            "broken_pipe" in combined ||
            "eofexception" in combined ||
            "econnreset" in combined ||
            "connection_lost" in combined ||
            "networkconnectionlost" in combined -> MobileHttpErrorCode.Reset
        "kind=connect" in diagLower ||
            "network_connect" in bodyLower ||
            "connectexception" in combined ||
            "cannotconnecttohost" in combined -> MobileHttpErrorCode.Connect
        else -> MobileHttpErrorCode.Other
    }
}

/**
 * Bounded rolling per-signature throttle (`60 s` default): the first event for a key passes with
 * `suppressedCount = 0`; duplicates within [windowMs] increment the counter and return `null`; the
 * next event after [windowMs] passes carrying the accumulated `suppressedCount` (no separate
 * background window-close flush). Thread-safe.
 */
@RaftCoreInternalApi class MobileSignatureThrottle(
    private val windowMs: Long = MOBILE_ERROR_THROTTLE_WINDOW_MS,
    private val maxKeys: Int = MOBILE_ERROR_THROTTLE_MAX_KEYS,
) {
    private data class Bucket(var lastEmittedMs: Long, var suppressed: Int)

    private val lock = SynchronizedObject()
    private val buckets = LinkedHashMap<String, Bucket>()

    /** Returns `suppressedCount >= 0` when this occurrence should emit, or `null` when throttled. */
    fun tryAcquire(key: String, nowMonotonicMs: Long): Int? =
        synchronized(lock) {
            val existing = buckets.remove(key)
            if (existing == null) {
                if (buckets.size >= maxKeys) {
                    val oldest = buckets.keys.firstOrNull()
                    if (oldest != null) buckets.remove(oldest)
                }
                buckets[key] = Bucket(lastEmittedMs = nowMonotonicMs, suppressed = 0)
                0
            } else if (nowMonotonicMs - existing.lastEmittedMs < windowMs) {
                existing.suppressed = (existing.suppressed + 1).coerceAtMost(1_000_000)
                buckets[key] = existing
                null
            } else {
                val carried = existing.suppressed
                existing.lastEmittedMs = nowMonotonicMs
                existing.suppressed = 0
                buckets[key] = existing
                carried
            }
        }

    fun reset() {
        synchronized(lock) { buckets.clear() }
    }
}

private object MobileHttpTraceMonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}

/**
 * Builds and emits `mobile.http.client` spans for failed HTTP calls. Injectable collaborators allow
 * pure unit testing without a live Ktor engine or `RaftDataStoreRegistry`.
 */
@RaftCoreInternalApi class MobileHttpClientErrorTraceRecorder(
    private val nowEpochMs: () -> Long = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
    private val nowMonotonicMs: () -> Long = MobileHttpTraceMonotonicClock::nowMs,
    private val resolveServerId: (Map<String, String>) -> String? = ::resolveActiveHttpErrorTraceServerId,
    private val resolveSampleRate: (String) -> Double = { serverId ->
        resolveActiveServerTraceSampleRate(
            serverId = serverId,
            traceName = MOBILE_HTTP_ERROR_TRACE_NAME,
            fallback = TRACE_SAMPLE_ERROR_DEFAULT_RATE,
        )
    },
    private val random: () -> Double = { Random.nextDouble() },
    private val newSpanIds: () -> Pair<String, String> = { newRaftTraceIds().let { it.traceId to it.spanId } },
    private val throttle: MobileSignatureThrottle = MobileSignatureThrottle(),
    private val emitSpan: (serverId: String, span: RaftWebSpanRecord) -> Unit = { serverId, span ->
        RaftClientTraceEvents.emitSpan(serverId, span, emptyList())
    },
) {
    /** Records one completed HTTP request when it represents a non-cancelled failure. Returns true if emitted. */
    fun recordFailure(
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        sentTraceId: String?,
        statusCode: Int,
        errorBodyText: String?,
        transportDiagnostics: String?,
        durationMs: Long,
        redactSensitive: Boolean,
    ): Boolean {
        if (redactSensitive) return false
        val classification = classifyMobileHttpFailure(statusCode, errorBodyText, transportDiagnostics) ?: return false
        val route = raftMobileApiRouteTemplate(url)
        if (raftIsTelemetrySelfApiRoute(route)) return false
        val serverId = resolveServerId(requestHeaders)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val rate = resolveSampleRate(serverId)
        if (!rate.isFinite() || rate <= 0.0) return false
        val clampedRate = rate.coerceAtMost(1.0)
        if (!traceSampled(clampedRate, random)) return false

        val throttleKey = "$route|${classification.statusBucket.wireValue}|${classification.errorCode?.wireValue.orEmpty()}"
        val suppressedCount = throttle.tryAcquire(throttleKey, nowMonotonicMs()) ?: return false

        val (fallbackTraceId, spanId) = newSpanIds()
        val traceId = raftValidTraceIdOrNull(sentTraceId) ?: fallbackTraceId
        val endEpochMs = nowEpochMs()
        val startEpochMs = (endEpochMs - durationMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        val normalizedMethod = method.trim().uppercase().takeIf { it.isNotEmpty() } ?: "UNKNOWN"

        val attrs =
            buildMap<String, JsonPrimitive> {
                put("method", JsonPrimitive(normalizedMethod))
                put("http.route", JsonPrimitive(route))
                put("status_bucket", JsonPrimitive(classification.statusBucket.wireValue))
                put("status_code", JsonPrimitive(classification.statusCode))
                classification.errorCode?.let { put("error_code", JsonPrimitive(it.wireValue)) }
                put("suppressed_count", JsonPrimitive(suppressedCount))
                put("sample_rate", JsonPrimitive(clampedRate))
            }
        val span =
            RaftWebSpanRecord(
                traceId = traceId,
                spanId = spanId,
                name = MOBILE_HTTP_CLIENT_SPAN_NAME,
                status = "error",
                startTime = raftEpochMillisUtcIso(startEpochMs),
                endTime = raftEpochMillisUtcIso(maxOf(startEpochMs, endEpochMs)),
                attrs = JsonObject(attrs),
            )
        return runCatching {
            emitSpan(serverId, span)
            true
        }.getOrDefault(false)
    }
}

@RaftCoreInternalApi object MobileHttpClientErrorTrace {
    private val defaultRecorder = MobileHttpClientErrorTraceRecorder()

    fun recordFailure(
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
        sentTraceId: String?,
        statusCode: Int,
        errorBodyText: String?,
        transportDiagnostics: String?,
        durationMs: Long,
        redactSensitive: Boolean,
    ) {
        runCatching {
            defaultRecorder.recordFailure(
                method = method,
                url = url,
                requestHeaders = requestHeaders,
                sentTraceId = sentTraceId,
                statusCode = statusCode,
                errorBodyText = errorBodyText,
                transportDiagnostics = transportDiagnostics,
                durationMs = durationMs,
                redactSensitive = redactSensitive,
            )
        }
    }
}

private fun resolveActiveHttpErrorTraceServerId(requestHeaders: Map<String, String>): String? {
    val headerServer =
        requestHeaders.entries
            .firstOrNull { (name, _) -> name.equals(HEADER_SERVER_ID, ignoreCase = true) }
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    if (headerServer != null) return headerServer
    return runCatching {
        RaftDataStoreRegistry.currentOrNull()
            ?.authStore
            ?.readInMemorySessionSnapshot()
            ?.session
            ?.currentServerId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
