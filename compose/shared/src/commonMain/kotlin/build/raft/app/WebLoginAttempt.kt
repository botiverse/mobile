package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.data.APP_LOGIN_BROWSER_CLOSED
import build.raft.app.data.APP_LOGIN_CALLBACK_RECEIVED
import build.raft.app.data.APP_LOGIN_STALE_CALLBACK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where one browser sign-in attempt on the sign-in page stands. */
internal enum class WebLoginAttemptPhase {
    /** Nothing in flight: the sign-in button can be tapped. */
    Idle,

    /** Preparing the request and opening the browser. */
    Opening,

    /** The browser is open; waiting for its callback (or for the user to come back without one). */
    AwaitingCallback,

    /** A callback arrived: the code is being exchanged, or the session is being opened. */
    Completing,
}

/**
 * artin 10-27: after "Sign in to Raft" the page used to come back from the browser looking
 * untouched. While an attempt is pending the button reads "Signing in…", and neither it nor the
 * other sign-in entry starts a second request. The attempt ends when:
 * - the browser could not be opened ([onBrowserOpened] false);
 * - the callback is an error, a denial or a stale link ([onCompletion]);
 * - the host says the browser sheet closed without one ([APP_LOGIN_BROWSER_CLOSED], iOS);
 * - the app comes back to the foreground after leaving for the browser and no callback arrives
 *   within [resumeGraceMs] (the user cancelled or closed the browser tab); not when
 *   [endsOnResume] is false (iOS, whose sheet reports its own cancel).
 * A successful callback stays pending: the page moves on to the post-sign-in route.
 *
 * [scope] runs the resume grace; tests pass a `runTest` scope so the grace is virtual time.
 */
internal class WebLoginAttempt(
    private val scope: CoroutineScope,
    private val resumeGraceMs: Long = WEB_LOGIN_RESUME_GRACE_MS,
    /** False on iOS: see [raftPlatformWebLoginEndsOnResume]. */
    private val endsOnResume: Boolean = true,
    private val logger: (String) -> Unit = {},
) {
    var phase by mutableStateOf(WebLoginAttemptPhase.Idle)
        private set

    val pending: Boolean get() = phase != WebLoginAttemptPhase.Idle

    private var resumed = true
    private var leftAppSinceBegin = false
    private var callbackInFlight = false
    private var releaseJob: Job? = null

    /** Starts an attempt; false (nothing changes) while one is already pending. */
    fun tryBegin(): Boolean {
        if (pending) return false
        phase = WebLoginAttemptPhase.Opening
        leftAppSinceBegin = false
        callbackInFlight = false
        logger("web_login_attempt phase=opening")
        return true
    }

    fun onBrowserOpened(opened: Boolean) {
        if (phase != WebLoginAttemptPhase.Opening) return
        if (!opened) {
            release("browser_not_opened")
            return
        }
        phase = WebLoginAttemptPhase.AwaitingCallback
        logger("web_login_attempt phase=awaiting_callback")
        // Already back (the host returned only after the user came back): start the grace now.
        if (resumed && leftAppSinceBegin) armResumeGrace()
    }

    fun onPaused() {
        resumed = false
        if (pending) leftAppSinceBegin = true
        cancelResumeGrace()
    }

    fun onResumed() {
        resumed = true
        if (phase == WebLoginAttemptPhase.AwaitingCallback && leftAppSinceBegin) armResumeGrace()
    }

    /** A [build.raft.app.data.RaftWebLoginCompletion] status; `null` when it was cleared. */
    fun onCompletion(status: String?) {
        when (status) {
            null ->
                // The callback was accepted but then ignored (a duplicate, or no request on file):
                // nothing more will come for it.
                if (callbackInFlight) release("callback_ignored")
            APP_LOGIN_CALLBACK_RECEIVED -> {
                cancelResumeGrace()
                callbackInFlight = true
                phase = WebLoginAttemptPhase.Completing
                logger("web_login_attempt phase=completing")
            }
            "app_shell", "choose_server" -> {
                // Stays pending: the page opens the post-sign-in route (or reports a failure).
                cancelResumeGrace()
                callbackInFlight = false
                phase = WebLoginAttemptPhase.Completing
            }
            APP_LOGIN_BROWSER_CLOSED ->
                if (phase == WebLoginAttemptPhase.AwaitingCallback) release("browser_closed")
            "login_error", "legal_acceptance", "retry", APP_LOGIN_STALE_CALLBACK -> release("callback_$status")
        }
    }

    /** The post-sign-in route could not be opened (no stored session): let the user try again. */
    fun onSessionMissing() = release("session_missing")

    private fun armResumeGrace() {
        if (!endsOnResume) return
        cancelResumeGrace()
        releaseJob =
            scope.launch {
                delay(resumeGraceMs)
                if (phase == WebLoginAttemptPhase.AwaitingCallback) release("resumed_without_callback")
            }
    }

    private fun cancelResumeGrace() {
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun release(reason: String) {
        cancelResumeGrace()
        callbackInFlight = false
        if (phase == WebLoginAttemptPhase.Idle) return
        phase = WebLoginAttemptPhase.Idle
        logger("web_login_attempt phase=idle reason=$reason")
    }
}

/** How long after coming back from the browser the page waits for a callback before re-enabling. */
internal const val WEB_LOGIN_RESUME_GRACE_MS = 1_500L
