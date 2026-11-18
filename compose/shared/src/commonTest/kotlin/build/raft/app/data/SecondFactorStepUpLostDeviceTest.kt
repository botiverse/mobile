package build.raft.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * "Verify it's you" for a lost device's `lost_device` revoke (web VerifyItsYouDialog with
 * `recoveryEmail` and `lostDevice`, botiverse/slock staging 33f441958f82ce0178cc7f48232d41b20907e50c):
 * Can't verify? offers the password email, Sign it out now with your password, and the two-step reset.
 */
class SecondFactorStepUpLostDeviceTest {
    private val refusal: DataResult<String> =
        DataResult.Error(
            ScreenError(
                code = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
                retryable = false,
                secondFactorStepUp = SecondFactorStepUpChallenge(methods = listOf("totp", "backup_code")),
            )
        )
    private val recovery = SecondFactorStepUpRecovery("me@raft.build", SecondFactorStepUpLostDevice(SecondFactorStepUpSignOutScope.Others, password = true))

    @Test
    fun signOutInsteadEndsWithoutSendingAgain() = runTest {
        var calls = 0
        val coordinator = SecondFactorStepUpCoordinator(stepUp = { error("not answered") })
        val outcome = async { coordinator.runSigningOut(recovery) { calls++; refusal } }
        runCurrent()
        val dialog = assertNotNull(coordinator.state.value)
        assertEquals(recovery, dialog.recovery)
        assertEquals(SecondFactorStepUpView.Answer, dialog.view)

        coordinator.accept(SecondFactorStepUpIntent.ShowLost)
        assertEquals(SecondFactorStepUpView.Lost, coordinator.state.value?.view)
        coordinator.accept(SecondFactorStepUpIntent.SignOutInstead)

        assertEquals(SecondFactorStepUpSigningOutOutcome.SignOutInstead, outcome.await())
        assertEquals(1, calls)
        assertNull(coordinator.state.value)
    }

    @Test
    fun theEmailsGoToTheOwnAddressAndShowCheckYourEmail() = runTest {
        val sent = mutableListOf<String>()
        val results = ArrayDeque(listOf(SecondFactorRecoveryEmailResult.TooManyRequests, SecondFactorRecoveryEmailResult.Sent))
        val coordinator =
            SecondFactorStepUpCoordinator(
                stepUp = { error("not answered") },
                requestPasswordEmail = { email -> sent += "password:$email"; results.removeFirst() },
                requestTwoStepReset = { email -> sent += "two-step:$email"; SecondFactorRecoveryEmailResult.Sent },
            )
        val outcome = async { coordinator.runSigningOut(recovery) { refusal } }
        runCurrent()
        coordinator.accept(SecondFactorStepUpIntent.ShowLost)
        coordinator.accept(SecondFactorStepUpIntent.RequestPasswordEmail)
        assertEquals(SecondFactorRecoveryEmailResult.TooManyRequests, coordinator.state.value?.recoveryFailure)
        assertEquals(SecondFactorStepUpView.Lost, coordinator.state.value?.view)
        coordinator.accept(SecondFactorStepUpIntent.RequestPasswordEmail)
        assertEquals(SecondFactorStepUpView.PasswordEmailSent, coordinator.state.value?.view)

        // Done closes it like Cancel: nothing was signed out.
        coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpSigningOutOutcome.Cancelled, outcome.await())
        assertEquals(listOf("password:me@raft.build", "password:me@raft.build"), sent)
    }

    @Test
    fun withoutALostDeviceOnlyTheTwoStepResetIsOfferedAndSignOutInsteadDoesNothing() = runTest {
        var reset = 0
        val coordinator =
            SecondFactorStepUpCoordinator(
                stepUp = { SecondFactorStepUpResult.Verified() },
                requestTwoStepReset = { reset++; SecondFactorRecoveryEmailResult.Sent },
            )
        val outcome = async { coordinator.runSigningOut(SecondFactorStepUpRecovery("me@raft.build")) { refusal } }
        runCurrent()
        coordinator.accept(SecondFactorStepUpIntent.ShowLost)
        coordinator.accept(SecondFactorStepUpIntent.SignOutInstead)
        coordinator.accept(SecondFactorStepUpIntent.RequestPasswordEmail)
        assertEquals(SecondFactorStepUpView.Lost, coordinator.state.value?.view, "neither applies without a lost device")
        coordinator.accept(SecondFactorStepUpIntent.RequestTwoStepReset)
        assertEquals(SecondFactorStepUpView.RecoverySent, coordinator.state.value?.view)
        assertEquals(1, reset)

        // Back from the lost page with the backup code link: the answer page, the backup code field.
        coordinator.accept(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.BackupCode))
        assertEquals(SecondFactorStepUpView.Answer, coordinator.state.value?.view)
        assertEquals(SecondFactorStepUpMethod.BackupCode, coordinator.state.value?.method)
        coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpSigningOutOutcome.Cancelled, outcome.await())
    }

    @Test
    fun theOrdinaryStepUpHasNoLostPage() = runTest {
        val coordinator = SecondFactorStepUpCoordinator(stepUp = { error("not answered") })
        val outcome = async { coordinator.run { refusal } }
        runCurrent()
        coordinator.accept(SecondFactorStepUpIntent.ShowLost)
        assertEquals(SecondFactorStepUpView.Answer, coordinator.state.value?.view)
        coordinator.accept(SecondFactorStepUpIntent.SignOutInstead)
        assertNotNull(coordinator.state.value, "still open")
        coordinator.accept(SecondFactorStepUpIntent.Cancel)
        assertEquals(SecondFactorStepUpOutcome.Cancelled, outcome.await())
    }
}
