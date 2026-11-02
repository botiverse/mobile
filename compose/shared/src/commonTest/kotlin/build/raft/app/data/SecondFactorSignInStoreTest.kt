package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecondFactorSignInStoreTest {
    private val beforeExpiry = SECOND_FACTOR_EXPIRES_AT_MILLIS_FIXTURE - 60_000L

    private fun store(
        pending: SecondFactorSignInPending = secondFactorPending(),
        now: () -> Long = { beforeExpiry },
        verify: suspend (SecondFactorSignInPending, SecondFactorStepUpAnswer) -> SecondFactorSignInResult,
    ) = SecondFactorSignInStore(pending, verify, now)

    @Test
    fun challengeThenARightCodeSignsIn() = runTest {
        val session = authSessionFixture()
        val answers = mutableListOf<SecondFactorStepUpAnswer>()
        val store = store { _, answer -> answers += answer; SecondFactorSignInResult.SignedIn(session) }

        assertEquals(SecondFactorStepUpMethod.Totp, store.state.value.method)
        assertEquals(listOf(SecondFactorStepUpMethod.BackupCode), store.state.value.otherMethods)
        assertEquals("a***@example.com", store.state.value.maskedEmail)
        assertFalse(store.state.value.canSubmit)

        store.accept(SecondFactorSignInIntent.InputChanged("12 34 56 7"))
        assertEquals("123456", store.state.value.input)
        store.accept(SecondFactorSignInIntent.Submit)

        assertEquals(listOf<SecondFactorStepUpAnswer>(SecondFactorStepUpAnswer.AuthenticatorCode("123456")), answers)
        assertEquals(SecondFactorSignInOutcome.SignedIn(session), store.outcome.value)
        assertFalse(store.state.value.submitting)
    }

    @Test
    fun aBackupCodeIsSentAsTypedWithoutSpaces() = runTest {
        val answers = mutableListOf<SecondFactorStepUpAnswer>()
        val store = store { _, answer -> answers += answer; SecondFactorSignInResult.SignedIn(authSessionFixture(), backupCodesRemaining = 9) }

        store.accept(SecondFactorSignInIntent.InputChanged("1"))
        store.accept(SecondFactorSignInIntent.SwitchMethod(SecondFactorStepUpMethod.BackupCode))
        assertEquals("", store.state.value.input)
        store.accept(SecondFactorSignInIntent.InputChanged(" abcde fghij "))
        store.accept(SecondFactorSignInIntent.Submit)

        assertEquals(listOf<SecondFactorStepUpAnswer>(SecondFactorStepUpAnswer.BackupCode("abcdefghij")), answers)
    }

    @Test
    fun aWrongCodeMarksTheFieldAndKeepsTheSignInOpen() = runTest {
        val results = ArrayDeque(listOf(SecondFactorSignInResult.CodeInvalid, SecondFactorSignInResult.BackupCodeUsed))
        val store = store { _, _ -> results.removeFirst() }

        store.accept(SecondFactorSignInIntent.InputChanged("000000"))
        store.accept(SecondFactorSignInIntent.Submit)
        assertEquals(SecondFactorStepUpFieldError.CodeInvalid, store.state.value.fieldError)
        assertNull(store.state.value.ended)
        assertNull(store.outcome.value)

        // Typing clears the mark; a used backup code says so.
        store.accept(SecondFactorSignInIntent.InputChanged("00000"))
        assertNull(store.state.value.fieldError)
        store.accept(SecondFactorSignInIntent.SwitchMethod(SecondFactorStepUpMethod.BackupCode))
        store.accept(SecondFactorSignInIntent.InputChanged("abcde-fghij"))
        store.accept(SecondFactorSignInIntent.Submit)
        assertEquals(SecondFactorStepUpFieldError.BackupCodeUsed, store.state.value.fieldError)
        assertNull(store.outcome.value)
    }

    @Test
    fun anExpiredChallengeLeavesOnlyBackToSignIn() = runTest {
        // From the server (410), from the page's timer, and from a submit after expiresAt.
        val fromServer = store { _, _ -> SecondFactorSignInResult.Expired }
        fromServer.accept(SecondFactorSignInIntent.InputChanged("123456"))
        fromServer.accept(SecondFactorSignInIntent.Submit)

        val fromTimer = store { _, _ -> error("not sent") }
        fromTimer.accept(SecondFactorSignInIntent.Expire)

        val fromClock = store(now = { SECOND_FACTOR_EXPIRES_AT_MILLIS_FIXTURE }) { _, _ -> error("not sent") }
        fromClock.accept(SecondFactorSignInIntent.InputChanged("123456"))
        fromClock.accept(SecondFactorSignInIntent.Submit)

        listOf(fromServer, fromTimer, fromClock).forEach { store ->
            assertEquals(SecondFactorSignInEnd.Expired, store.state.value.ended)
            assertFalse(store.state.value.canSubmit)
            store.accept(SecondFactorSignInIntent.InputChanged("654321"))
            store.accept(SecondFactorSignInIntent.Back)
            assertEquals(SecondFactorSignInOutcome.Cancelled, store.outcome.value)
        }
    }

    @Test
    fun tooManyAttemptsAndTheLockEndTheSignIn() = runTest {
        val cases =
            mapOf(
                SecondFactorSignInResult.AttemptsExhausted to SecondFactorSignInEnd.AttemptsExhausted,
                SecondFactorSignInResult.Locked(1_793_607_600_000L) to SecondFactorSignInEnd.Locked(1_793_607_600_000L),
            )
        cases.forEach { (result, end) ->
            val store = store { _, _ -> result }
            store.accept(SecondFactorSignInIntent.InputChanged("123456"))
            store.accept(SecondFactorSignInIntent.Submit)
            assertEquals(end, store.state.value.ended, result.toString())
            assertNull(store.outcome.value)
        }
    }

    @Test
    fun otherRefusalsShowABannerAndKeepTheAnswer() = runTest {
        val results = ArrayDeque(listOf(SecondFactorSignInResult.Unavailable, SecondFactorSignInResult.Failed(serverCode = "network")))
        val store = store { _, _ -> results.removeFirst() }
        store.accept(SecondFactorSignInIntent.InputChanged("123456"))

        store.accept(SecondFactorSignInIntent.Submit)
        assertEquals(SecondFactorSignInBanner.Unavailable, store.state.value.banner)
        store.accept(SecondFactorSignInIntent.Submit)
        assertEquals(SecondFactorSignInBanner.Failed(null), store.state.value.banner)
        assertEquals("123456", store.state.value.input)
        assertTrue(store.state.value.canSubmit)
    }

    @Test
    fun backCancelsButNotWhileAnAnswerIsBeingChecked() = runTest {
        val gate = CompletableDeferred<SecondFactorSignInResult>()
        val store = store { _, _ -> gate.await() }
        store.accept(SecondFactorSignInIntent.InputChanged("123456"))

        val submitting = launch { store.accept(SecondFactorSignInIntent.Submit) }
        runCurrent()
        assertTrue(store.state.value.submitting)
        store.accept(SecondFactorSignInIntent.Back)
        assertNull(store.outcome.value)

        gate.complete(SecondFactorSignInResult.CodeInvalid)
        submitting.join()
        store.accept(SecondFactorSignInIntent.Back)
        assertEquals(SecondFactorSignInOutcome.Cancelled, store.outcome.value)
        // Settled: nothing more is sent.
        store.accept(SecondFactorSignInIntent.Submit)
        assertEquals(SecondFactorSignInOutcome.Cancelled, store.outcome.value)
    }

    @Test
    fun aPasskeyOnlyChallengeHasNoFieldAndOnlyGoesBack() = runTest {
        val store = store(pending = secondFactorPending(methods = listOf("passkey"))) { _, _ -> error("not sent") }

        assertNull(store.state.value.method)
        store.accept(SecondFactorSignInIntent.InputChanged("123456"))
        store.accept(SecondFactorSignInIntent.Submit)
        assertNull(store.outcome.value)
        store.accept(SecondFactorSignInIntent.Back)
        assertEquals(SecondFactorSignInOutcome.Cancelled, store.outcome.value)
    }
}
