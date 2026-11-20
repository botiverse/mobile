package build.raft.app.data

import build.raft.app.network.RaftTraceIds
import build.raft.app.network.RaftUploadWireFacts
import build.raft.app.network.newRaftTraceIds
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `slock.attachment.upload_finished` from the app: how one composer attachment upload ended and where
 * its time went, with the web's event and attribute names (botiverse/slock
 * `packages/web/src/utils/attachmentUploadOutcomeTrace.ts`, slock #15924) so web, desktop and app read
 * in one table. One event per upload attempt, when it settles.
 *
 * Attributes (an absent value is left out, never written as 0 or null):
 * - `route`: `legacy` (multipart POST to `/api/attachments/upload`), `direct` (upload session and a
 *   presigned PUT to the object store) or `unknown` (ended before a request was made).
 * - `outcome`: `ok` / `error` / `timeout` / `cancelled`; `error_code`: the app's error code.
 * - `size_bucket`, `elapsed_bucket`, `duration_ms`: as the web. The clock starts when the picked
 *   files reach the composer.
 * - `prepare_ms`: from there to the first upload request (the multipart POST, or the upload session's
 *   create call): capability lookup, waiting for an upload slot, reading the file's size.
 * - `request_body_ms`, `response_wait_ms`, `request_bytes`, `edge_pop`: [RaftUploadWireFacts], of the
 *   multipart POST or the presigned PUT (the last send when the body went twice).
 *
 * The rest of `duration_ms` is the control calls (session create / complete) and connection setup.
 * No file name, id, URL or user identifier. `clientPlatform` and `appVersion` are added by the sender.
 */
internal const val RaftAttachmentUploadFinishedTraceName = "slock.attachment.upload_finished"

internal const val RaftAttachmentUploadRouteLegacy = "legacy"
internal const val RaftAttachmentUploadRouteDirect = "direct"
internal const val RaftAttachmentUploadRouteUnknown = "unknown"

/**
 * One upload attempt's timing, filled in by the network layer as the upload runs. The composer puts
 * it in the coroutine context of the upload ([kotlin.coroutines.coroutineContext]); the network
 * client reads it there, so no repository signature carries telemetry. An upload started elsewhere
 * has none and records nothing.
 *
 * It also owns the attempt's trace id: the API requests of the upload (the multipart POST, the
 * session create and complete calls) send [traceHeaders], so their `server.http.request` spans join
 * the trace id the event carries. The presigned PUT to the object store sends no `traceparent`.
 */
