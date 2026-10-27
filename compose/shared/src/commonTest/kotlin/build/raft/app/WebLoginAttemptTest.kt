package build.raft.app

import build.raft.app.data.APP_LOGIN_BROWSER_CLOSED
import build.raft.app.data.APP_LOGIN_CALLBACK_RECEIVED
import build.raft.app.data.APP_LOGIN_STALE_CALLBACK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** artin 10-27: "Sign in to Raft" turns into "Signing in…" and recovers when no callback comes. */
@OptIn(ExperimentalCoroutinesApi::class)
class WebLoginAttemptTest {
    private fun TestScope.openedAttempt(): WebLoginAttempt =
        WebLoginAttempt(backgroundScope, resumeGraceMs = GRACE).apply {
            assertTrue(tryBegin())
            onPaused() // the browser covers the app
            onBrowserOpened(true)
        }

    @Test
    fun pendingAttemptBlocksASecondRequestUntilItEnds() = runTest {
        val attempt = WebLoginAttempt(backgroundScope, resumeGraceMs = GRACE)
        assertTrue(attempt.tryBegin())
        assertEquals(WebLoginAttemptPhase.Opening, attempt.phase)
        assertFalse(attempt.tryBegin(), "a second tap while opening")
        var launches = 0
        startWebLoginWithConsent(consentAccepted = true, starting = attempt.pending) { launches++ }
        assertEquals(0, launches, "the launch boundary sees the pending attempt")

        attempt.onBrowserOpened(true)
        assertEquals(WebLoginAttemptPhase.AwaitingCallback, attempt.phase)
        assertFalse(attempt.tryBegin(), "a second tap while the browser is open")
    }

    @Test
    fun resumingWithoutACallbackReEnablesOnlyAfterTheGrace() = runTest {
        val attempt = openedAttempt()
        attempt.onResumed()
        advanceTimeBy(GRACE - 1)
        runCurrent()
        assertEquals(WebLoginAttemptPhase.AwaitingCallback, attempt.phase, "still within the grace")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(WebLoginAttemptPhase.Idle, attempt.phase)
        assertTrue(attempt.tryBegin(), "the user can sign in again")
    }

    @Test
    fun aCallbackWithinTheGraceKeepsTheAttemptPendingThroughSuccess() = runTest {
        val attempt = openedAttempt()
        attempt.onResumed()
        advanceTimeBy(GRACE / 2)
        attempt.onCompletion(APP_LOGIN_CALLBACK_RECEIVED)
        advanceTimeBy(GRACE * 4)
        runCurrent()
        assertEquals(WebLoginAttemptPhase.Completing, attempt.phase, "exchanging, not cancelled")

        attempt.onCompletion("choose_server")
        attempt.onCompletion(null) // the page clears the status after opening the next route
        assertEquals(WebLoginAttemptPhase.Completing, attempt.phase, "stays busy while the page moves on")
        assertFalse(attempt.tryBegin())
    }

    @Test
    fun leavingAgainBeforeTheGraceEndsCancelsIt() = runTest {
        val attempt = openedAttempt()
        attempt.onResumed()
        advanceTimeBy(GRACE / 2)
        attempt.onPaused() // back to the browser
        advanceTimeBy(GRACE * 2)
        runCurrent()
        assertEquals(WebLoginAttemptPhase.AwaitingCallback, attempt.phase)
    }

    @Test
    fun aResumeThatDidNotFollowLeavingTheAppDoesNotEndTheAttempt() = runTest {
        // iOS: the auth sheet may never pause the app; its cancel arrives as browser_closed instead.
        val attempt = WebLoginAttempt(backgroundScope, resumeGraceMs = GRACE)
        attempt.tryBegin()
        attempt.onBrowserOpened(true)
        attempt.onResumed()
        advanceTimeBy(GRACE * 2)
        runCurrent()
        assertEquals(WebLoginAttemptPhase.AwaitingCallback, attempt.phase)

        attempt.onCompletion(APP_LOGIN_BROWSER_CLOSED)
        assertEquals(WebLoginAttemptPhase.Idle, attempt.phase)
    }

    @Test
    fun hostReturningAfterTheUserCameBackStartsTheGraceAtOnce() = runTest {
        val attempt = WebLoginAttempt(backgroundScope, resumeGraceMs = GRACE)
        attempt.tryBegin()
        attempt.onPaused()
        attempt.onResumed()
        attempt.onBrowserOpened(true)
        advanceTimeBy(GRACE + 1)
        runCurrent()
        assertEquals(WebLoginAttemptPhase.Idle, attempt.phase)
    }

    @Test
    fun failedCallbacksAndFailedOpensReEnableAtOnce() = runTest {
        for (status in listOf("login_error", "legal_acceptance", "retry", APP_LOGIN_STALE_CALLBACK)) {
            val attempt = openedAttempt()
            attempt.onCompletion(APP_LOGIN_CALLBACK_RECEIVED)
            attempt.onCompletion(status)
            assertEquals(WebLoginAttemptPhase.Idle, attempt.phase, "status=$status")
        }
        val notOpened = WebLoginAttempt(backgroundScope, resumeGraceMs = GRACE)
        notOpened.tryBegin()
        notOpened.onBrowserOpened(false)
        assertEquals(WebLoginAttemptPhase.Idle, notOpened.phase, "browser could not open")
    }

    @Test
    fun anIgnoredCallbackOrAMissingSessionReEnables() = runTest {
        val ignored = openedAttempt()
        ignored.onCompletion(APP_LOGIN_CALLBACK_RECEIVED)
        ignored.onCompletion(null) // the bridge cleared its marker: the coordinator ignored the callback
        assertEquals(WebLoginAttemptPhase.Idle, ignored.phase)

        val missing = openedAttempt()
        missing.onCompletion("app_shell")
        missing.onSessionMissing()
        assertEquals(WebLoginAttemptPhase.Idle, missing.phase)
    }

    private companion object {
        const val GRACE = WEB_LOGIN_RESUME_GRACE_MS
    }
}
