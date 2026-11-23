package build.raft.app.assistant

import build.raft.app.data.RaftAssistantWeChatConnectApi
import build.raft.app.data.ScreenError
import build.raft.app.data.WeChatAttempt
import build.raft.app.data.WeChatAttemptCookie
import build.raft.app.data.WeChatAttemptStatus
import build.raft.app.data.WeChatPollResult
import build.raft.app.data.WeChatRequestFailure
import build.raft.app.data.WeChatStartResult
import build.raft.app.data.WeChatVerifyCodePattern
import build.raft.app.model.ServerId
import build.raft.app.raftLogInfo
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Connecting WeChat from the app (web assistant/useWeChatConnect.ts, botiverse/slock staging
// 34511fe7). Offered only where web offers it, a window past the phone layout (web's
// PHONE_MEDIA_QUERY; here the app's wide breakpoint, [threadAssistantPanelWide]); a phone keeps
// "connect from a computer".

/** Why connecting stopped (web `WeChatConnectFailure`); each has its own sentence and offers a new code. */
internal enum class WeChatConnectFailure {
    /** The QR code's few minutes ran out (here, or WeChat said so). */
    Expired,

    /** The code was spent or replaced (a newer one was made elsewhere). */
    Used,

    /** Too many wrong verification codes. */
    Blocked,

    /** WeChat says this account is already connected. */
    AlreadyBound,

    /** This WeChat account is connected to another assistant in Raft. */
    InUse,

    /** The poll came without this attempt's binding cookie. */
    Unbound,

    /** Too many QR codes in a minute. */
    RateLimited,

    /** WeChat couldn't be reached, or answered unexpectedly. */
    Upstream,

    /** The server no longer offers WeChat here (flag off, no assistant). */
    Unavailable,
    Other,
}

internal enum class WeChatVerifyError { Wrong, Malformed }

/** What the connect dialog shows (web `WeChatConnectStep`). */
internal sealed interface WeChatConnectStep {
    data object Disclosure : WeChatConnectStep

    data object Starting : WeChatConnectStep

    /** The QR code is shown; [scanned] once WeChat says it was scanned (and from then on). */
    data class Qr(val attempt: WeChatAttempt, val scanned: Boolean) : WeChatConnectStep

    /** WeChat asked for the code it shows on the phone. */
    data class Verify(val attempt: WeChatAttempt, val error: WeChatVerifyError?, val checking: Boolean) : WeChatConnectStep

    data object Connected : WeChatConnectStep

    /** [error] carries the refusing request's trace ID; null for a local stop (the code ran out here). */
    data class Failed(val reason: WeChatConnectFailure, val error: ScreenError?) : WeChatConnectStep
}

/** Web `DEFAULT_DELAYS`: the next poll a second after an answer, a refused-for-now one after 3 s. */
internal const val WECHAT_POLL_GAP_MILLIS: Long = 1_000L
internal const val WECHAT_POLL_RETRY_MILLIS: Long = 3_000L

/** The server long-polls WeChat up to 15 s; the poll's request budget must outlast that with room. */
internal const val WECHAT_POLL_MIN_REQUEST_TIMEOUT_MILLIS: Long = 25_000L

/** Web `startFailure`: null means connected after all (`wechat_already_connected`). */
internal fun weChatStartFailure(failure: WeChatRequestFailure): WeChatConnectFailure? =
    when (failure.error.code) {
        "wechat_rate_limited" -> WeChatConnectFailure.RateLimited
        "wechat_already_connected" -> null
        "wechat_upstream_unavailable" -> WeChatConnectFailure.Upstream
        "wechat_unavailable", "assistant_not_found" -> WeChatConnectFailure.Unavailable
        else -> if (failure.httpStatus == 429) WeChatConnectFailure.RateLimited else WeChatConnectFailure.Other
    }

/** Web `pollFailure`'s three outcomes besides a final failure. */
internal sealed interface WeChatPollOutcome {
    data object Retry : WeChatPollOutcome

    data object Malformed : WeChatPollOutcome

    data class Fail(val reason: WeChatConnectFailure) : WeChatPollOutcome
}

/** Web `pollFailure`: no answer, a 5xx, one poll already running or WeChat unreachable are tried again. */
internal fun weChatPollOutcome(failure: WeChatRequestFailure): WeChatPollOutcome =
    when (failure.error.code) {
        "invalid_verify_code" -> WeChatPollOutcome.Malformed
        "wechat_poll_in_flight", "wechat_upstream_unavailable" -> WeChatPollOutcome.Retry
        "wechat_attempt_expired" -> WeChatPollOutcome.Fail(WeChatConnectFailure.Expired)
        "wechat_attempt_used", "wechat_attempt_not_found" -> WeChatPollOutcome.Fail(WeChatConnectFailure.Used)
        "wechat_attempt_unbound" -> WeChatPollOutcome.Fail(WeChatConnectFailure.Unbound)
        "wechat_account_in_use" -> WeChatPollOutcome.Fail(WeChatConnectFailure.InUse)
        "wechat_upstream_refused" -> WeChatPollOutcome.Fail(WeChatConnectFailure.Upstream)
        "wechat_unavailable", "assistant_not_found" -> WeChatPollOutcome.Fail(WeChatConnectFailure.Unavailable)
        else ->
            if (failure.httpStatus == null || failure.httpStatus >= 500) WeChatPollOutcome.Retry
            else WeChatPollOutcome.Fail(WeChatConnectFailure.Other)
    }

private val FinalStatuses: Map<WeChatAttemptStatus, WeChatConnectFailure> =
    mapOf(
        WeChatAttemptStatus.Expired to WeChatConnectFailure.Expired,
        WeChatAttemptStatus.Blocked to WeChatConnectFailure.Blocked,
        WeChatAttemptStatus.AlreadyBound to WeChatConnectFailure.AlreadyBound,
    )

/**
 * One connect flow at a time (web `WeChatConnectController`): [start] (or Get New Code) makes a QR
 * code and polls it until WeChat answers for good, the code runs out, or [stop] (Cancel, closing the
 * dialog, leaving the page). A new start ends the one before; nothing a stopped run learns reaches
 * [step]. One poll at a time; `need_verify_code` stops polling until [submitCode].
 *
 * The attempt's binding cookie lives only on its run: it goes with every poll of that attempt and is
 * dropped when the run ends (a final answer, a removal Set-Cookie, cancel, leave, a new start, the
 * code running out). [onStatusChanged] reads the WeChat status again (connected, or no longer offered).
 * Main-thread confined, like [AssistantWeChatStore].
 */
internal class AssistantWeChatConnectStore(
    private val api: RaftAssistantWeChatConnectApi,
    private val serverId: String,
    private val scope: CoroutineScope,
    private val onStatusChanged: suspend () -> Unit,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val pollGapMillis: Long = WECHAT_POLL_GAP_MILLIS,
    private val retryMillis: Long = WECHAT_POLL_RETRY_MILLIS,
    private val logger: (String) -> Unit = { line -> raftLogInfo("RaftAssistantWeChat", line) },
) {
    private val mutableStep = MutableStateFlow<WeChatConnectStep>(WeChatConnectStep.Disclosure)
    val step: StateFlow<WeChatConnectStep> = mutableStep.asStateFlow()

    private class Run {
        /** Null while the QR code is being made. */
        var attempt: WeChatAttempt? = null
        var cookie: WeChatAttemptCookie? = null
        var scanned: Boolean = false
        var polling: Boolean = false
        var job: Job? = null
        var expiry: Job? = null
    }

    private var run: Run? = null

    /** Whether a run is going (a QR code is being made or followed). Tests. */
    internal val running: Boolean get() = run != null

    /** The cookie the current run holds. Tests: it is gone once the run ends. */
    internal val heldCookie: WeChatAttemptCookie? get() = run?.cookie

    fun stop() {
        val current = run ?: return
        run = null
        current.cookie = null
        current.expiry?.cancel()
        current.job?.cancel()
    }

    fun start() {
        stop()
        val current = Run()
        run = current
        mutableStep.value = WeChatConnectStep.Starting
        current.job =
            scope.launch {
                val result = api.startWeChatAttempt(ServerId(serverId))
                if (run !== current) return@launch
                when (result) {
                    is WeChatStartResult.Failed -> {
                        val reason = weChatStartFailure(result.failure)
                        logger("assistant_wechat_start_failed code=${result.failure.error.code} status=${result.failure.httpStatus ?: "none"}")
                        if (reason == null) connected(current) else fail(current, reason, result.failure.error)
                    }
                    is WeChatStartResult.Started -> {
                        val cookie = result.cookie
                        if (cookie == null) {
                            // Every poll would be refused (403 wechat_attempt_unbound) after the person scanned.
                            logger("assistant_wechat_start_failed reason=cookie_missing")
                            fail(current, WeChatConnectFailure.Other, null)
                            return@launch
                        }
                        current.attempt = result.attempt
                        current.cookie = cookie
                        scheduleExpiry(current, result.attempt)
                        mutableStep.value = WeChatConnectStep.Qr(result.attempt, scanned = false)
                        poll(current, firstCode = null)
                    }
                }
            }
    }

    /** Web `submitCode`: sends WeChat's verification code, or says it is malformed without asking. */
    fun submitCode(raw: String) {
        val current = run ?: return
        val attempt = current.attempt ?: return
        if (current.polling) return
        val code = raw.trim()
        if (!WeChatVerifyCodePattern.matches(code)) {
            mutableStep.value = WeChatConnectStep.Verify(attempt, WeChatVerifyError.Malformed, checking = false)
            return
        }
        mutableStep.value = WeChatConnectStep.Verify(attempt, error = null, checking = true)
        current.job = scope.launch { poll(current, firstCode = code) }
    }

    private fun live(current: Run): Boolean = run === current

    private fun scheduleExpiry(current: Run, attempt: WeChatAttempt) {
        val expiresAt = runCatching { Instant.parse(attempt.expiresAt).toEpochMilliseconds() }.getOrNull() ?: return
        val left = (expiresAt - nowEpochMillis()).coerceAtLeast(0L)
        current.expiry =
            scope.launch {
                delay(left)
                fail(current, WeChatConnectFailure.Expired, null)
            }
    }

    private fun fail(current: Run, reason: WeChatConnectFailure, error: ScreenError?) {
        if (!live(current)) return
        logger("assistant_wechat_connect_failed reason=${reason.name}")
        val failedStep = WeChatConnectStep.Failed(reason, error)
        stop()
        mutableStep.value = failedStep
        // No longer offered here: the settings entry follows the status.
        if (reason == WeChatConnectFailure.Unavailable) scope.launch { onStatusChanged() }
    }

    private fun connected(current: Run) {
        if (!live(current)) return
        stop()
        logger("assistant_wechat_connected")
        // The settings entry and the DM notice read the new connection from the status.
        scope.launch {
            try {
                onStatusChanged()
            } finally {
                // A new start since then owns the step.
                if (run == null) mutableStep.value = WeChatConnectStep.Connected
            }
        }
    }

    /** Polls until a final answer, or until WeChat asks for its code (then [submitCode] goes on). */
    private suspend fun poll(current: Run, firstCode: String?) {
        val attempt = current.attempt ?: return
        current.polling = true
        var code = firstCode
        try {
            while (live(current)) {
                val cookie = current.cookie
                if (cookie == null) {
                    logger("assistant_wechat_poll_stopped reason=cookie_dropped")
                    fail(current, WeChatConnectFailure.Other, null)
                    return
                }
                val result = api.pollWeChatAttempt(ServerId(serverId), cookie, code)
                if (!live(current)) return
                if (result.cookieCleared) current.cookie = null
                when (result) {
                    is WeChatPollResult.Failed ->
                        when (val outcome = weChatPollOutcome(result.failure)) {
                            WeChatPollOutcome.Retry -> {
                                logger("assistant_wechat_poll_retry code=${result.failure.error.code} status=${result.failure.httpStatus ?: "none"}")
                                // The code, if one was sent, goes again with the retry.
                                delay(retryMillis)
                            }
                            WeChatPollOutcome.Malformed -> {
                                mutableStep.value = WeChatConnectStep.Verify(attempt, WeChatVerifyError.Malformed, checking = false)
                                return
                            }
                            is WeChatPollOutcome.Fail -> {
                                fail(current, outcome.reason, result.failure.error)
                                return
                            }
                        }
                    is WeChatPollResult.Answered -> {
                        val sentCode = code != null
                        code = null
                        val status = result.status
                        if (status == WeChatAttemptStatus.Confirmed) {
                            connected(current)
                            return
                        }
                        FinalStatuses[status]?.let { reason ->
                            fail(current, reason, null)
                            return
                        }
                        if (status == WeChatAttemptStatus.NeedVerifyCode) {
                            // Asked again right after a code: that code was wrong.
                            mutableStep.value =
                                WeChatConnectStep.Verify(attempt, if (sentCode) WeChatVerifyError.Wrong else null, checking = false)
                            return
                        }
                        // `wait` also answers a long poll that timed out after the scan: scanned stays.
                        val scanned = current.scanned || status == WeChatAttemptStatus.Scanned || sentCode
                        current.scanned = scanned
                        mutableStep.value = WeChatConnectStep.Qr(attempt, scanned)
                        delay(pollGapMillis)
                    }
                }
            }
        } finally {
            current.polling = false
        }
    }
}
