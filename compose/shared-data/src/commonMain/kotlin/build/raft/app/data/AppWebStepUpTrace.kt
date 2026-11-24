package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.RaftTraceIds
import build.raft.app.network.newRaftTraceIds
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `mobile.auth.web_step_up` (#160): one span per web step-up attempt (AppWebStepUp.kt), so how often
 * it succeeds, and at which step it fails, can be read per platform and app version once
 * `app_web_step_up_v1` is on for everyone. It goes through the client-trace pipeline as
 * `mobile.realtime.session_health` does ([RaftClientTraceEvents.emitSpan], which adds `clientPlatform`
 * and `appVersion`; privacy mode permitting), under the signed-in shell's server, and is never
 * sampled (`sample_rate` 1).
 *
 * It starts when the person starts the web step-up (Continue in browser, or Verify with your passkey
 * in the browser beside the code) and ends when the refused action's retry has its answer, or when
 * the attempt ends otherwise. Its start and complete requests carry the span's `traceparent`
 * ([AppWebStepUpRequestTrace]), so the server's request spans join its trace; the page's URL carries
 * nothing of it.
 *
 * Attributes:
 * - `trigger`: [AppWebStepUpTrigger]. `action`: the `StepUpAction` key the start sends.
 * - `outcome`: [AppWebStepUpOutcome].
 * - `failed_step` (only with `failed`): [AppWebStepUpStep]. `http_status`: the status that step's
 *   request got (start, complete; absent without an answer). `error_code`: the refusal's `code` when
 *   the answer had one, else the app's reason: the start or complete result's code (`http_502`,
 *   `http_0` when no answer came, `network`, `app_web_step_up_contract_mismatch`, ...),
 *   `url_not_allowed`, the web session's reason (`as:<code>`, `start_refused`, ...), the callback's
 *   (`expired`, `expired_local`, `stale`, `malformed`, `callback_error`), or the retried action's
 *   error code.
 * - `start_ms` (POST step-up/start), `browser_ms` (the page handed to the web session until it called
 *   back, closed or failed), `complete_ms` (POST step-up/complete): each once that phase ended.
 * - `sample_rate`: 1.0.
 *
 * Codes, statuses and durations only: never the request id, the page's URL, the callback's code, the
 * PKCE values, a token or an email.
 *
 * `abandoned`: the attempt was under way when the action that asked for it went away (the dialog's
 * page or store closed: [SecondFactorStepUpCoordinator]), or when the app was killed with the page up.
 * A killed app sends nothing, so the request it left on file ([AppWebStepUpPending.trace]) is reported
 * at the next sign-in ([reportAppWebStepUpLeftover]), ending where the app last saw it: the page opening.
 */
@RaftCoreInternalApi const val AppWebStepUpSpanName = "mobile.auth.web_step_up"

/** `trigger`: which offer of the web step-up the person took. */
@RaftCoreInternalApi enum class AppWebStepUpTrigger(val wire: String) {
    /** Only older passkeys and no code: the dialog goes straight to the browser (Continue in browser). */
    LegacyOnly("legacy_only"),

    /** Verify with your passkey in the browser, under the code's field (#158). */
    BesideCode("beside_code"),
}

@RaftCoreInternalApi enum class AppWebStepUpOutcome(val wire: String) {
    /** This sign-in was verified (or two-step verification had gone off) and the action answered. */
    Completed("completed"),

    /** The person closed the page, cancelled on it, or closed the dialog. */
    Cancelled("cancelled"),
    Failed("failed"),

    /** No answer ever came: the action that asked went away, or the app was killed. */
    Abandoned("abandoned"),
}

/** `failed_step`. */
@RaftCoreInternalApi enum class AppWebStepUpStep(val wire: String) {
    /** POST step-up/start. */
    Start("start"),

    /** The web session could not show the page. */
    Browser("browser"),

    /** The page's callback: expired, unusable, or for another request. */
    Callback("callback"),

    /** POST step-up/complete. */
    Complete("complete"),

    /** The refused action, sent again after the step-up. */
    Retry("retry"),
}

/** The attempts' monotonic clock (one origin per process). */
@RaftCoreInternalApi object AppWebStepUpClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}

/** Where the attempts' spans go, with their clocks and ids; tests pass their own. */
@RaftCoreInternalApi class AppWebStepUpTracer(
    val nowMs: () -> Long = AppWebStepUpClock::nowMs,
    val nowEpochMs: () -> Long = ::defaultNowMillis,
    val newTraceIds: () -> RaftTraceIds = { newRaftTraceIds() },
    private val emit: (RaftWebSpanRecord) -> Unit = ::emitAppWebStepUpSpan,
) {
    fun send(span: RaftWebSpanRecord) {
        try {
            emit(span)
        } catch (_: Throwable) {
            // Telemetry never affects the step-up.
        }
    }
}

