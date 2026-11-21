package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
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
 * Batched [RaftClientTraceFlushDelayMillis] after the first event, at most
 * [RaftClientTraceMaxBatch] per request. Nothing here throws. What a failed flush does with its batch:
 *
 * - No HTTP answer (status 0 from [RaftNetworkClient]: connect, DNS, reset, timeout, a closed
 *   reachability gate) or a 5xx, from the attestation request or the trace POST
 *   ([raftClientTraceShouldResend]): the batch goes back to the front of the queue in its original
 *   order, records unchanged (the same `time`, `trace_id` and `span_id` as the first attempt), and
 *   the next flush waits [RaftClientTraceRetryDelaysMillis] (4 s, 15 s, 60 s, then every 5 min; back
 *   to the start after any HTTP answer). A network-restored signal ([RaftFreshnessSignal]) replaces a
 *   waiting retry with one [RaftClientTraceFlushDelayMillis] from then, once per wait.
 * - Any other answer (4xx, including a refused attestation, or a 2xx attestation without a token),
 *   or an exception while building the request (a record that does not encode): the batch is
 *   dropped, as the web does, and the flush goes on with the next; sending it again cannot help.
 * - Queueing an event never sends early: while a retry is waiting, new events (also at the cap)
 *   wait for it.
 * - Bounds: the queue never holds more than [RaftClientTraceMaxQueued]; while sends are failing a
 *   full queue drops its oldest records to take newer ones (otherwise, as before, the new unit is
 *   dropped). A record still unsent [RaftClientTraceGiveUpMillis] after the first failed flush it
 *   waited through is dropped at the next failure. Both kinds of loss are logged as counts, and the
 *   next batch carries one [RaftClientTraceDroppedEventName] event per reason (`dropped_count`,
 *   `reason` `aged_out` / `queue_cap`, `oldest_dropped_age_s`). That event is queued like any other
 *   (resent unchanged with its batch); a count is forgotten only when its batch was accepted, and
 *   losing the event itself puts its count back. Drops under privacy mode, a refusal or an account
 *   change are not counted.
 * - Account: records are queued per server and a batch is attested for its own server by the
 *   signed-in session at flush time. Sign-out or another account ([RaftClientTraceEventSender.setAccount])
 *   drops everything held and the cached attestation, so a record is never sent under another
 *   account's attestation. A server switch within one account keeps them.
 *
 * `RAFT_PRIVACY_MODE` ([RaftTelemetryPrivacy], web `webTelemetryPrivacyHold.ts`): until the
 * environment's client config answers, events are held in memory (up to [RaftClientTraceMaxQueued])
 * and nothing is sent, not even the attestation request; "on" drops what was held and every later
 * event; "off" sends what was held, then everything is as before.
 */
internal const val RaftClientTraceFlushDelayMillis = 4_000L
internal const val RaftClientTraceMaxBatch = 100
internal const val RaftClientTraceMaxQueued = 500

/** Wait before the flush after the 1st, 2nd, 3rd, and every later consecutive failed one. */
internal val RaftClientTraceRetryDelaysMillis = listOf(4_000L, 15_000L, 60_000L, 300_000L)

/** A record is given up this long after the first failed flush it waited through. */
internal const val RaftClientTraceGiveUpMillis = 30 * 60_000L

/**
 * How many records were given up, by reason; carried by the next batch. The receiver stores it once
 * the name is registered there and ignores it until then.
 */
internal const val RaftClientTraceDroppedEventName = "slock.client_trace.dropped"
internal const val RaftClientTraceDroppedCountAttr = "dropped_count"
internal const val RaftClientTraceDroppedReasonAttr = "reason"
internal const val RaftClientTraceDroppedOldestAgeAttr = "oldest_dropped_age_s"

/** Why a record was given up (`reason` of [RaftClientTraceDroppedEventName]). */
internal enum class RaftClientTraceDropReason(val wireName: String) {
    /** Still unsent [RaftClientTraceGiveUpMillis] after its first failed flush. */
    AgedOut("aged_out"),

    /** Pushed out by [RaftClientTraceMaxQueued]. */
    QueueCap("queue_cap"),
}

