package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.RaftAppIdentityRegistry
import kotlin.time.Instant
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Client trace events through the same receiver the web uses (botiverse/slock
 * packages/web/src/utils/webAuthTrace.ts `emitWebEvent` / `flushAuthTraces`): a
 * `web-trace-batch:create` scope attestation from `POST /api/servers/:id/scope-attestation`, then
 * `POST <trace-upload worker>/api/web-traces` with `{ attestation, records: [], events }`
 * (packages/trace-upload-worker/src/traces/web.ts). The worker accepts only `surface: "web"`
 * records, so mobile events carry that surface and tell themselves apart by `clientPlatform`
 * (`android` / `ios`, the `X-Raft-Client-Platform` value), the attribute web's
 * `withDefaultWebAttrs` adds to every event, with `appVersion` beside it.
 *
 * Batched [RaftClientTraceFlushDelayMillis] (15 s) after the first event, at most
 * [RaftClientTraceMaxBatch] per request, each batch the records of one server recorded under one
 * account. When enqueueing brings a single `(serverId, account)` group in the queue to
 * [RaftClientTraceMaxBatch] while in the foreground with privacy mode off and no retry waiting,
 * the pending flush is brought forward immediately (0 ms delay) so bursts flush cleanly at the
 * batch cap without dropping. Nothing here throws. What a failed flush does with its batch:
 *
 * - No HTTP answer (status 0 from [RaftNetworkClient]: connect, DNS, reset, timeout, a closed
 *   reachability gate) or a 5xx, from the attestation request or the trace POST
 *   ([raftClientTraceShouldResend]): the batch goes back to the front of the queue in its original
 *   order, records unchanged (the same `time`, `trace_id` and `span_id` as the first attempt). Its
 *   server's records then wait for the retry while the same flush goes on with the other servers'
 *   records, so one failing server does not hold up the rest. The retry comes
 *   [RaftClientTraceRetryDelaysMillis] after a flush that put a batch back: 4 s, 15 s, 60 s, then
 *   every 5 min while each flush puts one back, and back to the start after a flush where a batch got
 *   a 2xx or a refusal and none went back. A network-restored signal ([RaftFreshnessSignal]) replaces
 *   a longer waiting retry with one [RaftClientTraceFlushDelayMillis] from then, once per wait,
 *   while in the foreground.
 * - Any other answer (4xx, including a refused attestation, or a 2xx attestation without a token),
 *   or an exception while building the request (a record that does not encode): the batch is
 *   dropped, as the web does, and the flush goes on with the next; sending it again cannot help. An
 *   exception is not an answer: the wait before the next retry stays as it was.
 * - While a retry is waiting, new events (also at the cap) still wait for it.
 * - Foreground gate ([RaftProcessForegroundGate]): when the process enters the background, any
 *   pending flush or retry timer is cancelled immediately so nothing fires or wakes the network
 *   while backgrounded (an already in-flight request settles cleanly and stops before the next
 *   batch). While backgrounded, enqueueing, network-restored signals, and privacy-mode transitions
 *   never schedule a flush; records remain queued in memory (capped at [RaftClientTraceMaxQueued]).
 *   Returning to the foreground schedules a flush when privacy mode is off and the queue is
 *   non-empty (respecting any pending retry delay, otherwise flushing immediately if a server
 *   batch is full or after [RaftClientTraceFlushDelayMillis]).
 * - Bounds: the queue never holds more than [RaftClientTraceMaxQueued]; while sends are failing a
 *   full queue drops its oldest records to take newer ones (otherwise, as before, the new unit is
 *   dropped). A record still unsent [RaftClientTraceGiveUpMillis] after the first failed flush it
 *   waited through is dropped at the next failure. Both kinds of loss are logged as counts, and the
 *   next batch carries one [RaftClientTraceDroppedEventName] event per reason (`dropped_count`,
 *   `reason` `aged_out` / `queue_cap`, `oldest_dropped_age_s`). That event is queued like any other
 *   (resent unchanged with its batch); a count is forgotten only when its batch was accepted, and
 *   losing the event itself puts its count back, unless the account changed or privacy mode went on
 *   since its batch was taken. Sign-out, another account and privacy mode "on" forget the counts not
 *   yet reported. Drops under privacy mode, a refusal or an account change are not counted.
 * - Account: each record carries the account signed in when it was queued
 *   ([RaftClientTraceEventSender.setAccount]). An event queued while no account is known (signed
 *   out, or before the first sign-in) is dropped: it belongs to no account it could be sent under.
 *   Before each batch, and again after a new attestation, the transport says which account its
 *   requests authenticate as ([RaftClientTraceTransport.currentAccount], the stored session); a
 *   batch recorded under another account is dropped (logged `account_changed`, not counted), and an
 *   attestation is used only for the account and server it was obtained for. So a record never
 *   leaves under another account's attestation, also between a login that replaces the session and
 *   the app shell reporting the new account. That report ([RaftClientTraceEventSender.setAccount],
 *   the fast path) drops everything held and the cached attestation at once, as sign-out does. A
 *   server switch within one account keeps them.
 *
 * `RAFT_PRIVACY_MODE` ([RaftTelemetryPrivacy], web `webTelemetryPrivacyHold.ts`): until the
 * environment's client config answers, events are held in memory (up to [RaftClientTraceMaxQueued])
 * and nothing is sent, not even the attestation request; "on" drops what was held and every later
 * event; "off" sends what was held, then everything is as before.
 */
@RaftCoreInternalApi const val RaftClientTraceFlushDelayMillis = 15_000L
@RaftCoreInternalApi const val RaftClientTraceMaxBatch = 100
@RaftCoreInternalApi const val RaftClientTraceMaxQueued = 500

/** Wait before the flush after the 1st, 2nd, 3rd, and every later consecutive failed one. */
@RaftCoreInternalApi val RaftClientTraceRetryDelaysMillis = listOf(4_000L, 15_000L, 60_000L, 300_000L)

/** A record is given up this long after the first failed flush it waited through. */
@RaftCoreInternalApi const val RaftClientTraceGiveUpMillis = 30 * 60_000L

/**
 * How many records were given up, by reason; carried by the next batch. The receiver stores it once
 * the name is registered there and ignores it until then.
 */
@RaftCoreInternalApi const val RaftClientTraceDroppedEventName = "slock.client_trace.dropped"
@RaftCoreInternalApi const val RaftClientTraceDroppedCountAttr = "dropped_count"
@RaftCoreInternalApi const val RaftClientTraceDroppedReasonAttr = "reason"
@RaftCoreInternalApi const val RaftClientTraceDroppedOldestAgeAttr = "oldest_dropped_age_s"

/** Why a record was given up (`reason` of [RaftClientTraceDroppedEventName]). */
@RaftCoreInternalApi enum class RaftClientTraceDropReason(val wireName: String) {
    /** Still unsent [RaftClientTraceGiveUpMillis] after its first failed flush. */
    AgedOut("aged_out"),

    /** Pushed out by [RaftClientTraceMaxQueued]. */
    QueueCap("queue_cap"),
}

