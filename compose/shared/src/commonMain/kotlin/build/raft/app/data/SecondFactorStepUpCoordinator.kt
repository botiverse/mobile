package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the answer field is marked. */
enum class SecondFactorStepUpFieldError { CodeInvalid, BackupCodeInvalid, BackupCodeUsed }

/** A refusal shown in a banner under the field. */
sealed interface SecondFactorStepUpBanner {
    data class Locked(val lockedUntilEpochMillis: Long?) : SecondFactorStepUpBanner
    /** 403 `session_family_required`: offer Sign In Again. */
    data object SignInAgain : SecondFactorStepUpBanner
    data class Failed(val serverMessage: String?) : SecondFactorStepUpBanner
}

/** Can't verify? › Sign it out now with your password: one device, or every other one (web `LostDeviceStepUp.signOut`). */
enum class SecondFactorStepUpSignOutScope { One, Others }

/**
 * Can't verify? for a lost device's `lost_device` revoke (web `LostDeviceStepUp`, slock #triage:#386):
 * what it may sign out with the password alone ([signOut]; null: no password, or nothing left to
 * sign out), and whether the account has a password ([password] false: the email sets a first one).
 */
data class SecondFactorStepUpLostDevice(
    val signOut: SecondFactorStepUpSignOutScope?,
    val password: Boolean,
)

/**
 * Signing out a lost device (web SecondFactorStepUpHost `recoveryEmail` for the `signOutDevice`
 * reason): the authenticator may have gone with it, so the dialog offers "Lost the device with your
 * authenticator?" for the person's own [email]. With [lostDevice] it offers the password email and
 * Sign it out now with your password; without, the two-step reset by email.
 */
data class SecondFactorStepUpRecovery(
    val email: String,
    val lostDevice: SecondFactorStepUpLostDevice? = null,
)

/** Which page of the dialog shows (web VerifyItsYouDialog `view`). */
enum class SecondFactorStepUpView {
    Answer,
    /** "Lost the device with your authenticator?" */
    Lost,
    /** The two-step reset email went out. */
    RecoverySent,
    /** The password reset (or set) email went out. */
    PasswordEmailSent,
}

/** A recovery email's answer (POST /auth/second-factor/recovery, POST /auth/forgot-password). */
enum class SecondFactorRecoveryEmailResult { Sent, TooManyRequests, Failed }

/**
 * The open "Verify it's you" dialog (web VerifyItsYouDialog): one way to answer at a time, the
 * others as links. [method] is null when the server listed only a passkey: the dialog then says
 * to do this on the web and has no field. [recovery]: signing out a lost device (see
 * [SecondFactorStepUpRecovery]); [passkeyListed]: the server listed a passkey (the lost page warns
 * that a passkey on the lost device still signs in).
 */
data class SecondFactorStepUpDialogState(
    val methods: List<SecondFactorStepUpMethod>,
    val method: SecondFactorStepUpMethod?,
    val input: String = "",
    val submitting: Boolean = false,
    val fieldError: SecondFactorStepUpFieldError? = null,
    val banner: SecondFactorStepUpBanner? = null,
    val recovery: SecondFactorStepUpRecovery? = null,
    val passkeyListed: Boolean = false,
    val view: SecondFactorStepUpView = SecondFactorStepUpView.Answer,
    /** The last recovery email that failed, shown on the lost page. */
    val recoveryFailure: SecondFactorRecoveryEmailResult? = null,
) {
    val canSubmit: Boolean
        get() = method != null && !submitting && normalizedSecondFactorAnswer(input).isNotEmpty()

    val otherMethods: List<SecondFactorStepUpMethod>
        get() = methods.filter { it != method }
}