internal class RaftAttachmentUploadTimingRecorder(
    /** Milliseconds since the picked files reached the composer. */
    private val elapsedMs: () -> Long,
    val traceIds: RaftTraceIds = newRaftTraceIds(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RaftAttachmentUploadTimingRecorder>

    internal class Snapshot(val route: String?, val prepareMs: Long?, val facts: RaftUploadWireFacts?)

    private val lock = SynchronizedObject()
    private var route: String? = null
    private var prepareMs: Long? = null
    private var facts: RaftUploadWireFacts? = null

    fun traceHeaders(): Map<String, String> = mapOf("traceparent" to traceIds.traceparent)

    /** The first request of the upload is about to be made: preparation ends here (first call only). */
    fun uploadRequestStarting(route: String) {
        val now = runCatching(elapsedMs).getOrNull()
        synchronized(lock) {
            this.route = route
            if (prepareMs == null) prepareMs = now
        }
    }

    /** The body's request ended (answered or failed); a later send replaces an earlier one. */
    fun transferFinished(route: String, facts: RaftUploadWireFacts?) {
        synchronized(lock) {
            this.route = route
            this.facts = facts
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(route, prepareMs, facts) }
}

internal data class RaftAttachmentUploadPhases(
    val prepareMs: Long? = null,
    val requestBodyMs: Long? = null,
    val responseWaitMs: Long? = null,
)

/**
 * The three phases as reported: a phase that was not measured stays null, the response wait needs a
 * body time, and together they never exceed [durationMs] (the engine's clock and the app's are read
 * separately, so each phase is capped by what is left of the total).
 */
internal fun raftAttachmentUploadPhases(
    durationMs: Long,
    prepareMs: Long?,
    facts: RaftUploadWireFacts?,
): RaftAttachmentUploadPhases {
    var left = durationMs.coerceAtLeast(0L)
    fun capped(value: Long?): Long? {
        if (value == null || value < 0L) return null
        val kept = minOf(value, left)
        left -= kept
        return kept
    }
    val prepare = capped(prepareMs)
    val body = capped(facts?.requestBodyMs)
    val wait = if (body == null) null else capped(facts?.responseWaitMs)
    return RaftAttachmentUploadPhases(prepareMs = prepare, requestBodyMs = body, responseWaitMs = wait)
}

internal data class RaftAttachmentUploadFinished(
    val route: String?,
    /** Null for a success. */
    val errorCode: String? = null,
    val cancelled: Boolean = false,
    /** The size of the file sent, when known. */
    val sizeBytes: Long? = null,
    val durationMs: Long,
    val prepareMs: Long? = null,
    val facts: RaftUploadWireFacts? = null,
)

/** Web `directPutSizeBucket`. */
internal fun raftAttachmentUploadSizeBucket(bytes: Long): String {
    val mb = 1024L * 1024L
    return when {
        bytes < 10L * mb -> "<10MB"
        bytes < 50L * mb -> "10-50MB"
        bytes < 200L * mb -> "50-200MB"
        else -> ">200MB"
    }
}

/** Web `directPutElapsedBucket`. */
internal fun raftAttachmentUploadElapsedBucket(ms: Long): String =
    when {
        ms < 1_000L -> "<1s"
        ms < 10_000L -> "1-10s"
        ms < 60_000L -> "10-60s"
        ms < 300_000L -> "1-5m"
        else -> ">5m"
    }

private val RaftAttachmentUploadErrorCodePattern = Regex("""^[A-Za-z0-9_.:-]{1,64}$""")

/** `ok` / `error` / `timeout` / `cancelled`, the web's closed set. */
internal fun raftAttachmentUploadOutcome(errorCode: String?, cancelled: Boolean): String =
    when {
        cancelled || errorCode == "cancelled" -> "cancelled"
        errorCode == null -> "ok"
        errorCode.contains("timeout", ignoreCase = true) || errorCode.contains("timed_out", ignoreCase = true) -> "timeout"
        else -> "error"
    }

internal fun raftAttachmentUploadFinishedAttrs(finished: RaftAttachmentUploadFinished): JsonObject {
    val durationMs = finished.durationMs.coerceAtLeast(0L)
    val outcome = raftAttachmentUploadOutcome(finished.errorCode, finished.cancelled)
    val phases = raftAttachmentUploadPhases(durationMs, finished.prepareMs, finished.facts)
    val sizeBytes = finished.sizeBytes?.takeIf { it > 0L } ?: finished.facts?.requestBytes?.takeIf { it > 0L }
    return buildJsonObject {
        put("route", finished.route ?: RaftAttachmentUploadRouteUnknown)
        put("outcome", outcome)
        if (outcome == "error" || outcome == "timeout") {
            // A code is an identifier; anything else (a message, a path) is not recorded.
            finished.errorCode?.let { code -> put("error_code", if (RaftAttachmentUploadErrorCodePattern.matches(code)) code else "other") }
        }
        sizeBytes?.let { put("size_bucket", raftAttachmentUploadSizeBucket(it)) }
        put("elapsed_bucket", raftAttachmentUploadElapsedBucket(durationMs))
        put("duration_ms", durationMs)
        phases.prepareMs?.let { put("prepare_ms", it) }
        phases.requestBodyMs?.let { put("request_body_ms", it) }
        phases.responseWaitMs?.let { put("response_wait_ms", it) }
        finished.facts?.requestBytes?.takeIf { it > 0L }?.let { put("request_bytes", it) }
        finished.facts?.edgePop?.let { put("edge_pop", it) }
    }
}

/** The same facts as one diagnostics line (every build, on device). */
internal fun raftAttachmentUploadFinishedLogLine(attrs: JsonObject, traceId: String): String =
    "thread_composer_attachment_upload_finished " +
        attrs.entries.joinToString(" ") { (name, value) -> "$name=${value.toString().trim('"')}" } +
        " traceId=$traceId"