/**
 * The coroutine context of an attempt's start and complete requests ([AppWebStepUpAttempt.request]),
 * as [InboxFetchReasonContext] carries its header, so the repository and its callers keep their
 * signatures: [AppWebStepUpRepository] sends [traceparent] and notes what came back ([answered]) for
 * the span. Nothing else of the request is kept.
 */
@RaftCoreInternalApi class AppWebStepUpRequestTrace(val traceparent: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AppWebStepUpRequestTrace>

    /** The answer's HTTP status (0: none came); null until the request ended. */
    var status: Int? = null
        private set

    /** The refusal's `code`, when the answer had one. */
    var errorCode: String? = null
        private set

    fun headers(): Map<String, String> = mapOf("traceparent" to traceparent)

    fun answered(status: Int, errorCode: String?) {
        this.status = status
        this.errorCode = errorCode
    }
}

/**
 * What a step-up request on file ([AppWebStepUpPending]) keeps of its attempt, so the next launch can
 * still report it if the app is killed with the page up: the span's ids, `trigger`, `action`, when
 * it started (epoch ms) and `start_ms`. Nothing the span itself does not carry.
 */
@Serializable
@RaftCoreInternalApi data class AppWebStepUpPendingTrace(
    val traceId: String,
    val spanId: String,
    val trigger: String,
    val action: String,
    val startedAt: Long,
    val startMs: Long? = null,
)

/**
 * One web step-up attempt ([AppWebStepUpSpanName]), owned by [SecondFactorStepUpCoordinator]: its
 * phases are timed on [AppWebStepUpTracer.nowMs] and its span is sent once, when it ends.
 */
@RaftCoreInternalApi class AppWebStepUpAttempt(
    private val trigger: AppWebStepUpTrigger,
    private val action: String,
    private val tracer: AppWebStepUpTracer,
) {
    private val ids: RaftTraceIds = tracer.newTraceIds()
    private val startedAtMs = tracer.nowMs()
    private val startedAtEpochMs = tracer.nowEpochMs()
    private var startMs: Long? = null
    private var completeMs: Long? = null
    private var browserOpenedAtMs: Long? = null
    private var browserMs: Long? = null
    private var startAnswer: AppWebStepUpRequestTrace? = null
    private var completeAnswer: AppWebStepUpRequestTrace? = null
    private var ended = false

    /** [step]'s request ([AppWebStepUpStep.Start] or [AppWebStepUpStep.Complete]): timed, and sent in this span's trace. */
    suspend fun <T> request(step: AppWebStepUpStep, block: suspend () -> T): T {
        val trace = AppWebStepUpRequestTrace(ids.traceparent)
        val at = tracer.nowMs()
        try {
            return withContext(trace) { block() }
        } finally {
            val spent = (tracer.nowMs() - at).coerceAtLeast(0L)
            if (step == AppWebStepUpStep.Complete) {
                completeMs = spent
                completeAnswer = trace
            } else {
                startMs = spent
                startAnswer = trace
            }
        }
    }

    /** The page is handed to the dialog's web session. */
    fun browserOpened() {
        browserOpenedAtMs = tracer.nowMs()
    }

    /** The web session called back, closed or failed: `browser_ms`, the first time only. */
    fun browserReturned() {
        val openedAt = browserOpenedAtMs ?: return
        if (browserMs == null) browserMs = (tracer.nowMs() - openedAt).coerceAtLeast(0L)
    }

    /** What the request on file keeps of this attempt ([AppWebStepUpPending.trace]). */
    fun onFile(): AppWebStepUpPendingTrace =
        AppWebStepUpPendingTrace(ids.traceId, ids.spanId, trigger.wire, action, startedAtEpochMs, startMs)

    /** The refused action's retry answered: completed, or failed with the action's error code. */
    fun retried(result: DataResult<*>) {
        when (result) {
            is DataResult.Content<*>,
            is DataResult.Empty -> end(AppWebStepUpOutcome.Completed)
            is DataResult.Error -> fail(AppWebStepUpStep.Retry, result.error.serverCode ?: result.error.code)
            is DataResult.NoPermission -> fail(AppWebStepUpStep.Retry, result.blocker.serverCode ?: result.blocker.code)
        }
    }

    /** Failed at [step]; [reason] is the app's code, used when that step's answer had none. */
    fun fail(step: AppWebStepUpStep, reason: String) = end(AppWebStepUpOutcome.Failed, step, reason)

    /** Ends the attempt now and sends its span (once). */
    fun end(outcome: AppWebStepUpOutcome, failedStep: AppWebStepUpStep? = null, reason: String? = null) {
        if (ended) return
        ended = true
        val answer =
            when (failedStep) {
                AppWebStepUpStep.Start -> startAnswer
                AppWebStepUpStep.Complete -> completeAnswer
                else -> null
            }
        tracer.send(
            appWebStepUpSpan(
                ids = ids,
                startEpochMs = startedAtEpochMs,
                endEpochMs = startedAtEpochMs + (tracer.nowMs() - startedAtMs).coerceAtLeast(0L),
                trigger = trigger.wire,
                action = action,
                outcome = outcome,
                failedStep = failedStep.takeIf { outcome == AppWebStepUpOutcome.Failed },
                httpStatus = answer?.status?.takeIf { it > 0 },
                errorCode = answer?.errorCode ?: reason,
                startMs = startMs,
                browserMs = browserMs,
                completeMs = completeMs,
            )
        )
    }
}