/**
 * Whether a batch whose request ended with [status] is kept and sent again: 0 (the shared network
 * client's "no HTTP response") or a 5xx. Any other non-2xx answer is a refusal.
 */
@RaftCoreInternalApi fun raftClientTraceShouldResend(status: Int): Boolean = status == 0 || status in 500..599
@RaftCoreInternalApi const val RaftWebTraceScope = "web-trace-batch:create"
@RaftCoreInternalApi const val RaftStateTransitionTraceName = "slock.state.transition"

/** Voice dictation caller-context trace keys dropped before export (`slock` `#wg-call:#9`). */
@RaftCoreInternalApi
val RaftClientTraceVoiceDictationDropKeys: Set<String> =
    setOf(
        "draft_before",
        "draftBefore",
        "contact_hints",
        "contactHints",
    )

/** Content-safety trace attribute keys dropped before export (`slock` `TRACE_CONTENT_SAFETY_DROP_FIELD_KEYS`). */
@RaftCoreInternalApi
val RaftClientTraceContentSafetyDropKeys: Set<String> =
    setOf("raw_payload", "token") + RaftClientTraceVoiceDictationDropKeys

private val RaftClientTraceContentSafetyDropKeysLower: Set<String> =
    RaftClientTraceContentSafetyDropKeys.mapTo(hashSetOf()) { it.lowercase() }

private fun isDroppedRaftClientTraceKey(key: String): Boolean {
    val lower = key.lowercase()
    if (lower in RaftClientTraceContentSafetyDropKeysLower) return true
    val dot = lower.lastIndexOf('.')
    return dot >= 0 && lower.substring(dot + 1) in RaftClientTraceContentSafetyDropKeysLower
}

private fun sanitizeRaftClientTraceElement(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> sanitizeRaftClientTraceAttrs(element)
        is JsonArray -> {
            var changed = false
            val next = ArrayList<JsonElement>(element.size)
            for (item in element) {
                val sanitized = sanitizeRaftClientTraceElement(item)
                if (sanitized !== item) changed = true
                next += sanitized
            }
            if (changed) JsonArray(next) else element
        }
        else -> element
    }

/**
 * Strips every content-safety drop attribute (`raw_payload`, `token`, `draft_before`, `draftBefore`,
 * `contact_hints`, `contactHints`) from trace attributes, including dotted leaf keys and nested
 * objects/arrays. Mirrors `slock` `sanitizeTraceContentSafetyAttrs`.
 */
@RaftCoreInternalApi
fun sanitizeRaftClientTraceAttrs(attrs: JsonObject): JsonObject {
    var changed = false
    val out = LinkedHashMap<String, JsonElement>(attrs.size)
    for ((key, value) in attrs) {
        if (isDroppedRaftClientTraceKey(key)) {
            changed = true
            continue
        }
        val sanitized = sanitizeRaftClientTraceElement(value)
        if (sanitized !== value) changed = true
        out[key] = sanitized
    }
    return if (changed) JsonObject(out) else attrs
}

/** Maximum absolute client-vs-server clock offset accepted for push delivery calibration (±24h). */
@RaftCoreInternalApi const val RaftPushClockOffsetMaxAbsMillis = 86_400_000L

@RaftCoreInternalApi const val RaftPushClockOffsetAttr = "clock_offset_ms"
@RaftCoreInternalApi const val RaftPushClockRttAttr = "clock_rtt_ms"
@RaftCoreInternalApi const val RaftPushClockSourceAttr = "clock_source"

/** Closed source enum for push delivery clock calibration (`#proj-mobile:#405`). */
@RaftCoreInternalApi enum class RaftPushClockSource(val wireName: String) {
    /** Parsed from `POST /api/servers/:id/scope-attestation` JSON field `serverTime`. */
    ServerTime("server_time"),

    /** Fallback parsed from the HTTP `Date` response header (RFC 7231 IMF-fixdate). */
    HttpDate("http_date"),
}

/**
 * Calibrated device-vs-server clock offset from a `scope-attestation` round trip (`#proj-mobile:#405`).
 *
 * [offsetMs] is `client_mid_ms - server_ms`, so server-side queries recover server time via
 * `occurred_at_ms - clock_offset_ms`.
 */
@RaftCoreInternalApi data class RaftClientClockCalibration(
    val offsetMs: Long,
    val rttMs: Long,
    val source: RaftPushClockSource,
)

private val HttpImfFixdateRegex =
    Regex("""^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\d{2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$""")

private val RaftPushDeliveryClockEventNames: Set<String> =
    setOf(
        RAFT_PUSH_DELIVERY_EVENT_RECEIVED,
        RAFT_PUSH_DELIVERY_EVENT_PRESENTED,
        RAFT_PUSH_DELIVERY_EVENT_OPENED,
    )

/**
 * Pure multiplatform RFC 7231 IMF-fixdate parser (e.g. `"Sat, 28 Nov 2026 07:51:56 GMT"`) returning
 * UTC epoch milliseconds, or `null` when malformed, non-GMT, pre-1970, or out of calendar bounds.
 */
@RaftCoreInternalApi fun raftParseHttpDateEpochMillis(raw: String): Long? {
    val match = HttpImfFixdateRegex.matchEntire(raw.trim()) ?: return null
    val day = match.groupValues[2].toIntOrNull() ?: return null
    val month =
        when (match.groupValues[3]) {
            "Jan" -> 1
            "Feb" -> 2
            "Mar" -> 3
            "Apr" -> 4
            "May" -> 5
            "Jun" -> 6
            "Jul" -> 7
            "Aug" -> 8
            "Sep" -> 9
            "Oct" -> 10
            "Nov" -> 11
            "Dec" -> 12
            else -> return null
        }
    val year = match.groupValues[4].toIntOrNull() ?: return null
    if (year < 1970) return null
    val hour = match.groupValues[5].toIntOrNull() ?: return null
    val minute = match.groupValues[6].toIntOrNull() ?: return null
    val second = match.groupValues[7].toIntOrNull() ?: return null
    val isLeap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val maxDay =
        when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeap) 29 else 28
            else -> return null
        }
    if (day !in 1..maxDay || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    val epochDays = raftEpochDayForDate(year, month, day)
    return epochDays * 86_400_000L +
        hour * 3_600_000L +
        minute * 60_000L +
        second * 1_000L
}

/**
 * Computes push delivery clock calibration from a `scope-attestation` round trip (`#proj-mobile:#405`).
 *
 * Returns `null` (so callers omit all three clock attributes rather than clamping) when:
 * - [t1Millis] < [t0Millis] (device wall clock stepped backwards during the request),
 * - neither [serverTimeIso] nor [httpDateHeader] parses cleanly, or
 * - the resulting `offsetMs = (t0Millis + rttMs / 2) - serverMs` lies outside
 *   `±[RaftPushClockOffsetMaxAbsMillis]` (±24h).
 */