/**
 * Whether a batch whose request ended with [status] is kept and sent again: 0 (the shared network
 * client's "no HTTP response") or a 5xx. Any other non-2xx answer is a refusal.
 */
internal fun raftClientTraceShouldResend(status: Int): Boolean = status == 0 || status in 500..599
internal const val RaftWebTraceScope = "web-trace-batch:create"
internal const val RaftStateTransitionTraceName = "slock.state.transition"

/** The web reuses an attestation until this long before it expires (ATTESTATION_REUSE_MARGIN_MS). */
private const val AttestationReuseMarginMillis = 60_000L

private val clientTraceJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

@Serializable
internal data class RaftScopeAttestationRequest(val scope: String)

@Serializable
internal data class RaftScopeAttestationResponse(val attestation: String? = null, val expiresAt: String? = null)

/** Web `WebEventRecord`; inside a span it carries that span's [traceId] / [spanId]. */
@Serializable
internal data class RaftWebEventRecord(
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
internal data class RaftWebSpanRecord(
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
internal data class RaftWebTraceBatchRequest(
    val attestation: String,
    val records: List<RaftWebSpanRecord> = emptyList(),
    val events: List<RaftWebEventRecord>,
)

/** The attestation request's HTTP [status] (0 without a response) and, for a readable 2xx, its body. */
internal class RaftScopeAttestationAnswer(val status: Int, val response: RaftScopeAttestationResponse? = null)

internal interface RaftClientTraceTransport {
    /** Asks for a `web-trace-batch:create` attestation for [serverId]. */
    suspend fun attest(serverId: ServerId): RaftScopeAttestationAnswer

    /** Posts the batch; returns the HTTP status (0 without a response). */
    suspend fun send(batch: RaftWebTraceBatchRequest): Int
}

internal class RaftNetworkClientTraceTransport(
    private val networkClient: RaftNetworkClient,
    private val traceBaseUrl: String = RAFT_AGENT_FEEDBACK_BASE_URL,
) : RaftClientTraceTransport {
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
        return RaftScopeAttestationAnswer(
            status = response.code,
            response = body?.let { runCatching { clientTraceJson.decodeFromString<RaftScopeAttestationResponse>(it) }.getOrNull() },
        )
    }

    override suspend fun send(batch: RaftWebTraceBatchRequest): Int =
        networkClient.requestRawAbsoluteUrl(
            priority = DataRequestPriority.Background,
            url = "${traceBaseUrl.trimEnd('/')}/api/web-traces",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            bodyText = clientTraceJson.encodeToString(batch),
        ).code
}

internal class RaftClientTraceEventSender(
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long,
    /** Attributes every event carries (`clientPlatform`, `appVersion`). */
    private val defaultAttrs: () -> Map<String, String>,
    private val flushDelayMillis: Long = RaftClientTraceFlushDelayMillis,
    private val privacyMode: StateFlow<RaftPrivacyMode> = RaftTelemetryPrivacy.state,
    /** Each emission: the device's network came back; a waiting retry is brought forward. */
    networkRestored: Flow<Any> = emptyFlow(),
    /** Diagnostics lines: counts and statuses only, never a record's name or content. */
    private val log: (String) -> Unit = {},
) {
    /** What a [RaftClientTraceDroppedEventName] event carries; put back if that event is lost. */
    private class DropReport(val reason: RaftClientTraceDropReason, val count: Int, val oldestAgeMillis: Long)

    /** One queued record: an event, or a span. */
    private class Queued(
        val serverId: ServerId,
        val record: RaftWebEventRecord? = null,
        val span: RaftWebSpanRecord? = null,
        val queuedAtMillis: Long = 0L,
        val report: DropReport? = null,
    ) {
        /** When the first flush this record waited through failed; null before any failure. */
        var firstFailedAtMillis: Long? = null
    }

    private class Attestation(val serverId: ServerId, val value: String, val expiresAtMillis: Long)

    /** Records given up for one reason and not yet carried by an event. */
    private class DropTally {
        var count = 0
        var oldestAgeMillis = 0L
    }

    private class FlushWork(val transport: RaftClientTraceTransport, val batch: List<Queued>, val accountEpoch: Int)

    private enum class SendOutcome { Sent, Refused, Resend }

    private class SendResult(val outcome: SendOutcome, val stage: String, val status: Int)

    private val lock = SynchronizedObject()
    private var transport: RaftClientTraceTransport? = null
    private val queue = mutableListOf<Queued>()
    private var flushJob: Job? = null

    /** Names the one flush job that may run; a replaced job (an early retry) finds another value. */
    private var flushGeneration = 0
    private var attestation: Attestation? = null

    /** Consecutive flushes that ended without a usable HTTP answer. */
    private var failureStreak = 0

    /** The flush job is waiting out a retry delay (not sending). */
    private var retryWaiting = false
    private var retryBroughtForward = false
    private var account: String? = null
    private var accountEpoch = 0
    private val dropTallies = RaftClientTraceDropReason.entries.associateWith { DropTally() }

    init {
        scope.launch {
            privacyMode.collect { mode ->
                synchronized(lock) {
                    when (mode) {
                        RaftPrivacyMode.On -> {
                            queue.clear()
                            dropTallies.values.forEach { tally ->
                                tally.count = 0
                                tally.oldestAgeMillis = 0L
                            }
                        }
                        RaftPrivacyMode.Off -> if (queue.isNotEmpty()) scheduleFlushLocked(flushDelayMillis)
                        RaftPrivacyMode.Unknown -> Unit
                    }
                }
            }
        }
        scope.launch {
            networkRestored.collect {
                synchronized(lock) {
                    if (retryWaiting && !retryBroughtForward && queue.isNotEmpty()) {
                        retryBroughtForward = true
                        flushJob?.cancel()
                        flushJob = null
                        scheduleFlushLocked(flushDelayMillis)
                    }
                }
            }
        }
    }

    /** Events waiting to be sent (held while privacy mode is unknown, or for a retry). */
    internal val queuedCount: Int
        get() = synchronized(lock) { queue.size }

    /** Records given up for [reason] that no [RaftClientTraceDroppedEventName] event carries yet. */
    internal fun droppedNotYetReported(reason: RaftClientTraceDropReason): Int =
        synchronized(lock) { dropTallies.getValue(reason).count }

    fun bindTransport(next: RaftClientTraceTransport) {
        synchronized(lock) { transport = next }
    }

    /**
     * The signed-in account, null when signed out. Signing out, or another account than the last
     * one, drops what is held and the cached attestation (a record never leaves under another
     * account's attestation); the first account after a start or a sign-out keeps what is held.
     */
    fun setAccount(accountId: String?) {
        val dropped =
            synchronized(lock) {
                val previous = account
                account = accountId
                if (accountId != null && (previous == null || previous == accountId)) return
                accountEpoch += 1
                attestation = null
                val held = queue.count { it.report == null }
                queue.forEach(::restoreReportLocked)
                queue.clear()
                held
            }
        if (dropped > 0) logSafely("client_trace_dropped reason=account_changed count=$dropped")
    }

    private fun scheduleFlushLocked(delayMillis: Long) {
        if (flushJob != null) return
        val generation = ++flushGeneration
        flushJob =
            scope.launch {
                delay(delayMillis)
                flush(generation)
            }
    }

    /**
     * Queues one event for [serverId]; sent with the next flush. Never throws. [traceId] / [spanId]:
     * the trace the event belongs to, when its requests sent that `traceparent`.
     */
    fun emit(serverId: String, name: String, attrs: JsonObject, traceId: String? = null, spanId: String? = null) {
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        try {
            val now = nowEpochMillis()
            val record =
                RaftWebEventRecord(
                    name = name,
                    time = raftEpochMillisUtcIso(now),
                    traceId = traceId,
                    spanId = spanId,
                    attrs = JsonObject(attrs + defaultAttrs().mapValues { (_, value) -> JsonPrimitive(value) }),
                )
            enqueue(listOf(Queued(ServerId(server), record = record, queuedAtMillis = now)))
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
            val unit =
                buildList {
                    add(Queued(id, span = span.copy(attrs = JsonObject(span.attrs + defaults)), queuedAtMillis = now))
                    events.forEach {
                        add(Queued(id, record = it.copy(attrs = JsonObject(it.attrs + defaults)), queuedAtMillis = now))
                    }
                }
            enqueue(unit)
        } catch (_: Throwable) {
            // Trace emission must never affect the app.
        }
    }

    private fun enqueue(unit: List<Queued>) {
        val line =
            synchronized(lock) {
                val mode = privacyMode.value
                if (mode == RaftPrivacyMode.On) return
                if (transport == null) return
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
                // Held, not sent, until the client config answers.
                if (mode == RaftPrivacyMode.Off) scheduleFlushLocked(flushDelayMillis)
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

    private fun dropLineLocked(reason: RaftClientTraceDropReason, count: Int): String =
        "client_trace_dropped reason=${reason.wireName} count=$count " +
            "notYetReported=${dropTallies.getValue(reason).count} queued=${queue.size}"

    /** One [RaftClientTraceDroppedEventName] event per reason with a count; the tallies move into them. */
    private fun takeDropReportsLocked(serverId: ServerId): List<Queued> {
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
        while (true) {
            val work =
                synchronized(lock) {
                    // Replaced while its delay was ending: the newer job owns the queue.
                    if (generation != flushGeneration) return
                    retryWaiting = false
                    retryBroughtForward = false
                    val transport = transport
                    val mode = privacyMode.value
                    if (queue.isEmpty() || transport == null || mode != RaftPrivacyMode.Off) {
                        flushJob = null
                        // Unknown again (an environment change): keep holding; the answer flushes.
                        if (mode != RaftPrivacyMode.Unknown) queue.clear()
                        return
                    }
                    val serverId = queue.first().serverId
                    val batch = queue.filter { it.serverId == serverId }.take(RaftClientTraceMaxBatch)
                    queue.removeAll(batch.toSet())
                    FlushWork(transport, takeDropReportsLocked(serverId) + batch, accountEpoch)
                }
            val result =
                try {
                    send(work)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Not a transport failure (the network client answers those with status 0): the
                    // request could not be built, e.g. a record that does not encode. That never
                    // changes, so the batch is dropped and the ones behind it go on.
                    SendResult(SendOutcome.Refused, stage = "local_error", status = 0)
                }
            val lines = mutableListOf<String>()
            val again = synchronized(lock) { settleLocked(work, result, lines) }
            lines.forEach(::logSafely)
            if (!again) return
        }
    }

    /** What becomes of a sent batch; false when this flush is over (a later one is scheduled if needed). */
    private fun settleLocked(work: FlushWork, result: SendResult, lines: MutableList<String>): Boolean {
        val batch = work.batch
        when (result.outcome) {
            SendOutcome.Sent -> {
                failureStreak = 0
                return true
            }
            SendOutcome.Refused -> {
                // An HTTP answer: the network works, and sending the same batch again cannot help.
                failureStreak = 0
                batch.forEach(::restoreReportLocked)
                lines += "client_trace_dropped reason=refused stage=${result.stage} status=${result.status} " +
                    "count=${batch.count { it.report == null }}"
                return true
            }
            SendOutcome.Resend -> Unit
        }
        val now = nowEpochMillis()
        val mode = privacyMode.value
        failureStreak += 1
        val sameAccount = work.accountEpoch == accountEpoch
        when {
            mode == RaftPrivacyMode.On -> queue.clear()
            sameAccount -> queue.addAll(0, batch)
            else -> batch.forEach(::restoreReportLocked)
        }
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
        flushJob = null
        var retryInMillis = -1L
        if (queue.isNotEmpty() && mode == RaftPrivacyMode.Off) {
            retryInMillis = RaftClientTraceRetryDelaysMillis[(failureStreak - 1).coerceIn(0, RaftClientTraceRetryDelaysMillis.lastIndex)]
            scheduleFlushLocked(retryInMillis)
            retryWaiting = true
        }
        lines += "client_trace_send_failed stage=${result.stage} status=${result.status} failures=$failureStreak " +
            "kept=${if (sameAccount && mode != RaftPrivacyMode.On) batch.size else 0} queued=${queue.size} retryInMs=$retryInMillis"
        if (agedOut > 0) lines += dropLineLocked(RaftClientTraceDropReason.AgedOut, agedOut)
        if (pushedOut > 0) lines += dropLineLocked(RaftClientTraceDropReason.QueueCap, pushedOut)
        return false
    }

    private fun logSafely(line: String) {
        try {
            log(line)
        } catch (_: Throwable) {
            // A diagnostics line never affects the app.
        }
    }

    private suspend fun send(work: FlushWork): SendResult {
        val transport = work.transport
        val serverId = work.batch.first().serverId
        val now = nowEpochMillis()
        val cached = synchronized(lock) { attestation?.takeIf { it.serverId == serverId && it.expiresAtMillis - AttestationReuseMarginMillis > now } }
        val value =
            cached?.value
                ?: run {
                    synchronized(lock) { attestation = null }
                    val answer = transport.attest(serverId)
                    if (raftClientTraceShouldResend(answer.status)) return SendResult(SendOutcome.Resend, "attestation", answer.status)
                    val token =
                        answer.response?.attestation?.takeIf { answer.status in 200..299 && it.isNotBlank() }
                            ?: return SendResult(SendOutcome.Refused, "attestation", answer.status)
                    val expiresAt = answer.response?.expiresAt?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }
                    synchronized(lock) {
                        // Signed out or another account meanwhile: this attestation is not theirs to use.
                        if (work.accountEpoch != accountEpoch) return SendResult(SendOutcome.Refused, "account_changed", answer.status)
                        if (expiresAt != null) attestation = Attestation(serverId, token, expiresAt)
                    }
                    token
                }
        val status =
            transport.send(
                RaftWebTraceBatchRequest(
                    attestation = value,
                    records = work.batch.mapNotNull { it.span },
                    events = work.batch.mapNotNull { it.record },
                ),
            )
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
    internal val sender: RaftClientTraceEventSender by lazy {
        RaftClientTraceEventSender(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            nowEpochMillis = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
            defaultAttrs = ::raftClientTraceDefaultAttrs,
            networkRestored = RaftFreshnessSignal.events.filter { it.trigger == RaftFreshnessTrigger.NetworkRestored },
            log = { message -> build.raft.app.raftLogInfo("RaftClientTrace", message) },
        )
    }

    /** The signed-in account ([RaftProductEvents.setSignedIn]): another one drops what is held. */
    internal fun onSignedIn(userId: String?) {
        val account = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        sender.setAccount(account)
    }

    /** Signed out ([RaftProductEvents.setSignedOut]): what is held is dropped, not sent by the next account. */
    internal fun onSignedOut() {
        sender.setAccount(null)
    }

    fun bindTransport(networkClient: RaftNetworkClient) {
        sender.bindTransport(RaftNetworkClientTraceTransport(networkClient))
    }

    /** `mobile_inbox_inplace_v0`: one `activity:inplace_check` (web `emitStateTransitionTrace`). */
    internal fun emitInPlaceCheck(serverId: String, check: ActivityInPlaceCheck) {
        sender.emit(serverId, RaftStateTransitionTraceName, check.stateTransitionTraceAttrs())
    }

    /** One sampled unit of work: its span and its events (`mobile.thread_open`, ThreadOpenTrace.kt). */
    internal fun emitSpan(serverId: String, span: RaftWebSpanRecord, events: List<RaftWebEventRecord>) {
        sender.emitSpan(serverId, span, events)
    }

    /**
     * `slock.attachment.upload_finished` (RaftAttachmentUploadTelemetry.kt): one composer upload
     * attempt, under the trace id its API requests sent.
     */
    internal fun emitAttachmentUploadFinished(serverId: String, attrs: JsonObject, traceId: String, spanId: String) {
        sender.emit(serverId, RaftAttachmentUploadFinishedTraceName, attrs, traceId = traceId, spanId = spanId)
    }

    /** `inbox_row_position_v1`: one `activity:list_row_lowered` or `activity:list_row_corrected` (web `reportListRowsLowered`). */
    internal fun emitListRowLowered(serverId: String, lowered: ActivityListRowLowered) {
        sender.emit(serverId, RaftStateTransitionTraceName, lowered.stateTransitionTraceAttrs())
    }
}

/** `clientPlatform` as `X-Raft-Client-Platform` names the app, and its `appVersion`. */
internal fun raftClientTraceDefaultAttrs(): Map<String, String> =
    buildMap {
        runCatching { featureFlagClientOs() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("clientPlatform", it) }
        runCatching { currentFeatureFlagClientContext().appVersion }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("appVersion", it) }
    }