sealed interface SecondFactorStepUpIntent {
    data class InputChanged(val text: String) : SecondFactorStepUpIntent
    data class SwitchMethod(val method: SecondFactorStepUpMethod) : SecondFactorStepUpIntent
    data object Submit : SecondFactorStepUpIntent
    /** Cancel, the close button and Back: the refused action ends quietly. */
    data object Cancel : SecondFactorStepUpIntent
    /** Sign In Again (after `session_family_required`): closes like Cancel; the host signs out. */
    data object SignInAgain : SecondFactorStepUpIntent
    /** "Lost the device with your authenticator?" (only with a [SecondFactorStepUpDialogState.recovery]). */
    data object ShowLost : SecondFactorStepUpIntent
    /** Back, from the lost page. */
    data object ShowAnswer : SecondFactorStepUpIntent
    /** Reset (or set) your password by email, for the person's own address. */
    data object RequestPasswordEmail : SecondFactorStepUpIntent
    /** Reset two-step verification by email, for the person's own address. */
    data object RequestTwoStepReset : SecondFactorStepUpIntent
    /** Sign it (them) out now with your password: the caller sends `sign_out` instead. */
    data object SignOutInstead : SecondFactorStepUpIntent
}

sealed interface SecondFactorStepUpOutcome<out T> {
    /** The action's own result: the first one when no step-up was asked, else the retry's. */
    data class Completed<T>(val result: DataResult<T>) : SecondFactorStepUpOutcome<T>
    /** The dialog was closed: the caller shows nothing. */
    data object Cancelled : SecondFactorStepUpOutcome<Nothing>
}

/** [SecondFactorStepUpCoordinator.runSigningOut]'s outcome: [SecondFactorStepUpOutcome]'s two, and Sign out instead. */
sealed interface SecondFactorStepUpSigningOutOutcome<out T> {
    data class Completed<T>(val result: DataResult<T>) : SecondFactorStepUpSigningOutOutcome<T>
    data object Cancelled : SecondFactorStepUpSigningOutOutcome<Nothing>
    /** Can't verify? › Sign it out now with your password: nothing was sent the second time. */
    data object SignOutInstead : SecondFactorStepUpSigningOutOutcome<Nothing>
}

/** How the open dialog ended. */
internal enum class SecondFactorStepUpSettle { Verified, Cancelled, SignOutInstead }

/** Spaces are never part of a code (web `normalizeEnteredCode`). */
internal fun normalizedSecondFactorAnswer(value: String): String = value.filterNot { it.isWhitespace() }

private const val TOTP_CODE_LENGTH = 6

/**
 * Step-up for any sensitive action (web `withSecondFactorStepUp`). [run] sends the action; when
 * the server refuses it with 403 `second_factor_step_up_required`, it opens the dialog, and once
 * POST /step-up passed (or two-step verification turned out to be off) it sends the action
 * exactly once more and returns that result (a second refusal is the caller's like any error).
 * Closing the dialog returns [SecondFactorStepUpOutcome.Cancelled]. Two actions refused at
 * once share one dialog.
 *
 * The host store forwards the dialog's intents to [accept] and mirrors [state] into its own
 * state through [onStateChange]. [requestPasswordEmail] and [requestTwoStepReset] send the lost
 * page's emails (only [runSigningOut] opens that page).
 */