/**
 * At sign-in ([RaftAppWebStepUp.onSignedIn]), when a span can be sent: a step-up request an earlier
 * launch left on file (the app was killed with the page up; no dialog outlives that, so it can never
 * finish) becomes one `abandoned` span, from when it started to when its page opened, and is dropped.
 * This launch's own request is never one ([AppWebStepUpPendingStore.leftover]).
 */
@RaftCoreInternalApi fun reportAppWebStepUpLeftover(
    pending: AppWebStepUpPendingStore,
    tracer: AppWebStepUpTracer,
    log: (String) -> Unit = {},
) {
    val leftover = pending.leftover() ?: return
    // Not a request this launch saved meanwhile.
    if (pending.read()?.generation == leftover.generation) pending.clear()
    val trace = leftover.trace
    log("app_web_step_up_abandoned source=earlier_launch traced=${trace != null}")
    tracer.send(
        appWebStepUpSpan(
            ids = trace?.let { RaftTraceIds(traceId = it.traceId, spanId = it.spanId) } ?: tracer.newTraceIds(),
            // A request from before this span existed has only the moment its page opened.
            startEpochMs = trace?.startedAt ?: leftover.startedAt,
            endEpochMs = leftover.startedAt,
            trigger = trace?.trigger,
            action = trace?.action,
            outcome = AppWebStepUpOutcome.Abandoned,
            startMs = trace?.startMs,
        )
    )
}

private val TraceCodePattern = Regex("^[A-Za-z0-9_.:-]{1,64}$")

/** A code or key as an attribute: a short token, never free text (anything else reads `other`). */
private fun traceCode(value: String): String = value.trim().takeIf { TraceCodePattern.matches(it) } ?: "other"

private fun appWebStepUpSpan(
    ids: RaftTraceIds,
    startEpochMs: Long,
    endEpochMs: Long,
    trigger: String?,
    action: String?,
    outcome: AppWebStepUpOutcome,
    failedStep: AppWebStepUpStep? = null,
    httpStatus: Int? = null,
    errorCode: String? = null,
    startMs: Long? = null,
    browserMs: Long? = null,
    completeMs: Long? = null,
): RaftWebSpanRecord {
    val attrs =
        buildMap<String, JsonPrimitive> {
            trigger?.let { put("trigger", JsonPrimitive(traceCode(it))) }
            action?.let { put("action", JsonPrimitive(traceCode(it))) }
            put("outcome", JsonPrimitive(outcome.wire))
            if (failedStep != null) {
                put("failed_step", JsonPrimitive(failedStep.wire))
                httpStatus?.let { put("http_status", JsonPrimitive(it)) }
                errorCode?.let { put("error_code", JsonPrimitive(traceCode(it))) }
            }
            startMs?.let { put("start_ms", JsonPrimitive(it)) }
            browserMs?.let { put("browser_ms", JsonPrimitive(it)) }
            completeMs?.let { put("complete_ms", JsonPrimitive(it)) }
            // Never sampled: every attempt is sent, so readers weight it 1 (docs/observability/trace-sampling.md).
            put("sample_rate", JsonPrimitive(1.0))
        }
    return RaftWebSpanRecord(
        traceId = ids.traceId,
        spanId = ids.spanId,
        name = AppWebStepUpSpanName,
        status = if (outcome == AppWebStepUpOutcome.Failed) "error" else "ok",
        startTime = raftEpochMillisUtcIso(startEpochMs),
        endTime = raftEpochMillisUtcIso(maxOf(startEpochMs, endEpochMs)),
        attrs = JsonObject(attrs),
    )
}

/**
 * Hands [span] to the client-trace pipeline under the signed-in shell's server, read from memory as
 * the frame metrics do ([RaftFrameMetricsTraceReporter]); signed out, nothing. Never throws.
 */
@RaftCoreInternalApi fun emitAppWebStepUpSpan(span: RaftWebSpanRecord) {
    try {
        val serverId =
            RaftDataStoreRegistry.currentOrNull()
                ?.authStore
                ?.readInMemorySessionSnapshot()
                ?.session
                ?.currentServerId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return
        RaftClientTraceEvents.emitSpan(serverId, span, emptyList())
    } catch (_: Throwable) {
        // Telemetry never affects the step-up.
    }
}