@RaftCoreInternalApi fun raftComputePushClockCalibration(
    t0Millis: Long,
    t1Millis: Long,
    serverTimeIso: String?,
    httpDateHeader: String?,
): RaftClientClockCalibration? {
    if (t1Millis < t0Millis) return null
    val rttMs = t1Millis - t0Millis
    if (rttMs < 0L) return null
    val serverTimeMs =
        serverTimeIso
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }
    val (serverMs, source) =
        if (serverTimeMs != null) {
            serverTimeMs to RaftPushClockSource.ServerTime
        } else {
            val httpDateMs =
                httpDateHeader
                    ?.let(::raftParseHttpDateEpochMillis)
                    ?: return null
            httpDateMs to RaftPushClockSource.HttpDate
        }
    val clientMidMs = t0Millis + (rttMs / 2L)
    val offsetMs = clientMidMs - serverMs
    if (((clientMidMs xor serverMs) and (clientMidMs xor offsetMs)) < 0L) return null
    if (offsetMs !in -RaftPushClockOffsetMaxAbsMillis..RaftPushClockOffsetMaxAbsMillis) {
        return null
    }
    return RaftClientClockCalibration(
        offsetMs = offsetMs,
        rttMs = rttMs,
        source = source,
    )
}

private fun RaftWebEventRecord.withPushClockCalibration(
    calibration: RaftClientClockCalibration?,
): RaftWebEventRecord {
    if (calibration == null || name !in RaftPushDeliveryClockEventNames) return this
    if (
        RaftPushClockOffsetAttr in attrs &&
        RaftPushClockRttAttr in attrs &&
        RaftPushClockSourceAttr in attrs
    ) {
        return this
    }
    val enriched = LinkedHashMap<String, JsonElement>(attrs.size + 3)
    enriched.putAll(attrs)
    if (RaftPushClockOffsetAttr !in enriched) {
        enriched[RaftPushClockOffsetAttr] = JsonPrimitive(calibration.offsetMs)
    }
    if (RaftPushClockRttAttr !in enriched) {
        enriched[RaftPushClockRttAttr] = JsonPrimitive(calibration.rttMs)
    }
    if (RaftPushClockSourceAttr !in enriched) {
        enriched[RaftPushClockSourceAttr] = JsonPrimitive(calibration.source.wireName)
    }
    return copy(attrs = JsonObject(enriched))
}

/** The web reuses an attestation until this long before it expires (ATTESTATION_REUSE_MARGIN_MS). */
private const val AttestationReuseMarginMillis = 60_000L

private val clientTraceJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

@Serializable
@RaftCoreInternalApi data class RaftScopeAttestationRequest(val scope: String)

@Serializable
@RaftCoreInternalApi data class RaftScopeAttestationResponse(
    val attestation: String? = null,
    val expiresAt: String? = null,
    val serverTime: String? = null,
)

/** Web `WebEventRecord`; inside a span it carries that span's [traceId] / [spanId]. */
@Serializable
@RaftCoreInternalApi data class RaftWebEventRecord(
    val type: String = "event",
    @kotlinx.serialization.SerialName("schema_version") val schemaVersion: Int = 1,
    val name: String,
    val surface: String = "web",
    val time: String,
    @kotlinx.serialization.SerialName("trace_id") val traceId: String? = null,
    @kotlinx.serialization.SerialName("span_id") val spanId: String? = null,
    val attrs: JsonObject,
)

/**
 * Web `WebTraceRecord` (webAuthTrace.ts; worker `isLocalTraceRecord`): a span with a real duration.
 * `kind` `internal` and `status` `ok` / `error`, as the web's spans.
 */
@Serializable
@RaftCoreInternalApi data class RaftWebSpanRecord(
    val type: String = "span",
    @kotlinx.serialization.SerialName("schema_version") val schemaVersion: Int = 1,
    @kotlinx.serialization.SerialName("trace_id") val traceId: String,
    @kotlinx.serialization.SerialName("span_id") val spanId: String,
    val name: String,
    val surface: String = "web",
    val kind: String = "internal",
    val status: String = "ok",
    @kotlinx.serialization.SerialName("start_time") val startTime: String,
    @kotlinx.serialization.SerialName("end_time") val endTime: String,
    val attrs: JsonObject,
)

/** `POST /api/web-traces` body: spans in `records`, point facts in `events` (either may be empty). */
@Serializable
@RaftCoreInternalApi data class RaftWebTraceBatchRequest(
    val attestation: String,
    val records: List<RaftWebSpanRecord> = emptyList(),
    val events: List<RaftWebEventRecord>,
)

/**
 * The attestation request's HTTP [status] (0 without a response), for a readable 2xx its body,
 * and any HTTP `Date` response header for push clock-offset fallback calibration.
 */
@RaftCoreInternalApi class RaftScopeAttestationAnswer(
    val status: Int,
    val response: RaftScopeAttestationResponse? = null,
    val httpDateHeader: String? = null,
)

@RaftCoreInternalApi interface RaftClientTraceTransport {
    /**
     * The account [attest] authenticates as: the user id of the stored session, null when signed
     * out. Read locally, never a request.
     */
    suspend fun currentAccount(): String?

    /**
     * The trace-upload receiver base URL for the active environment, or `null` when this
     * environment has no receiver and uploads must be skipped cleanly (no attestation request and
     * no POST). Defaults to [RAFT_DEFAULT_TRACE_UPLOAD_URL] so test transports that do not override
     * it keep sending.
     */
    fun currentTraceUploadBaseUrl(): String? = RAFT_DEFAULT_TRACE_UPLOAD_URL

    /** Asks for a `web-trace-batch:create` attestation for [serverId]. */
    suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer

    /** Posts the batch; returns the HTTP status (0 without a response). */
    suspend fun send(batch: RaftWebTraceBatchRequest): Int
}

@RaftCoreInternalApi class RaftNetworkClientTraceTransport(
    private val networkClient: RaftNetworkClient,
    /** The user id of the session [networkClient] authenticates with. */
    private val sessionAccount: suspend () -> String?,
    /** Explicit receiver override for tests; when null, derived dynamically from [apiBaseUrlProvider]. */
    private val traceBaseUrl: String? = null,
    /** Dynamic API base URL reader so runtime environment switches update the receiver immediately. */
    private val apiBaseUrlProvider: () -> String = { networkClient.currentApiBaseUrl() ?: RAFT_API_BASE_URL },
) : RaftClientTraceTransport {
    override suspend fun currentAccount(): String? = sessionAccount()

    override fun currentTraceUploadBaseUrl(): String? =
        if (traceBaseUrl != null) {
            traceBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }
        } else {
            defaultTraceUploadUrlForServer(apiBaseUrlProvider())
        }

    override suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer {
        val response =
            networkClient.request(
                priority = DataRequestPriority.Background,
                path = "servers/${serverId.value}/scope-attestation",
                method = "POST",
                serverIdOverride = serverId,
                bodyText = clientTraceJson.encodeToString(RaftScopeAttestationRequest(RaftWebTraceScope)),
            )
        if (!response.isSuccessful) return RaftScopeAttestationAnswer(response.code)
        val body = response.bodyText?.takeIf { it.isNotBlank() }
        val httpDateHeader =
            response.responseHeaders.entries
                .asSequence()
                .filter { (name, _) -> name.equals("date", ignoreCase = true) }
                .flatMap { (_, values) -> values.asSequence() }
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
        return RaftScopeAttestationAnswer(
            status = response.code,
            response = body?.let { runCatching { clientTraceJson.decodeFromString<RaftScopeAttestationResponse>(it) }.getOrNull() },
            httpDateHeader = httpDateHeader,
        )
    }

    override suspend fun send(batch: RaftWebTraceBatchRequest): Int {
        val baseUrl = currentTraceUploadBaseUrl() ?: return 0
        return networkClient.requestRawAbsoluteUrl(
            priority = DataRequestPriority.Background,
            url = "$baseUrl/api/web-traces",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            bodyText = clientTraceJson.encodeToString(batch),
        ).code
    }
}