class SecondFactorStepUpCoordinator(
    private val stepUp: suspend (SecondFactorStepUpAnswer) -> SecondFactorStepUpResult,
    private val onStateChange: (SecondFactorStepUpDialogState?) -> Unit = {},
    private val requestPasswordEmail: suspend (String) -> SecondFactorRecoveryEmailResult = { SecondFactorRecoveryEmailResult.Failed },
    private val requestTwoStepReset: suspend (String) -> SecondFactorRecoveryEmailResult = { SecondFactorRecoveryEmailResult.Failed },
) {
    private val _state = MutableStateFlow<SecondFactorStepUpDialogState?>(null)
    val state: StateFlow<SecondFactorStepUpDialogState?> = _state.asStateFlow()

    private var pending: CompletableDeferred<SecondFactorStepUpSettle>? = null

    suspend fun <T> run(request: suspend () -> DataResult<T>): SecondFactorStepUpOutcome<T> {
        val first = request()
        val challenge = first.secondFactorStepUpChallengeOrNull() ?: return SecondFactorStepUpOutcome.Completed(first)
        if (awaitSettled(challenge, recovery = null) != SecondFactorStepUpSettle.Verified) return SecondFactorStepUpOutcome.Cancelled
        return SecondFactorStepUpOutcome.Completed(request())
    }

    /**
     * [run] for signing out a lost device (web `withSecondFactorStepUp(request, "signOutDevice",
     * { lostDevice })`): the dialog also offers [recovery]'s "Lost the device with your
     * authenticator?", whose Sign it out now with your password ends it with
     * [SecondFactorStepUpSigningOutOutcome.SignOutInstead] (the caller sends `sign_out`).
     */
    suspend fun <T> runSigningOut(
        recovery: SecondFactorStepUpRecovery,
        request: suspend () -> DataResult<T>,
    ): SecondFactorStepUpSigningOutOutcome<T> {
        val first = request()
        val challenge = first.secondFactorStepUpChallengeOrNull() ?: return SecondFactorStepUpSigningOutOutcome.Completed(first)
        return when (awaitSettled(challenge, recovery)) {
            SecondFactorStepUpSettle.Verified -> SecondFactorStepUpSigningOutOutcome.Completed(request())
            SecondFactorStepUpSettle.Cancelled -> SecondFactorStepUpSigningOutOutcome.Cancelled
            SecondFactorStepUpSettle.SignOutInstead -> SecondFactorStepUpSigningOutOutcome.SignOutInstead
        }
    }

    suspend fun accept(intent: SecondFactorStepUpIntent) {
        when (intent) {
            is SecondFactorStepUpIntent.InputChanged -> update { current ->
                if (current.submitting) return@update current
                val text =
                    if (current.method == SecondFactorStepUpMethod.Totp) {
                        intent.text.filter { it.isDigit() }.take(TOTP_CODE_LENGTH)
                    } else {
                        intent.text
                    }
                if (text == current.input) current else current.copy(input = text, fieldError = null)
            }
            is SecondFactorStepUpIntent.SwitchMethod -> update { current ->
                val sameAnswer = intent.method == current.method && current.view == SecondFactorStepUpView.Answer
                if (current.submitting || sameAnswer || intent.method !in current.methods) {
                    current
                } else {
                    // The lost page's "Use a backup code" comes back to the answer page too.
                    current.copy(
                        method = intent.method,
                        input = "",
                        fieldError = null,
                        banner = null,
                        view = SecondFactorStepUpView.Answer,
                    )
                }
            }
            SecondFactorStepUpIntent.Submit -> submit()
            SecondFactorStepUpIntent.Cancel,
            SecondFactorStepUpIntent.SignInAgain ->
                if (_state.value?.submitting != true) settle(SecondFactorStepUpSettle.Cancelled)
            SecondFactorStepUpIntent.ShowLost -> update { current ->
                if (current.submitting || current.recovery == null) {
                    current
                } else {
                    current.copy(view = SecondFactorStepUpView.Lost, recoveryFailure = null)
                }
            }
            SecondFactorStepUpIntent.ShowAnswer -> update { current ->
                if (current.submitting) current else current.copy(view = SecondFactorStepUpView.Answer)
            }
            SecondFactorStepUpIntent.RequestPasswordEmail -> sendRecoveryEmail(password = true)
            SecondFactorStepUpIntent.RequestTwoStepReset -> sendRecoveryEmail(password = false)
            SecondFactorStepUpIntent.SignOutInstead -> {
                val current = _state.value
                if (current != null && !current.submitting && current.recovery?.lostDevice?.signOut != null) {
                    settle(SecondFactorStepUpSettle.SignOutInstead)
                }
            }
        }
    }

    /** The lost page's emails: the password one (with a lost device) or the two-step reset. */
    private suspend fun sendRecoveryEmail(password: Boolean) {
        val current = _state.value ?: return
        val recovery = current.recovery ?: return
        if (current.submitting) return
        if (password && recovery.lostDevice == null) return
        val deferred = pending ?: return
        publish(current.copy(submitting = true, recoveryFailure = null))
        val result = if (password) requestPasswordEmail(recovery.email) else requestTwoStepReset(recovery.email)
        if (pending !== deferred) return
        val shown = _state.value ?: return
        publish(
            when (result) {
                SecondFactorRecoveryEmailResult.Sent ->
                    shown.copy(
                        submitting = false,
                        view = if (password) SecondFactorStepUpView.PasswordEmailSent else SecondFactorStepUpView.RecoverySent,
                    )
                else -> shown.copy(submitting = false, recoveryFailure = result)
            }
        )
    }

    private suspend fun awaitSettled(
        challenge: SecondFactorStepUpChallenge,
        recovery: SecondFactorStepUpRecovery?,
    ): SecondFactorStepUpSettle {
        pending?.let { return it.await() }
        val deferred = CompletableDeferred<SecondFactorStepUpSettle>()
        pending = deferred
        val methods = challenge.usableMethods
        publish(
            SecondFactorStepUpDialogState(
                methods = methods,
                method = methods.firstOrNull(),
                recovery = recovery,
                passkeyListed = "passkey" in challenge.methods,
            )
        )
        return try {
            deferred.await()
        } finally {
            // Also when the waiting action is cancelled (the page went away): no dialog left open.
            if (pending === deferred) {
                pending = null
                publish(null)
            }
        }
    }

    private suspend fun submit() {
        val current = _state.value ?: return
        val method = current.method ?: return
        if (current.view != SecondFactorStepUpView.Answer || !current.canSubmit) return
        val deferred = pending ?: return
        val entered = normalizedSecondFactorAnswer(current.input)
        val answer =
            when (method) {
                SecondFactorStepUpMethod.Totp -> SecondFactorStepUpAnswer.AuthenticatorCode(entered)
                SecondFactorStepUpMethod.BackupCode -> SecondFactorStepUpAnswer.BackupCode(entered)
            }
        publish(current.copy(submitting = true, fieldError = null, banner = null))
        val result = stepUp(answer)
        if (pending !== deferred) return
        val shown = _state.value ?: return
        val next = shown.copy(submitting = false)
        when (result) {
            is SecondFactorStepUpResult.Verified,
            SecondFactorStepUpResult.NotEnabled -> settle(SecondFactorStepUpSettle.Verified)
            SecondFactorStepUpResult.CodeInvalid ->
                publish(
                    next.copy(
                        fieldError =
                            if (method == SecondFactorStepUpMethod.BackupCode) {
                                SecondFactorStepUpFieldError.BackupCodeInvalid
                            } else {
                                SecondFactorStepUpFieldError.CodeInvalid
                            }
                    )
                )
            SecondFactorStepUpResult.BackupCodeUsed ->
                publish(next.copy(fieldError = SecondFactorStepUpFieldError.BackupCodeUsed))
            is SecondFactorStepUpResult.Locked ->
                publish(next.copy(banner = SecondFactorStepUpBanner.Locked(result.lockedUntilEpochMillis)))
            SecondFactorStepUpResult.SessionFamilyRequired ->
                publish(next.copy(banner = SecondFactorStepUpBanner.SignInAgain))
            is SecondFactorStepUpResult.Failed ->
                publish(next.copy(banner = SecondFactorStepUpBanner.Failed(result.serverMessage)))
        }
    }

    private fun settle(outcome: SecondFactorStepUpSettle) {
        val deferred = pending ?: return
        pending = null
        publish(null)
        deferred.complete(outcome)
    }

    private inline fun update(transform: (SecondFactorStepUpDialogState) -> SecondFactorStepUpDialogState) {
        val current = _state.value ?: return
        val next = transform(current)
        if (next != current) publish(next)
    }

    private fun publish(next: SecondFactorStepUpDialogState?) {
        if (_state.value == next) return
        _state.value = next
        onStateChange(next)
    }
}
