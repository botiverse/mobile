package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.network.RAFT_TRANSPORT_ERROR_OTHER
import build.raft.app.network.RaftTraceIds
import build.raft.app.network.RaftTransportFailureKind
import build.raft.app.network.RaftUploadWireFacts
import build.raft.app.network.newRaftTraceIds
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `slock.attachment.upload_finished` from the app: how one composer attachment upload ended and where
 * its time went, with the web's event and attribute names (botiverse/slock
 * `packages/web/src/utils/attachmentUploadOutcomeTrace.ts`, slock #15924) so web, desktop and app read
 * in one table. One event per upload attempt, when it settles: the first upload of a picked file, and
 * each Retry the user taps, is its own attempt with its own trace id. Re-sends inside an attempt (the
 * multipart POST after a connection reset or a token refresh, the complete call's retries, the
 * recovery after an uncertain PUT) stay in that attempt's one event.
 *
 * Attributes (an absent value is left out, never written as 0 or null):
 * - `route`: `legacy` (multipart POST to `/api/attachments/upload`), `direct` (upload session and a
 *   presigned PUT to the object store) or `unknown` (ended before a request was made).
 * - `outcome`: `ok` / `error` / `timeout` / `cancelled`. `timeout_phase` (only with `timeout`): the
 *   web's `create` / `transfer` / `response` / `complete` / `status`. `error_code`: a bounded set
 *   ([raftAttachmentUploadErrorCode]).
 * - `cancel_reason` (only with `cancelled`, always then; never on `ok`, `error` or `timeout`): why the
 *   upload's task was cancelled, a closed set ([RaftAttachmentUploadCancelReason]): `user_removed`
 *   (the user removed the tile), `draft_sent` (the draft was sent), `draft_cleared` (the draft was
 *   cleared), `session_ended` (sign-out, or the server is no longer available) or `other` (a cancel
 *   that carried none of these). Each place that cancels an upload says why ([RaftUploadCancelled]);
 *   an `other` also writes one diagnostics line naming where it came from
 *   ([raftAttachmentUploadCancelOtherLogLine]).
 * - `attempt`: 1 for the first upload of a picked file, 2 for its first Retry, and so on (counted
 *   while the conversation stays open).
 * - `size_bucket`, `elapsed_bucket`, `duration_ms`: as the web. The clock starts when the picked
 *   files reach the composer.
 * - `prepare_ms`: from there to the first upload request being handed to the network client (the
 *   multipart POST, or the upload session's create call), before it queues for a request slot:
 *   waiting for an upload slot, reading the file's size, a capability cache lookup. A capability
 *   probe this attempt made in that window is `control_ms`, not `prepare_ms` (subtracted; never
 *   negative), so the two are disjoint as on the web.
 * - `connect_ms`, `request_body_ms`, `response_wait_ms`, `request_bytes`, `edge_pop`:
 *   [RaftUploadWireFacts], of the multipart POST or the presigned PUT. When the body went more than
 *   once they describe the last send, and `body_sends` says how many there were.
 * - `request_body_partial_ms`, `request_bytes_sent`: only when the body never finished (a failure,
 *   timeout or cancel mid-send): how long it had been going out and how many file bytes were reported
 *   sent. The time is the engine's on Android / desktop when the transport returned
 *   ([RaftUploadWireFacts.requestBodyPartialMs]); otherwise (iOS, and every cancellation) it is the
 *   span between the first and the last upload-progress report.
 * - `control_ms`: as the web (slock #16006), the summed time of the first attempt of each control
 *   call: the capability probe (both routes), plus on `direct` the session create and the first
 *   complete call. A failed call counts the time it took, and so does a token refresh and resend
 *   inside it (the auth middleware's 401 handling runs within the call). Later complete attempts,
 *   the recovery after an uncertain PUT (status and its completes) and body resends are left out.
 *   Absent when no control call was reached. The app keeps the capabilities for a few minutes
 *   ([AttachmentUploadCapabilitiesCache]): the probe counts only when this attempt itself fetched
 *   them; a cache hit, or a capability prefetched before the attempt, adds to neither `control_ms`
 *   nor `prepare_ms`. Each call is timed where the upload makes it ([raftUploadControlCall]), on
 *   the attempt's monotonic clock.
 *
 * As on the web the phases are disjoint: `prepare_ms`, `control_ms`, `connect_ms`, `request_body_ms`
 * and `response_wait_ms` never add up to more than `duration_ms` ([raftAttachmentUploadPhases]); the
 * rest is retries (later complete attempts), recovery polling, body resends and queueing. No file
 * name, id, URL, error text or user identifier.
 * `clientPlatform` and `appVersion` are added by the sender.
 */
@RaftCoreInternalApi const val RaftAttachmentUploadFinishedTraceName = "slock.attachment.upload_finished"

@RaftCoreInternalApi const val RaftAttachmentUploadRouteLegacy = "legacy"
@RaftCoreInternalApi const val RaftAttachmentUploadRouteDirect = "direct"
@RaftCoreInternalApi const val RaftAttachmentUploadRouteUnknown = "unknown"

/** The web's `AttachmentUploadPhase` values the app can tell apart. */
@RaftCoreInternalApi const val RaftAttachmentUploadPhaseCreate = "create"
@RaftCoreInternalApi const val RaftAttachmentUploadPhaseTransfer = "transfer"
@RaftCoreInternalApi const val RaftAttachmentUploadPhaseResponse = "response"
@RaftCoreInternalApi const val RaftAttachmentUploadPhaseComplete = "complete"
@RaftCoreInternalApi const val RaftAttachmentUploadPhaseStatus = "status"

/** The event's `cancel_reason` values. */
@RaftCoreInternalApi enum class RaftAttachmentUploadCancelReason(val wire: String) {
    /** The user removed the tile. */
    UserRemoved("user_removed"),

    /** The draft was sent. */
    DraftSent("draft_sent"),

    /** The draft was cleared without being sent. */
    DraftCleared("draft_cleared"),

    /** Sign-out, or the server is no longer available. */
    SessionEnded("session_ended"),

    /** A cancel that carried none of the above; [RaftUploadCancelled.site] says where it came from. */
    Other("other"),
}

/** Cancel sites that report `other`: short stable identifiers for the diagnostics line, never text. */
@RaftCoreInternalApi const val RaftUploadCancelSiteOrphanRecovery = "orphan_recovery"
@RaftCoreInternalApi const val RaftUploadCancelSiteFailAttempts = "fail_attempts"
/** The task's job was cancelled with no [RaftUploadCancelled] (its scope was cancelled, for example). */
@RaftCoreInternalApi const val RaftUploadCancelSiteUntagged = "untagged"
/** The upload itself returned the `cancelled` code, so no canceller was involved. */
@RaftCoreInternalApi const val RaftUploadCancelSiteResult = "upload_result"

/**
 * The cause a composer upload's job is cancelled with (`job.cancel(RaftUploadCancelled(...))`), so the
 * attempt's event can say why ([RaftAttachmentUploadAttempt.reportCancellation]). [site] names the
 * cancelling code path; it is only written (to the device log) for [RaftAttachmentUploadCancelReason.Other].
 */
@RaftCoreInternalApi class RaftUploadCancelled(
    val reason: RaftAttachmentUploadCancelReason,
    val site: String = reason.wire,
) : CancellationException("attachment upload cancelled: ${reason.wire}")

/** The first [RaftUploadCancelled] in [causes] or their cause chains (a copied exception keeps the original as its cause). */
@RaftCoreInternalApi fun raftUploadCancelledIn(vararg causes: Throwable?): RaftUploadCancelled? {
    for (cause in causes) {
        var current = cause
        var depth = 0
        while (current != null && depth < 8) {
            if (current is RaftUploadCancelled) return current
            current = current.cause
            depth += 1
        }
    }
    return null
}

/**
 * One upload attempt's timing, filled in by the network layer as the upload runs. The composer puts
 * it in the coroutine context of the upload ([kotlin.coroutines.coroutineContext]); the network
 * client reads it there, so no repository signature carries telemetry. An upload started elsewhere
 * has none and records nothing.
 *
 * It also owns the attempt's trace id: the API requests of the upload (the multipart POST, the
 * session create, complete, status and cancel calls) send [traceHeaders], so their
 * `server.http.request` spans join the trace id the event carries. The presigned PUT to the object
 * store sends no `traceparent`.
 */
@RaftCoreInternalApi class RaftAttachmentUploadTimingRecorder(
    /** Milliseconds since the picked files reached the composer. */
    private val elapsedMs: () -> Long,
    val traceIds: RaftTraceIds = newRaftTraceIds(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RaftAttachmentUploadTimingRecorder>

    @RaftCoreInternalApi class Snapshot(
        val route: String?,
        /** First upload request handed over, less the control calls timed before it (this attempt's probe). */
        val prepareMs: Long?,
        val facts: RaftUploadWireFacts?,
        /** Times the body was sent in this attempt, over every call of the transport. */
        val bodySends: Int,
        /** Bytes the last send's progress reports had reached, and the span between its first and last report. */
        val bytesSent: Long?,
        val progressSpanMs: Long?,
        /** The control call that timed out last, unless a later call of the attempt answered. */
        val controlTimeoutPhase: String?,
        /** `control_ms`: the timed control calls' summed time; null when none was reached. */
        val controlMs: Long? = null,
    )

    private val lock = SynchronizedObject()
    private var route: String? = null
    private var prepareMs: Long? = null
    private var facts: RaftUploadWireFacts? = null
    private var bodySends = 0
    private var bytesSent: Long? = null
    private var firstProgressAtMs: Long? = null
    private var lastProgressAtMs: Long? = null
    private var controlTimeoutPhase: String? = null
    private var controlMs: Long? = null
    /** Control time recorded before the first upload request: the probe, which is not preparation. */
    private var controlBeforePrepareMs = 0L

    private fun now(): Long? = runCatching(elapsedMs).getOrNull()

    fun traceHeaders(): Map<String, String> = mapOf("traceparent" to traceIds.traceparent)

    /** The first request of the upload is handed to the network client: preparation ends here (first call only). */
    fun uploadRequestStarting(route: String) {
        val at = now()
        synchronized(lock) {
            this.route = route
            if (prepareMs == null) prepareMs = at
        }
    }

    /** A control call of the upload (create / complete / status) ended; [timedOut] when it got no answer in time. */
    fun controlCallFinished(phase: String, timedOut: Boolean) {
        synchronized(lock) { controlTimeoutPhase = if (timedOut) phase else null }
    }

    /** A control call counted in `control_ms` starts ([raftUploadControlCall]): its start on the attempt's clock. */
    fun controlCallStarting(): Long? = now()

    /** That control call ended, answered, failed or cancelled: its time is added to `control_ms`. */
    fun controlCallTimed(startedAtMs: Long?) {
        val endedAt = now()
        synchronized(lock) {
            val spent = if (startedAtMs != null && endedAt != null) (endedAt - startedAtMs).coerceAtLeast(0L) else 0L
            controlMs = (controlMs ?: 0L) + spent
            if (prepareMs == null) controlBeforePrepareMs += spent
        }
    }

    /** The body is handed to the transport (again): the progress of an earlier send no longer counts. */
    fun transferStarting(route: String) {
        synchronized(lock) {
            this.route = route
            bytesSent = null
            firstProgressAtMs = null
            lastProgressAtMs = null
        }
    }

    /** The transport reported [bytes] of the body sent so far. */
    fun transferProgress(bytes: Long) {
        val at = now()
        synchronized(lock) {
            bytesSent = bytes.coerceAtLeast(0L)
            if (firstProgressAtMs == null) firstProgressAtMs = at
            lastProgressAtMs = at
        }
    }

    /** The body's request ended (answered or failed); a later send replaces an earlier one. */
    fun transferFinished(route: String, facts: RaftUploadWireFacts?) {
        synchronized(lock) {
            this.route = route
            this.facts = facts
            bodySends += (facts?.sends ?: 1).coerceAtLeast(1)
            controlTimeoutPhase = null
        }
    }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            val first = firstProgressAtMs
            val last = lastProgressAtMs
            Snapshot(
                route = route,
                prepareMs = prepareMs?.let { (it - controlBeforePrepareMs).coerceAtLeast(0L) },
                facts = facts,
                bodySends = bodySends,
                bytesSent = bytesSent,
                progressSpanMs = if (first != null && last != null) (last - first).coerceAtLeast(0L) else null,
                controlTimeoutPhase = controlTimeoutPhase,
                controlMs = controlMs,
            )
        }
}

/**
 * Runs one control call of a composer upload, adding its time to the attempt's `control_ms` whether it
 * answers, fails or is cancelled (as the web's `timeControlCall`). Only the first attempt of each call
 * counts: pass [counted] false for a retry or a recovery call. Without a recorder in the coroutine
 * context (an upload started elsewhere, a prefetch) it just runs [call].
 */
@RaftCoreInternalApi suspend inline fun <T> raftUploadControlCall(counted: Boolean = true, call: () -> T): T {
    val recorder = if (counted) currentCoroutineContext()[RaftAttachmentUploadTimingRecorder] else null
    val startedAt = recorder?.controlCallStarting()
    try {
        return call()
    } finally {
        recorder?.controlCallTimed(startedAt)
    }
}

@RaftCoreInternalApi data class RaftAttachmentUploadPhases(
    val prepareMs: Long? = null,
    val connectMs: Long? = null,
    val requestBodyMs: Long? = null,
    val responseWaitMs: Long? = null,
    val controlMs: Long? = null,
)

/**
 * The phases as reported: a phase that was not measured stays null, the response wait needs a body
 * time, and together they never exceed [durationMs] (the engine's clock and the app's are read
 * separately, so each phase is capped by what is left of the total; the two on the app's own clock,
 * preparation and the control calls, first).
 */
@RaftCoreInternalApi fun raftAttachmentUploadPhases(
    durationMs: Long,
    prepareMs: Long?,
    facts: RaftUploadWireFacts?,
    controlMs: Long? = null,
): RaftAttachmentUploadPhases {
    var left = durationMs.coerceAtLeast(0L)
    fun capped(value: Long?): Long? {
        if (value == null || value < 0L) return null
        val kept = minOf(value, left)
        left -= kept
        return kept
    }
    val prepare = capped(prepareMs)
    val control = capped(controlMs)
    val connect = capped(facts?.connectMs)
    val body = capped(facts?.requestBodyMs)
    val wait = if (body == null) null else capped(facts?.responseWaitMs)
    return RaftAttachmentUploadPhases(
        prepareMs = prepare,
        connectMs = connect,
        requestBodyMs = body,
        responseWaitMs = wait,
        controlMs = control,
    )
}

@RaftCoreInternalApi data class RaftAttachmentUploadFinished(
    val route: String?,
    /** The app's error code ([ScreenError.code] / [PermissionBlocker.code]); null for a success. */
    val errorCode: String? = null,
    /** The server's contract code of the refusal, when it sent one. */
    val serverCode: String? = null,
    val cancelled: Boolean = false,
    val attempt: Int = 1,
    /** The size of the picked file, used when the transport reported none. */
    val sizeBytes: Long? = null,
    val durationMs: Long,
    val prepareMs: Long? = null,
    val facts: RaftUploadWireFacts? = null,
    val bodySends: Int = 0,
    val bytesSent: Long? = null,
    val progressSpanMs: Long? = null,
    val controlTimeoutPhase: String? = null,
    /** `control_ms` ([RaftAttachmentUploadTimingRecorder.Snapshot.controlMs]); null when no control call was reached. */
    val controlMs: Long? = null,
    /** [RaftAttachmentUploadCancelReason.wire]; written only when the outcome is `cancelled` (`other` when null). */
    val cancelReason: String? = null,
)

/** Web `directPutSizeBucket`. */
@RaftCoreInternalApi fun raftAttachmentUploadSizeBucket(bytes: Long): String {
    val mb = 1024L * 1024L
    return when {
        bytes < 10L * mb -> "<10MB"
        bytes < 50L * mb -> "10-50MB"
        bytes < 200L * mb -> "50-200MB"
        else -> ">200MB"
    }
}

/** Web `directPutElapsedBucket`. */
@RaftCoreInternalApi fun raftAttachmentUploadElapsedBucket(ms: Long): String =
    when {
        ms < 1_000L -> "<1s"
        ms < 10_000L -> "1-10s"
        ms < 60_000L -> "10-60s"
        ms < 300_000L -> "1-5m"
        else -> ">5m"
    }

/**
 * Which step ran out of time, as the web's `timeout_phase`: the body's request when the transport
 * classified its failure as a timeout (`response` once every byte was sent, else `transfer`), else the
 * control call that timed out last. The web's `channel` and `capability` do not occur: the app
 * resolves no channel inside an upload attempt, and a failed capability probe (cached, and often
 * fetched before the attempt) falls back to multipart instead of ending the upload.
 */
@RaftCoreInternalApi fun raftAttachmentUploadTimeoutPhase(facts: RaftUploadWireFacts?, controlTimeoutPhase: String?): String? =
    when {
        facts?.failureKind == RaftTransportFailureKind.TIMEOUT ->
            if (facts?.requestBodyMs != null) RaftAttachmentUploadPhaseResponse else RaftAttachmentUploadPhaseTransfer
        else -> controlTimeoutPhase
    }

/** The app's own codes for "a request of the upload ran out of time". */
private val RaftAttachmentUploadTimeoutAppCodes = setOf("network_timeout", "attachment_presigned_upload_timeout")

/**
 * `ok` / `error` / `timeout` / `cancelled`, the web's closed set. A failed upload is a `timeout` when
 * the transport said so ([timeoutPhase]) or the app's code is one of its two timeout codes; no error
 * text is searched.
 */
@RaftCoreInternalApi fun raftAttachmentUploadOutcome(errorCode: String?, cancelled: Boolean, timeoutPhase: String? = null): String =
    when {
        cancelled || errorCode == "cancelled" -> "cancelled"
        errorCode == null -> "ok"
        timeoutPhase != null || errorCode in RaftAttachmentUploadTimeoutAppCodes -> "timeout"
        else -> "error"
    }

/**
 * The upload contract's codes: what the server answers on the upload routes and what the web's client
 * reports on this event (botiverse/slock `packages/server`, `packages/web/src/utils/directAttachmentUpload.ts`
 * at 080597aea).
 */
@RaftCoreInternalApi val RaftAttachmentUploadContractCodes: Set<String> =
    setOf(
        "UPLOAD_STALLED", "UPLOAD_OBJECT_PUT_UNCERTAIN", "UPLOAD_OBJECT_PUT_FAILED", "UPLOAD_OBJECT_NOT_FOUND",
        "UPLOAD_OBJECT_MISMATCH", "UPLOAD_VERIFICATION_IN_PROGRESS", "UPLOAD_COMPLETE_FAILED",
        "UPLOAD_SESSION_CREATE_FAILED", "UPLOAD_SESSION_STATUS_FAILED", "UPLOAD_SESSION_EXPIRED",
        "UPLOAD_SESSION_CANCELED", "UPLOAD_SESSION_NOT_FOUND", "UPLOAD_INVALID_REQUEST", "UPLOAD_TOO_LARGE",
        "UPLOAD_FORBIDDEN", "UPLOAD_RATE_LIMITED", "UPLOAD_IDEMPOTENCY_CONFLICT", "UPLOAD_QUOTA_EXCEEDED",
        "FILE_UPLOAD_QUOTA_EXCEEDED", "ATTACHMENT_UPLOAD_DISABLED", "ATTACHMENT_TOO_LARGE",
    )

/** App conditions the web names with a contract code. */
private val RaftAttachmentUploadAppToContractCodes =
    mapOf(
        "attachment_too_large" to "UPLOAD_TOO_LARGE",
        "attachment_upload_file_empty" to "UPLOAD_INVALID_REQUEST",
    )

/** The app's codes for "the request got no HTTP answer"; the platform's error name replaces them when known. */
private val RaftAttachmentUploadTransportAppCodes =
    setOf("network_unavailable", "network_connect_failed", "network_dns_failed")

/**
 * The upload's task was ended from below while nobody cancelled it (the network client was retired
 * under it): the tile's own code for it (`ThreadComposerUploadInterruptedErrorCode`).
 */
@RaftCoreInternalApi const val RaftAttachmentUploadInterruptedCode = "upload_interrupted"

/** The app's own upload codes that are reported as they are. */
@RaftCoreInternalApi val RaftAttachmentUploadAppCodes: Set<String> =
    RaftAttachmentUploadTransportAppCodes +
        setOf(
            "network_request_failed", "network_parse_failed", "server_unavailable", "server_forbidden", "auth_required", "auth_authority_changed",
            "empty_upload_result", "attachment_upload_failed", "attachment_upload_unavailable", "attachment_read_failed",
            "attachment_size_unavailable", "attachment_size_mismatch", "attachment_file_input_conflict",
            "attachment_direct_upload_unavailable", "attachment_upload_capabilities_unavailable",
            "attachment_upload_file_preflight_invalid", "attachment_upload_session_create_failed",
            "attachment_upload_session_put_failed", "attachment_upload_session_complete_failed",
            "attachment_upload_session_contract_invalid", "attachment_upload_session_empty_body",
            "attachment_upload_session_invalid_request", "attachment_upload_authority_binding_required",
            "attachment_upload_authority_binding_unsupported", "attachment_upload_method_invalid",
            "attachment_upload_contract_invalid", "file_ref_upload_requires_app_scope_file_resolver",
            "missing_channel_id", RaftAttachmentUploadInterruptedCode,
        )

private val RaftAttachmentUploadHttpCodePattern = Regex("""^http_[1-5][0-9]{2}$""")

/** `okhttp:<class>` / `ktor:<class>` / `<NSError domain>:<code>` as [build.raft.app.network.raftTransportErrorToken] builds them. */
private val RaftAttachmentUploadTransportErrorPattern = Regex("""^[A-Za-z][A-Za-z0-9]{1,39}:(?:[A-Za-z][A-Za-z0-9]{1,47}|-?[0-9]{1,6})$""")

/**
 * The event's `error_code`: always one of a bounded set, never error text. In order:
 * 1. a `timeout`: `UPLOAD_OBJECT_PUT_UNCERTAIN` when that is the app's result, else `UPLOAD_STALLED`
 *    (as the web; `timeout_phase` says which step);
 * 2. the server's contract code, when it is one of [RaftAttachmentUploadContractCodes];
 * 3. the app's code when it is itself a contract code, or names a condition the web has a code for
 *    (`attachment_too_large` -> `UPLOAD_TOO_LARGE`, `attachment_upload_file_empty` -> `UPLOAD_INVALID_REQUEST`);
 * 4. a request that got no HTTP answer: the platform's error name from the transport
 *    (`okhttp:SocketTimeoutException`, `NSURLErrorDomain:-1005`, `ktor:HttpRequestTimeoutException`),
 *    else the app's `network_unavailable` / `network_connect_failed` / `network_dns_failed`;
 * 5. `http_<status>`;
 * 6. one of [RaftAttachmentUploadAppCodes];
 * 7. anything else: `other`.
 * Null for `ok` and `cancelled`.
 */
@RaftCoreInternalApi fun raftAttachmentUploadErrorCode(
    outcome: String,
    errorCode: String?,
    serverCode: String?,
    transportError: String?,
): String? {
    if (outcome != "error" && outcome != "timeout") return null
    val contractServerCode = serverCode?.trim()?.uppercase()?.takeIf { it in RaftAttachmentUploadContractCodes }
    if (outcome == "timeout") {
        return if (errorCode == "UPLOAD_OBJECT_PUT_UNCERTAIN" || contractServerCode == "UPLOAD_OBJECT_PUT_UNCERTAIN") {
            "UPLOAD_OBJECT_PUT_UNCERTAIN"
        } else {
            "UPLOAD_STALLED"
        }
    }
    if (contractServerCode != null) return contractServerCode
    val code = errorCode ?: return RAFT_TRANSPORT_ERROR_OTHER
    if (code in RaftAttachmentUploadContractCodes) return code
    RaftAttachmentUploadAppToContractCodes[code]?.let { return it }
    if (code in RaftAttachmentUploadTransportAppCodes) {
        val native = transportError?.takeIf { it != RAFT_TRANSPORT_ERROR_OTHER && RaftAttachmentUploadTransportErrorPattern.matches(it) }
        return native ?: code
    }
    if (RaftAttachmentUploadHttpCodePattern.matches(code)) return code
    if (code in RaftAttachmentUploadAppCodes) return code
    return RAFT_TRANSPORT_ERROR_OTHER
}

@RaftCoreInternalApi fun raftAttachmentUploadFinishedAttrs(finished: RaftAttachmentUploadFinished): JsonObject {
    val durationMs = finished.durationMs.coerceAtLeast(0L)
    val facts = finished.facts
    val timeoutPhase = raftAttachmentUploadTimeoutPhase(facts, finished.controlTimeoutPhase)
    val outcome = raftAttachmentUploadOutcome(finished.errorCode, finished.cancelled, timeoutPhase)
    val errorCode = raftAttachmentUploadErrorCode(outcome, finished.errorCode, finished.serverCode, facts?.transportError)
    val phases = raftAttachmentUploadPhases(durationMs, finished.prepareMs, facts, finished.controlMs)
    // The file actually sent, when the transport reported its length; else the picked file's.
    val requestBytes = facts?.requestBytes?.takeIf { it > 0L }
    val sizeBytes = requestBytes ?: finished.sizeBytes?.takeIf { it > 0L }
    val bodyUnfinished = outcome != "ok" && phases.requestBodyMs == null
    return buildJsonObject {
        put("route", finished.route ?: RaftAttachmentUploadRouteUnknown)
        put("outcome", outcome)
        if (outcome == "cancelled") put("cancel_reason", finished.cancelReason ?: RaftAttachmentUploadCancelReason.Other.wire)
        if (outcome == "timeout") timeoutPhase?.let { put("timeout_phase", it) }
        errorCode?.let { put("error_code", it) }
        put("attempt", finished.attempt.coerceAtLeast(1))
        sizeBytes?.let { put("size_bucket", raftAttachmentUploadSizeBucket(it)) }
        put("elapsed_bucket", raftAttachmentUploadElapsedBucket(durationMs))
        put("duration_ms", durationMs)
        phases.prepareMs?.let { put("prepare_ms", it) }
        phases.connectMs?.let { put("connect_ms", it) }
        phases.requestBodyMs?.let { put("request_body_ms", it) }
        phases.responseWaitMs?.let { put("response_wait_ms", it) }
        phases.controlMs?.let { put("control_ms", it) }
        if (bodyUnfinished) {
            (facts?.requestBodyPartialMs ?: finished.progressSpanMs)
                ?.takeIf { it >= 0L }
                ?.let { put("request_body_partial_ms", minOf(it, durationMs)) }
            finished.bytesSent
                ?.takeIf { it >= 0L }
                // Progress counts multipart framing too; never more than the file itself.
                ?.let { sent -> put("request_bytes_sent", if (sizeBytes != null) minOf(sent, sizeBytes) else sent) }
        }
        requestBytes?.let { put("request_bytes", it) }
        if (finished.bodySends > 1) put("body_sends", finished.bodySends)
        facts?.edgePop?.let { put("edge_pop", it) }
    }
}

/** The same facts as one diagnostics line (every build, on device). */
@RaftCoreInternalApi fun raftAttachmentUploadFinishedLogLine(attrs: JsonObject, traceId: String): String =
    "thread_composer_attachment_upload_finished " +
        attrs.entries.joinToString(" ") { (name, value) -> "$name=${value.toString().trim('"')}" } +
        " traceId=$traceId"

/** The one extra diagnostics line of a `cancelled` event whose `cancel_reason` is `other`: where the cancel came from. */
@RaftCoreInternalApi fun raftAttachmentUploadCancelOtherLogLine(site: String, traceId: String): String =
    "thread_composer_attachment_upload_cancel_other site=$site traceId=$traceId"

/**
 * One composer upload attempt's report: owns the attempt's [recorder] and emits its event exactly
 * once. The server the event is filed under is the one the attempt started on, and the outcome is the
 * upload's own result when it has one ([settle]): a scope cancelled as the upload returns, or a server
 * switch while it ran, changes neither.
 */
@RaftCoreInternalApi class RaftAttachmentUploadAttempt(
    /** The server this attempt was started on; without one the event is only logged. */
    private val serverId: String?,
    private val attempt: Int,
    private val pickedSizeBytes: Long?,
    private val elapsedMs: () -> Long,
    private val log: (String) -> Unit,
    private val emit: (serverId: String, attrs: JsonObject, traceId: String, spanId: String) -> Unit =
        RaftClientTraceEvents::emitAttachmentUploadFinished,
) {
    private class Settled(val errorCode: String?, val serverCode: String?)

    val recorder = RaftAttachmentUploadTimingRecorder(elapsedMs)

    private val lock = SynchronizedObject()
    private var settled: Settled? = null
    private var reported = false

    /** The upload returned [result]. The first result stands. */
    fun settle(result: DataResult<*>) {
        val next =
            when (result) {
                is DataResult.Content -> Settled(errorCode = null, serverCode = null)
                is DataResult.Error -> Settled(result.error.code, result.error.serverCode)
                is DataResult.NoPermission -> Settled(result.blocker.code, result.blocker.serverCode)
                is DataResult.Empty -> Settled("empty_upload_result", serverCode = null)
            }
        synchronized(lock) { if (settled == null) settled = next }
    }

    /**
     * The upload's task ended with a CancellationException and no result. [taskCancelled]: its own job
     * was cancelled, which only the tile's removal, the draft being sent or cleared, or sign-out /
     * losing the server do; that is `cancelled`, and its `cancel_reason` is the [RaftUploadCancelled]
     * found in [causes] (the exception that ended the task, the job's own cancellation), else `other`.
     * Otherwise the exception came from below while the task was still wanted: an `error` with
     * [RaftAttachmentUploadInterruptedCode], never `cancelled`.
     */
    fun reportCancellation(taskCancelled: Boolean, vararg causes: Throwable?) {
        if (taskCancelled) {
            report(cancelled = true, cancellation = raftUploadCancelledIn(*causes))
        } else {
            report(failureCode = RaftAttachmentUploadInterruptedCode)
        }
    }

    /**
     * The upload task's `catch` of [caught] (a CancellationException, no result yet), run in the
     * task's coroutine: [reportCancellation] with whether the task's own job was cancelled, and the
     * [RaftUploadCancelled] its canceller passed, read from [caught] and from the job itself.
     */
    suspend fun reportTaskCancellation(caught: CancellationException) {
        val context = currentCoroutineContext()
        val taskCancelled = !context.isActive
        val jobCancellation = if (taskCancelled) runCatching { context.ensureActive() }.exceptionOrNull() else null
        reportCancellation(taskCancelled, caught, jobCancellation)
    }

    /**
     * Emits the attempt's event, once. A settled upload reports its result whatever [cancelled] says;
     * an unsettled one is `cancelled` (why: [cancellation], `other` without one), or failed with
     * [failureCode]. Never throws.
     */
    fun report(
        cancelled: Boolean = false,
        failureCode: String = "attachment_upload_failed",
        cancellation: RaftUploadCancelled? = null,
    ) {
        val result =
            synchronized(lock) {
                if (reported) return
                reported = true
                settled
            }
        runCatching {
            val timing = recorder.snapshot()
            val attrs =
                raftAttachmentUploadFinishedAttrs(
                    RaftAttachmentUploadFinished(
                        route = timing.route,
                        errorCode = if (result != null) result.errorCode else if (cancelled) null else failureCode,
                        serverCode = result?.serverCode,
                        cancelled = result == null && cancelled,
                        attempt = attempt,
                        sizeBytes = pickedSizeBytes,
                        durationMs = elapsedMs(),
                        prepareMs = timing.prepareMs,
                        facts = timing.facts,
                        bodySends = timing.bodySends,
                        bytesSent = timing.bytesSent,
                        progressSpanMs = timing.progressSpanMs,
                        controlTimeoutPhase = timing.controlTimeoutPhase,
                        controlMs = timing.controlMs,
                        cancelReason = if (result == null) cancellation?.reason?.wire else null,
                    )
                )
            log(raftAttachmentUploadFinishedLogLine(attrs, recorder.traceIds.traceId))
            if (attrs["cancel_reason"]?.jsonPrimitive?.content == RaftAttachmentUploadCancelReason.Other.wire) {
                val site =
                    when {
                        result != null -> RaftUploadCancelSiteResult
                        else -> cancellation?.site ?: RaftUploadCancelSiteUntagged
                    }
                log(raftAttachmentUploadCancelOtherLogLine(site, recorder.traceIds.traceId))
            }
            val server = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return@runCatching
            emit(server, attrs, recorder.traceIds.traceId, recorder.traceIds.spanId)
        }
    }
}