@RaftCoreInternalApi class RaftClientTraceEventSender(
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long,
    /** Attributes every event carries (`clientPlatform`, `appVersion`). */
    private val defaultAttrs: () -> Map<String, String>,
    private val flushDelayMillis: Long = RaftClientTraceFlushDelayMillis,
    private val privacyMode: StateFlow<RaftPrivacyMode> = RaftTelemetryPrivacy.state,
    /** Process foreground gate: hidden, no timer or send runs; back in front, queued records flush. */
    private val foreground: StateFlow<Boolean> = RaftProcessForegroundGate.foreground,
    /** Each emission: the device's network came back; a waiting retry is brought forward. */
    networkRestored: Flow<Any> = emptyFlow(),
    /** Diagnostics lines: counts and statuses only, never a record's name or content. */
    private val log: (String) -> Unit = {},
) {
    /** What a [RaftClientTraceDroppedEventName] event carries; put back if that event is lost. */
    private class DropReport(val reason: RaftClientTraceDropReason, val count: Int, val oldestAgeMillis: Long)

    /** One queued record, an event or a span, of [serverId], queued while [account] was signed in. */
    private class Queued(
        val serverId: ServerId,
        val account: String,
        val record: RaftWebEventRecord? = null,
        val span: RaftWebSpanRecord? = null,
        val queuedAtMillis: Long = 0L,
        val report: DropReport? = null,
    ) {
        /** When the first flush this record waited through failed; null before any failure. */
        var firstFailedAtMillis: Long? = null
    }

    /** Obtained by [account]'s session for [serverId] against [traceBaseUrl]; used for that tuple only. */
    private class Attestation(
        val serverId: ServerId,
        val account: String,
        val traceBaseUrl: String,
        val value: String,
        val expiresAtMillis: Long,
        val clockCalibration: RaftClientClockCalibration? = null,
    )

    /** Records given up for one reason and not yet carried by an event. */
    private class DropTally {
        var count = 0
        var oldestAgeMillis = 0L
    }

    private class FlushWork(
        val transport: RaftClientTraceTransport,
        val serverId: ServerId,
        val account: String,
        val batch: List<Queued>,
        val accountEpoch: Int,
        val heldEpoch: Int,
    )

    private enum class SendOutcome { Sent, Refused, AccountChanged, NoReceiver, Resend }

    private class SendResult(val outcome: SendOutcome, val stage: String, val status: Int)

    /** A batch one flush put back; its server is not tried again before the retry. */
    private class PutBack(val serverId: ServerId, val stage: String, val status: Int, val kept: Int)

    /** What one flush has done so far. */
    private class FlushRound {
        val putBack = mutableListOf<PutBack>()

        /** A batch was settled by an HTTP answer: a 2xx or a refusal. */
        var answered = false
    }

    private val lock = SynchronizedObject()
    private var transport: RaftClientTraceTransport? = null
    private val queue = mutableListOf<Queued>()
    private var flushJob: Job? = null

    /** Names the one flush job that may run; a replaced job (an early retry) finds another value. */
    private var flushGeneration = 0

    /** True while [flush] is actively executing network sends (not merely waiting in `delay`). */
    private var flushInProgress = false

    /** True when the currently scheduled waiting [flushJob] already targets a 0 ms early flush. */
    private var flushImmediateScheduled = false
    private var attestation: Attestation? = null

    /** Consecutive flushes that put a batch back. */
    private var failureStreak = 0

    /**
     * A previous flush put a batch back and records still await a retry (kept across background
     * pauses so foreground return respects retry backoff rather than flushing at 0 ms / 15 s).
     */
    private var retryPending = false

    /** The flush job is waiting out a retry delay (not sending). */
    private var retryWaiting = false
    private var retryBroughtForward = false
    private var account: String? = null
    private var accountEpoch = 0

    /**
     * Moves on whenever what is held is dropped (sign-out, another account, privacy mode on): a batch
     * taken before then neither comes back nor puts its counts back.
     */
    private var heldEpoch = 0
    private val dropTallies = RaftClientTraceDropReason.entries.associateWith { DropTally() }

    init {
        scope.launch {
            privacyMode.collect { mode ->
                synchronized(lock) {
                    when (mode) {
                        RaftPrivacyMode.On -> {
                            queue.clear()
                            resetTalliesLocked()
                            heldEpoch += 1
                            retryPending = false
                            cancelPendingFlushTimerLocked()
                        }
                        RaftPrivacyMode.Off -> scheduleForActiveStateLocked()
                        RaftPrivacyMode.Unknown -> cancelPendingFlushTimerLocked()
                    }
                }
            }
        }
        scope.launch {
            foreground.collect {
                synchronized(lock) {
                    if (foreground.value) {
                        scheduleForActiveStateLocked()
                    } else {
                        cancelPendingFlushTimerLocked()
                    }
                }
            }
        }
        scope.launch {
            networkRestored.collect {
                synchronized(lock) {
                    if (
                        foreground.value &&
                        privacyMode.value == RaftPrivacyMode.Off &&
                        !flushInProgress &&
                        retryWaiting &&
                        !retryBroughtForward &&
                        queue.isNotEmpty() &&
                        retryDelayForStreakLocked() > flushDelayMillis
                    ) {
                        cancelPendingFlushTimerLocked()
                        scheduleFlushLocked(flushDelayMillis, isRetry = true, broughtForward = true)
                    }
                }
            }
        }
    }

    /** Events waiting to be sent (held while privacy mode is unknown, backgrounded, or for a retry). */
    @RaftCoreInternalApi val queuedCount: Int
        get() = synchronized(lock) { queue.size }

    /** Records given up for [reason] that no [RaftClientTraceDroppedEventName] event carries yet. */
    @RaftCoreInternalApi fun droppedNotYetReported(reason: RaftClientTraceDropReason): Int =
        synchronized(lock) { dropTallies.getValue(reason).count }

    /** True when the sender can accept foreground handoff (transport + account + privacy Off + foreground). */
    @RaftCoreInternalApi fun canAcceptImmediateHandoff(): Boolean =
        synchronized(lock) {
            transport != null &&
                account != null &&
                privacyMode.value == RaftPrivacyMode.Off &&
                foreground.value
        }

    fun bindTransport(next: RaftClientTraceTransport) {
        synchronized(lock) { transport = next }
    }

    /**
     * The signed-in account, null when signed out. Events are queued under it, and only while one is
     * known. Signing out, or another account, drops what is held, the cached attestation and the
     * counts not yet reported, so nothing recorded under one account is sent or reported under
     * another.
     */
    fun setAccount(accountId: String?) {
        val next = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val dropped =
            synchronized(lock) {
                if (next == account) return
                account = next
                accountEpoch += 1
                heldEpoch += 1
                attestation = null
                retryPending = false
                cancelPendingFlushTimerLocked()
                resetTalliesLocked()
                val held = queue.count { it.report == null }
                queue.clear()
                held
            }
        if (dropped > 0) logSafely("client_trace_dropped reason=account_changed count=$dropped")
    }

    private fun retryDelayForStreakLocked(): Long =
        RaftClientTraceRetryDelaysMillis[(failureStreak - 1).coerceIn(0, RaftClientTraceRetryDelaysMillis.lastIndex)]

    private fun hasFullBatchForLocked(serverId: ServerId, account: String): Boolean {
        if (queue.size < RaftClientTraceMaxBatch) return false
        var count = 0
        for (item in queue) {
            if (item.serverId == serverId && item.account == account) {
                count += 1
                if (count >= RaftClientTraceMaxBatch) return true
            }
        }
        return false
    }

    private fun hasAnyFullBatchLocked(): Boolean {
        if (queue.size < RaftClientTraceMaxBatch) return false
        val counts = HashMap<Pair<ServerId, String>, Int>()
        for (item in queue) {
            val key = item.serverId to item.account
            val next = (counts[key] ?: 0) + 1
            if (next >= RaftClientTraceMaxBatch) return true
            counts[key] = next
        }
        return false
    }

    private fun scheduleFlushLocked(
        delayMillis: Long,
        isRetry: Boolean = false,
        broughtForward: Boolean = false,
    ) {
        if (flushJob != null || flushInProgress) return
        val generation = ++flushGeneration
        flushImmediateScheduled = !isRetry && delayMillis <= 0L
        retryWaiting = isRetry
        retryBroughtForward = broughtForward
        flushJob =
            scope.launch {
                if (delayMillis > 0L) delay(delayMillis)
                flush(generation)
            }
    }

    /** Cancels a waiting flush/retry timer without cancelling an already in-flight HTTP send. */
    private fun cancelPendingFlushTimerLocked() {
        if (flushInProgress) return
        val pending = flushJob ?: return
        flushJob = null
        flushGeneration += 1
        flushImmediateScheduled = false
        retryWaiting = false
        retryBroughtForward = false
        pending.cancel()
    }

    /** Schedules a flush when foregrounded, privacy mode is off, and records are queued. */
    private fun scheduleForActiveStateLocked() {
        if (!foreground.value || privacyMode.value != RaftPrivacyMode.Off || queue.isEmpty() || flushInProgress) return
        if (retryPending) {
            scheduleFlushLocked(retryDelayForStreakLocked(), isRetry = true)
            return
        }
        if (hasAnyFullBatchLocked()) {
            bringForwardFlushForFullBatchLocked()
            return
        }
        scheduleFlushLocked(flushDelayMillis)
    }

    /** Brings a pending normal flush forward to 0 ms when a server batch hits [RaftClientTraceMaxBatch]. */
    private fun bringForwardFlushForFullBatchLocked() {
        if (!foreground.value || privacyMode.value != RaftPrivacyMode.Off) return
        if (flushInProgress || flushImmediateScheduled || retryPending || retryWaiting) return
        if (flushJob != null) cancelPendingFlushTimerLocked()
        scheduleFlushLocked(delayMillis = 0L)
    }

    /**
     * Queues one event for [serverId]; sent with the next flush. Never throws. [traceId] / [spanId]:
     * the trace the event belongs to, when its requests sent that `traceparent`. [occurredAtMillis]:
     * when the event happened (defaults to now), written as `RaftWebEventRecord.time` so buffered
     * events keep their original occurrence timestamp instead of upload time.
     */
    fun emit(
        serverId: String,
        name: String,
        attrs: JsonObject,
        traceId: String? = null,
        spanId: String? = null,
        occurredAtMillis: Long? = null,
    ) {
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        try {
            val now = nowEpochMillis()
            val eventTimeMillis = occurredAtMillis ?: now
            val safeAttrs = sanitizeRaftClientTraceAttrs(attrs)
            val record =
                RaftWebEventRecord(
                    name = name,
                    time = raftEpochMillisUtcIso(eventTimeMillis),
                    traceId = traceId,
                    spanId = spanId,
                    attrs = JsonObject(safeAttrs + defaultAttrs().mapValues { (_, value) -> JsonPrimitive(value) }),
                )
            enqueue { account -> listOf(Queued(ServerId(server), account, record = record, queuedAtMillis = now)) }
        } catch (_: Throwable) {
            // Trace emission must never affect state transitions.
        }
    }

    /**
     * Queues one span with its events (each already carrying the span's trace / span id), all or
     * none: a full queue drops the whole unit. [defaultAttrs] are added to the span and each event.
     * Never throws.
     */
    fun emitSpan(serverId: String, span: RaftWebSpanRecord, events: List<RaftWebEventRecord>) {
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        try {
            val now = nowEpochMillis()
            val defaults = defaultAttrs().mapValues { (_, value) -> JsonPrimitive(value) }
            val id = ServerId(server)
            val withDefaults = span.copy(attrs = JsonObject(sanitizeRaftClientTraceAttrs(span.attrs) + defaults))
            val eventsWithDefaults = events.map { it.copy(attrs = JsonObject(sanitizeRaftClientTraceAttrs(it.attrs) + defaults)) }
            enqueue { account ->
                buildList {
                    add(Queued(id, account, span = withDefaults, queuedAtMillis = now))
                    eventsWithDefaults.forEach { add(Queued(id, account, record = it, queuedAtMillis = now)) }
                }
            }
        } catch (_: Throwable) {
            // Trace emission must never affect the app.
        }
    }

    /** Queues the records [build] makes for the signed-in account; none while no account is known. */
    private fun enqueue(build: (account: String) -> List<Queued>) {
        val line =
            synchronized(lock) {
                val mode = privacyMode.value
                if (mode == RaftPrivacyMode.On) return
                if (transport == null) return
                // Signed out, or before the first sign-in: no account it may be sent under.
                val currentAccount = account ?: return
                val unit = build(currentAccount)
                if (unit.isEmpty()) return
                val over = queue.size + unit.size - RaftClientTraceMaxQueued
                var dropLine: String? = null
                if (over > 0) {
                    val now = nowEpochMillis()
                    if (failureStreak == 0 || unit.size > RaftClientTraceMaxQueued) {
                        // As before: a full queue that is not waiting on a failed send keeps what it has.
                        unit.forEach { giveUpLocked(it, RaftClientTraceDropReason.QueueCap, now) }
                        return@synchronized dropLineLocked(RaftClientTraceDropReason.QueueCap, unit.size)
                    }
                    // Sends are failing: the oldest make room for the newest.
                    repeat(over.coerceAtMost(queue.size)) { giveUpLocked(queue.removeAt(0), RaftClientTraceDropReason.QueueCap, now) }
                    dropLine = dropLineLocked(RaftClientTraceDropReason.QueueCap, over)
                }
                queue += unit
                // Held, not sent, until the client config answers and the app is in the foreground.
                if (mode == RaftPrivacyMode.Off && foreground.value) {
                    if (
                        !flushInProgress &&
                        !flushImmediateScheduled &&
                        !retryPending &&
                        !retryWaiting &&
                        hasFullBatchForLocked(unit.first().serverId, currentAccount)
                    ) {
                        bringForwardFlushForFullBatchLocked()
                    } else {
                        scheduleFlushLocked(flushDelayMillis)
                    }
                }
                dropLine
            }
        line?.let(::logSafely)
    }

    /** Counts [item] as given up for [reason]; a lost drop report puts its own count back instead. */
    private fun giveUpLocked(item: Queued, reason: RaftClientTraceDropReason, now: Long) {
        if (item.report != null) {
            restoreReportLocked(item)
            return
        }
        val tally = dropTallies.getValue(reason)
        tally.count += 1
        tally.oldestAgeMillis = maxOf(tally.oldestAgeMillis, now - item.queuedAtMillis)
    }

    /** A drop report that will not be sent: its count waits for the next one. */
    private fun restoreReportLocked(item: Queued) {
        val report = item.report ?: return
        val tally = dropTallies.getValue(report.reason)
        tally.count += report.count
        tally.oldestAgeMillis = maxOf(tally.oldestAgeMillis, report.oldestAgeMillis)
    }

    private fun resetTalliesLocked() {
        dropTallies.values.forEach { tally ->
            tally.count = 0
            tally.oldestAgeMillis = 0L
        }
    }

    private fun dropLineLocked(reason: RaftClientTraceDropReason, count: Int): String =
        "client_trace_dropped reason=${reason.wireName} count=$count " +
            "notYetReported=${dropTallies.getValue(reason).count} queued=${queue.size}"

    /** One [RaftClientTraceDroppedEventName] event per reason with a count; the tallies move into them. */
    private fun takeDropReportsLocked(serverId: ServerId, account: String): List<Queued> {
        if (dropTallies.values.none { it.count > 0 }) return emptyList()
        return try {
            val now = nowEpochMillis()
            val defaults = defaultAttrs().mapValues { (_, value) -> JsonPrimitive(value) }
            val reports =
                dropTallies.filterValues { it.count > 0 }.map { (reason, tally) ->
                    val attrs =
                        mapOf(
                            RaftClientTraceDroppedCountAttr to JsonPrimitive(tally.count),
                            RaftClientTraceDroppedReasonAttr to JsonPrimitive(reason.wireName),
                            RaftClientTraceDroppedOldestAgeAttr to JsonPrimitive(tally.oldestAgeMillis.coerceAtLeast(0L) / 1_000L),
                        )
                    Queued(
                        serverId = serverId,
                        account = account,
                        record =
                            RaftWebEventRecord(
                                name = RaftClientTraceDroppedEventName,
                                time = raftEpochMillisUtcIso(now),
                                attrs = JsonObject(attrs + defaults),
                            ),
                        queuedAtMillis = now,
                        report = DropReport(reason, tally.count, tally.oldestAgeMillis),
                    )
                }
            reports.forEach { item ->
                val tally = dropTallies.getValue(item.report!!.reason)
                tally.count = 0
                tally.oldestAgeMillis = 0L
            }
            reports
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun flush(generation: Int) {
        val round = FlushRound()
        while (true) {
            val finished = mutableListOf<String>()
            val work = synchronized(lock) { nextBatchLocked(generation, round, finished) }
            finished.forEach(::logSafely)
            if (work == null) return
            val result =
                try {
                    send(work)
                } catch (cancellation: CancellationException) {
                    synchronized(lock) {
                        val stillHeld = work.heldEpoch == heldEpoch && privacyMode.value != RaftPrivacyMode.On
                        if (stillHeld) queue.addAll(0, work.batch)
                        if (generation == flushGeneration) {
                            flushJob = null
                            flushInProgress = false
                            flushImmediateScheduled = false
                        }
                    }
                    throw cancellation
                } catch (_: Throwable) {
                    // Not a transport failure (the network client answers those with status 0): the
                    // request could not be built, e.g. a record that does not encode. That never
                    // changes, so the batch is dropped and the ones behind it go on.
                    SendResult(SendOutcome.Refused, stage = "local_error", status = 0)
                }
            val lines = mutableListOf<String>()
            synchronized(lock) { settleLocked(work, result, round, lines) }
            lines.forEach(::logSafely)
        }
    }

    /**
     * The next batch of this flush: the oldest records of a server it has not put a batch back for.
     * Null when the flush is over; then the retry, if one is needed, is scheduled.
     */
    private fun nextBatchLocked(generation: Int, round: FlushRound, lines: MutableList<String>): FlushWork? {
        // Replaced while its delay was ending: the newer job owns the queue.
        if (generation != flushGeneration) return null
        retryWaiting = false
        retryBroughtForward = false
        flushImmediateScheduled = false
        val transport = transport
        val mode = privacyMode.value
        val isForeground = foreground.value
        val first = queue.firstOrNull { item -> round.putBack.none { it.serverId == item.serverId } }
        if (transport == null || mode != RaftPrivacyMode.Off || !isForeground || first == null) {
            // Unknown again (an environment change) or backgrounded: keep holding until active again.
            if (mode == RaftPrivacyMode.On) {
                queue.clear()
                retryPending = false
            }
            finishFlushLocked(round, mode, isForeground, lines)
            return null
        }
        flushInProgress = true
        retryPending = false
        val batch = queue.filter { it.serverId == first.serverId && it.account == first.account }.take(RaftClientTraceMaxBatch)
        queue.removeAll(batch.toSet())
        return FlushWork(
            transport = transport,
            serverId = first.serverId,
            account = first.account,
            batch = takeDropReportsLocked(first.serverId, first.account) + batch,
            accountEpoch = accountEpoch,
            heldEpoch = heldEpoch,
        )
    }

    /** What becomes of a batch this flush tried. */
    private fun settleLocked(work: FlushWork, result: SendResult, round: FlushRound, lines: MutableList<String>) {
        val batch = work.batch
        val count = batch.count { it.report == null }
        // Neither sign-out, another account nor privacy mode on since it was taken.
        val stillHeld = work.heldEpoch == heldEpoch && privacyMode.value != RaftPrivacyMode.On
        when (result.outcome) {
            SendOutcome.Sent -> round.answered = true
            SendOutcome.Refused -> {
                // An HTTP refusal says the network works; an exception says nothing about it.
                if (result.status != 0) round.answered = true
                if (stillHeld) batch.forEach(::restoreReportLocked)
                lines += "client_trace_dropped reason=refused stage=${result.stage} status=${result.status} count=$count"
            }
            // Recorded under another account than the session's: not sent, and its counts are not
            // the session account's to report.
            SendOutcome.AccountChanged -> lines += "client_trace_dropped reason=account_changed stage=${result.stage} count=$count"
            // Active environment has no trace receiver (e.g. custom / dev / localhost) or switched
            // mid-attestation: skip cleanly without retrying or counting as a transport failure.
            SendOutcome.NoReceiver -> lines += "client_trace_skipped reason=no_receiver stage=${result.stage} count=$count"
            SendOutcome.Resend -> {
                if (stillHeld) queue.addAll(0, batch)
                round.putBack += PutBack(work.serverId, result.stage, result.status, kept = if (stillHeld) batch.size else 0)
            }
        }
    }

    /** This flush is over. One that put a batch back failed: the retry waits longer each time. */
    private fun finishFlushLocked(round: FlushRound, mode: RaftPrivacyMode, isForeground: Boolean, lines: MutableList<String>) {
        flushJob = null
        flushInProgress = false
        flushImmediateScheduled = false
        if (round.putBack.isEmpty()) {
            if (round.answered) failureStreak = 0
            if (queue.isEmpty()) retryPending = false
            return
        }
        val now = nowEpochMillis()
        failureStreak += 1
        // Everything held waited through this failure; what has waited long enough is given up.
        var agedOut = 0
        val held = queue.iterator()
        while (held.hasNext()) {
            val item = held.next()
            val firstFailedAt = item.firstFailedAtMillis
            if (firstFailedAt == null || firstFailedAt > now) {
                item.firstFailedAtMillis = now
            } else if (now - firstFailedAt >= RaftClientTraceGiveUpMillis) {
                held.remove()
                giveUpLocked(item, RaftClientTraceDropReason.AgedOut, now)
                if (item.report == null) agedOut += 1
            }
        }
        var pushedOut = 0
        while (queue.size > RaftClientTraceMaxQueued) {
            val item = queue.removeAt(0)
            giveUpLocked(item, RaftClientTraceDropReason.QueueCap, now)
            if (item.report == null) pushedOut += 1
        }
        var retryInMillis = -1L
        retryPending = queue.isNotEmpty()
        if (retryPending && mode == RaftPrivacyMode.Off && isForeground) {
            retryInMillis = retryDelayForStreakLocked()
            scheduleFlushLocked(retryInMillis, isRetry = true)
        }
        round.putBack.forEach { put ->
            lines += "client_trace_send_failed stage=${put.stage} status=${put.status} failures=$failureStreak " +
                "kept=${put.kept} queued=${queue.size} retryInMs=$retryInMillis"
        }
        if (agedOut > 0) lines += dropLineLocked(RaftClientTraceDropReason.AgedOut, agedOut)
        if (pushedOut > 0) lines += dropLineLocked(RaftClientTraceDropReason.QueueCap, pushedOut)
    }

    private fun logSafely(line: String) {
        try {
            log(line)
        } catch (_: Throwable) {
            // A diagnostics line never affects the app.
        }
    }

    /** The account [transport]'s requests authenticate as now. */
    private suspend fun sessionAccount(transport: RaftClientTraceTransport): String? =
        transport.currentAccount()?.trim()?.takeIf { it.isNotEmpty() }

    /** Normalized receiver base URL for [transport], or null when uploads are skipped. */
    private fun traceUploadBaseUrl(transport: RaftClientTraceTransport): String? =
        transport.currentTraceUploadBaseUrl()?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    private suspend fun send(work: FlushWork): SendResult {
        val transport = work.transport
        val serverId = work.serverId
        // Another account's session (a login replaced it, the shell has not said so yet) or none.
        if (sessionAccount(transport) != work.account) return SendResult(SendOutcome.AccountChanged, "session", 0)
        // Custom / dev / self-hosted servers have no default trace receiver: skip cleanly before
        // minting a scope attestation or posting to another environment's worker.
        val receiverBaseUrl =
            traceUploadBaseUrl(transport)
                ?: run {
                    synchronized(lock) { attestation = null }
                    return SendResult(SendOutcome.NoReceiver, "config", 0)
                }
        val now = nowEpochMillis()
        val cached =
            synchronized(lock) {
                attestation?.takeIf {
                    it.serverId == serverId &&
                        it.account == work.account &&
                        it.traceBaseUrl == receiverBaseUrl &&
                        it.expiresAtMillis - AttestationReuseMarginMillis > now
                }
            }
        val (value, clockCalibration) =
            if (cached != null) {
                cached.value to cached.clockCalibration
            } else {
                synchronized(lock) { attestation = null }
                val t0 = nowEpochMillis()
                val answer = transport.attest(serverId)
                val t1 = nowEpochMillis()
                // Environment may have switched while the attestation request was in flight.
                val postAttestReceiverUrl = traceUploadBaseUrl(transport)
                if (postAttestReceiverUrl == null) {
                    synchronized(lock) { attestation = null }
                    return SendResult(SendOutcome.NoReceiver, "attestation", answer.status)
                }
                if (postAttestReceiverUrl != receiverBaseUrl) {
                    synchronized(lock) { attestation = null }
                    return SendResult(SendOutcome.NoReceiver, "environment_changed", answer.status)
                }
                if (raftClientTraceShouldResend(answer.status)) return SendResult(SendOutcome.Resend, "attestation", answer.status)
                val token =
                    answer.response?.attestation?.takeIf { answer.status in 200..299 && it.isNotBlank() }
                        ?: return SendResult(SendOutcome.Refused, "attestation", answer.status)
                val expiresAt = answer.response?.expiresAt?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }
                val calibration =
                    raftComputePushClockCalibration(
                        t0Millis = t0,
                        t1Millis = t1,
                        serverTimeIso = answer.response?.serverTime,
                        httpDateHeader = answer.httpDateHeader,
                    )
                // The session changed while it was asked for: it may be the other account's.
                if (sessionAccount(transport) != work.account) return SendResult(SendOutcome.AccountChanged, "attestation", answer.status)
                synchronized(lock) {
                    // Signed out or another account meanwhile: this attestation is not theirs to use.
                    if (work.accountEpoch != accountEpoch) return SendResult(SendOutcome.AccountChanged, "attestation", answer.status)
                    if (expiresAt != null) {
                        attestation =
                            Attestation(
                                serverId = serverId,
                                account = work.account,
                                traceBaseUrl = receiverBaseUrl,
                                value = token,
                                expiresAtMillis = expiresAt,
                                clockCalibration = calibration,
                            )
                    }
                }
                token to calibration
            }
        // Re-verify right before POST so an environment switch never sends a mismatched attestation.
        val preSendReceiverUrl = traceUploadBaseUrl(transport)
        if (preSendReceiverUrl == null || preSendReceiverUrl != receiverBaseUrl) {
            synchronized(lock) { attestation = null }
            return SendResult(
                SendOutcome.NoReceiver,
                if (preSendReceiverUrl == null) "send" else "environment_changed",
                0,
            )
        }
        val status =
            transport.send(
                RaftWebTraceBatchRequest(
                    attestation = value,
                    records = work.batch.mapNotNull { it.span },
                    events = work.batch.mapNotNull { it.record?.withPushClockCalibration(clockCalibration) },
                ),
            )
        // If environment switched to one without a receiver right as send ran, skip without retry.
        if (status == 0 && traceUploadBaseUrl(transport) == null) {
            synchronized(lock) { attestation = null }
            return SendResult(SendOutcome.NoReceiver, "send", 0)
        }
        // A rejected attestation (e.g. secret rotation) must not be reused.
        if (status == 401 || status == 403) synchronized(lock) { attestation = null }
        return when {
            status in 200..299 -> SendResult(SendOutcome.Sent, "send", status)
            raftClientTraceShouldResend(status) -> SendResult(SendOutcome.Resend, "send", status)
            else -> SendResult(SendOutcome.Refused, "send", status)
        }
    }
}

/** Process-wide client trace events; the shared store provider binds the transport. */
object RaftClientTraceEvents {
    @RaftCoreInternalApi val sender: RaftClientTraceEventSender by lazy {
        RaftClientTraceEventSender(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            nowEpochMillis = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
            defaultAttrs = ::raftClientTraceDefaultAttrs,
            foreground = RaftProcessForegroundGate.foreground,
            networkRestored = RaftFreshnessSignal.events.filter { it.trigger == RaftFreshnessTrigger.NetworkRestored },
            log = { message -> build.raft.app.raftLogInfo("RaftClientTrace", message) },
        )
    }

    /** The signed-in account ([RaftProductEvents.setSignedIn]): another one drops what is held. */
    @RaftCoreInternalApi fun onSignedIn(userId: String?) {
        val account = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        sender.setAccount(account)
        RaftPushDeliveryTrace.onSignedIn(accountId = account)
    }

    /** Signed out ([RaftProductEvents.setSignedOut]): what is held is dropped, not sent by the next account. */
    @RaftCoreInternalApi fun onSignedOut() {
        sender.setAccount(null)
        RaftPushDeliveryTrace.onSignedOut()
    }

    /** [sessionAccount]: the user id of the session [networkClient] authenticates with (null signed out). */
    fun bindTransport(networkClient: RaftNetworkClient, sessionAccount: suspend () -> String?) {
        bindTransport(
            networkClient = networkClient,
            apiBaseUrlProvider = { networkClient.currentApiBaseUrl() ?: RAFT_API_BASE_URL },
            sessionAccount = sessionAccount,
        )
    }

    /**
     * Binds [networkClient] with a dynamic [apiBaseUrlProvider] so runtime environment switches
     * resolve the matching trace-upload receiver on every flush.
     */
    fun bindTransport(
        networkClient: RaftNetworkClient,
        apiBaseUrlProvider: () -> String,
        sessionAccount: suspend () -> String?,
    ) {
        sender.bindTransport(
            RaftNetworkClientTraceTransport(
                networkClient = networkClient,
                sessionAccount = sessionAccount,
                apiBaseUrlProvider = apiBaseUrlProvider,
            )
        )
        RaftPushDeliveryTrace.onTransportBound()
    }

    /** `mobile_inbox_inplace_v0`: one `activity:inplace_check` (web `emitStateTransitionTrace`). */
    @RaftCoreInternalApi fun emitInPlaceCheck(serverId: String, check: ActivityInPlaceCheck) {
        sender.emit(serverId, RaftStateTransitionTraceName, check.stateTransitionTraceAttrs())
    }

    /**
     * One unit of work: its span and its events (`mobile.thread_open`, ThreadOpenTrace.kt, sampled;
     * `mobile.realtime.session_health`, RaftRealtimeSessionHealth.kt, every window).
     */
    @RaftCoreInternalApi fun emitSpan(serverId: String, span: RaftWebSpanRecord, events: List<RaftWebEventRecord>) {
        sender.emitSpan(serverId, span, events)
    }

    /**
     * `slock.attachment.upload_finished` (RaftAttachmentUploadTelemetry.kt): one composer upload
     * attempt, under the trace id its API requests sent.
     */
    @RaftCoreInternalApi fun emitAttachmentUploadFinished(serverId: String, attrs: JsonObject, traceId: String, spanId: String) {
        sender.emit(serverId, RaftAttachmentUploadFinishedTraceName, attrs, traceId = traceId, spanId = spanId)
    }

    /** `inbox_row_position_v1`: one `activity:list_row_lowered` or `activity:list_row_corrected` (web `reportListRowsLowered`). */
    @RaftCoreInternalApi fun emitListRowLowered(serverId: String, lowered: ActivityListRowLowered) {
        sender.emit(serverId, RaftStateTransitionTraceName, lowered.stateTransitionTraceAttrs())
    }

    /** #proj-mobile:#163: foreground return -> sync pull + Activity updated latency (`activity:foreground_sync_updated`). */
    @RaftCoreInternalApi fun emitForegroundSyncUpdated(serverId: String, telemetry: ActivityForegroundSyncUpdated) {
        sender.emit(serverId, RaftStateTransitionTraceName, telemetry.stateTransitionTraceAttrs())
    }

    /** #proj-o11y:#53 (web slock #16943): `slock.activity.item_reappeared` / `slock.activity.done_committed`. */
    @RaftCoreInternalApi fun emitActivityDoneTrace(serverId: String, event: ActivityDoneTraceEvent) {
        sender.emit(serverId, event.traceName, event.toTraceAttrs())
    }

    /** #proj-o11y:#53 (slock #16951): an Activity row's unread state flipped without the person acting on it. */
    @RaftCoreInternalApi fun emitActivityUnreadStateFlipped(serverId: String, event: ActivityUnreadStateFlipped) {
        sender.emit(serverId, RaftActivityUnreadStateFlippedTraceName, event.toTraceAttrs())
    }

    /** #proj-mobile:#399 (slock #16971): canonical user-visible error record (`mobile.ui_error`). */
    @RaftCoreInternalApi fun emitUiError(serverId: String, attrs: JsonObject, traceId: String? = null) {
        sender.emit(serverId, MOBILE_UI_ERROR_EVENT_NAME, attrs, traceId = traceId)
    }

    /** #proj-mobile:#405: push delivery point events (`mobile.push.received|presented|opened`). */
    @RaftCoreInternalApi fun emitPushDeliveryEvent(
        serverId: String,
        name: String,
        attrs: JsonObject,
        occurredAtMillis: Long,
    ) {
        sender.emit(serverId, name, attrs, occurredAtMillis = occurredAtMillis)
    }

    /** #wg-mobile-sync:#357 (web slock #17140/#17157/#17246): `slock.composer.voice_dictation_session`. */
    @RaftCoreInternalApi fun emitVoiceDictationSession(
        serverId: String,
        summary: VoiceDictationSessionSummary,
    ) {
        sender.emit(
            serverId = serverId,
            name = VoiceDictationSessionEventName,
            attrs = voiceDictationSessionTraceAttrs(summary = summary),
        )
    }
}

/** `clientPlatform` as `X-Raft-Client-Platform` names the app, its `appVersion`, and `app_build`. */
@RaftCoreInternalApi fun raftClientTraceDefaultAttrs(): Map<String, String> =
    buildMap {
        runCatching { featureFlagClientOs() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("clientPlatform", it) }
        runCatching { currentFeatureFlagClientContext().appVersion }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("appVersion", it) }
        runCatching { RaftAppIdentityRegistry.current()?.buildNumber }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { put("app_build", it) }
    }
