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
 * Fire and forget, like the web: batched [RaftClientTraceFlushDelayMillis] after the first event,
 * at most [RaftClientTraceMaxBatch] per request; any failure drops the batch. Nothing here throws.
 */
internal const val RaftClientTraceFlushDelayMillis = 4_000L
internal const val RaftClientTraceMaxBatch = 100
internal const val RaftClientTraceMaxQueued = 500
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

/** Web `WebEventRecord`. */
@Serializable
internal data class RaftWebEventRecord(
    val type: String = "event",
    @kotlinx.serialization.SerialName("schema_version") val schemaVersion: Int = 1,
    val name: String,
    val surface: String = "web",
    val time: String,
    val attrs: JsonObject,
)

/** `POST /api/web-traces` body: events only (the worker accepts an empty `records`). */
@Serializable
internal data class RaftWebTraceBatchRequest(
    val attestation: String,
    val records: List<JsonObject> = emptyList(),
    val events: List<RaftWebEventRecord>,
)

internal interface RaftClientTraceTransport {
    /** A `web-trace-batch:create` attestation for [serverId], or null when the server refused. */
    suspend fun attest(serverId: ServerId): RaftScopeAttestationResponse?

    /** Posts the batch; returns the HTTP status (0 without a response). */
    suspend fun send(batch: RaftWebTraceBatchRequest): Int
}

internal class RaftNetworkClientTraceTransport(
    private val networkClient: RaftNetworkClient,
    private val traceBaseUrl: String = RAFT_AGENT_FEEDBACK_BASE_URL,
) : RaftClientTraceTransport {
    override suspend fun attest(serverId: ServerId): RaftScopeAttestationResponse? {
        val response =
            networkClient.request(
                priority = DataRequestPriority.Background,
                path = "servers/${serverId.value}/scope-attestation",
                method = "POST",
                serverIdOverride = serverId,
                bodyText = clientTraceJson.encodeToString(RaftScopeAttestationRequest(RaftWebTraceScope)),
            )
        if (!response.isSuccessful) return null
        val body = response.bodyText?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { clientTraceJson.decodeFromString<RaftScopeAttestationResponse>(body) }.getOrNull()
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
) {
    private class Queued(val serverId: ServerId, val record: RaftWebEventRecord)

    private class Attestation(val serverId: ServerId, val value: String, val expiresAtMillis: Long)

    private val lock = SynchronizedObject()
    private var transport: RaftClientTraceTransport? = null
    private val queue = mutableListOf<Queued>()
    private var flushJob: Job? = null
    private var attestation: Attestation? = null

    fun bindTransport(next: RaftClientTraceTransport) {
        synchronized(lock) { transport = next }
    }

    /** Queues one event for [serverId]; sent with the next flush. Never throws. */
    fun emit(serverId: String, name: String, attrs: JsonObject) {
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        try {
            val now = nowEpochMillis()
            val record =
                RaftWebEventRecord(
                    name = name,
                    time = raftEpochMillisUtcIso(now),
                    attrs = JsonObject(attrs + defaultAttrs().mapValues { (_, value) -> JsonPrimitive(value) }),
                )
            synchronized(lock) {
                if (transport == null || queue.size >= RaftClientTraceMaxQueued) return
                queue += Queued(ServerId(server), record)
                if (flushJob == null) {
                    flushJob =
                        scope.launch {
                            delay(flushDelayMillis)
                            flush()
                        }
                }
            }
        } catch (_: Throwable) {
            // Trace emission must never affect state transitions.
        }
    }

    private suspend fun flush() {
        while (true) {
            val (target, batch) =
                synchronized(lock) {
                    val transport = transport
                    if (queue.isEmpty() || transport == null) {
                        flushJob = null
                        queue.clear()
                        return
                    }
                    val serverId = queue.first().serverId
                    val batch = queue.filter { it.serverId == serverId }.take(RaftClientTraceMaxBatch)
                    queue.removeAll(batch.toSet())
                    transport to batch
                }
            try {
                send(target, batch.first().serverId, batch.map { it.record })
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Dropped, as web: tracing failure never affects the app.
            }
        }
    }

    private suspend fun send(transport: RaftClientTraceTransport, serverId: ServerId, events: List<RaftWebEventRecord>) {
        val now = nowEpochMillis()
        val cached = synchronized(lock) { attestation?.takeIf { it.serverId == serverId && it.expiresAtMillis - AttestationReuseMarginMillis > now } }
        val value =
            cached?.value
                ?: run {
                    synchronized(lock) { attestation = null }
                    val answer = transport.attest(serverId) ?: return
                    val token = answer.attestation?.takeIf { it.isNotBlank() } ?: return
                    val expiresAt = answer.expiresAt?.let { raw -> runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() }
                    if (expiresAt != null) synchronized(lock) { attestation = Attestation(serverId, token, expiresAt) }
                    token
                }
        val status = transport.send(RaftWebTraceBatchRequest(attestation = value, events = events))
        // A rejected attestation (e.g. secret rotation) must not be reused.
        if (status == 401 || status == 403) synchronized(lock) { attestation = null }
    }
}

/** Process-wide client trace events; the shared store provider binds the transport. */
object RaftClientTraceEvents {
    internal val sender: RaftClientTraceEventSender by lazy {
        RaftClientTraceEventSender(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            nowEpochMillis = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
            defaultAttrs = ::raftClientTraceDefaultAttrs,
        )
    }

    fun bindTransport(networkClient: RaftNetworkClient) {
        sender.bindTransport(RaftNetworkClientTraceTransport(networkClient))
    }

    /** `mobile_inbox_inplace_v0`: one `activity:inplace_check` (web `emitStateTransitionTrace`). */
    internal fun emitInPlaceCheck(serverId: String, check: ActivityInPlaceCheck) {
        sender.emit(serverId, RaftStateTransitionTraceName, check.stateTransitionTraceAttrs())
    }
}

/** `clientPlatform` as `X-Raft-Client-Platform` names the app, and its `appVersion`. */
internal fun raftClientTraceDefaultAttrs(): Map<String, String> =
    buildMap {
        runCatching { featureFlagClientOs() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("clientPlatform", it) }
        runCatching { currentFeatureFlagClientContext().appVersion }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("appVersion", it) }
    }
