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

/**
 * The open "Verify it's you" dialog (web VerifyItsYouDialog): one way to answer at a time, the
 * others as links. [method] is null when the server listed only a passkey: the dialog then says
 * to do this on the web and has no field.
 */
data class SecondFactorStepUpDialogState(
    val methods: List<SecondFactorStepUpMethod>,
    val method: SecondFactorStepUpMethod?,
    val input: String = "",
    val submitting: Boolean = false,
    val fieldError: SecondFactorStepUpFieldError? = null,
    val banner: SecondFactorStepUpBanner? = null,
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
}

sealed interface SecondFactorStepUpOutcome<out T> {
    /** The action's own result: the first one when no step-up was asked, else the retry's. */
    data class Completed<T>(val result: DataResult<T>) : SecondFactorStepUpOutcome<T>
    /** The dialog was closed: the caller shows nothing. */
    data object Cancelled : SecondFactorStepUpOutcome<Nothing>
}

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
 * state through [onStateChange].
 */
class SecondFactorStepUpCoordinator(
    private val stepUp: suspend (SecondFactorStepUpAnswer) -> SecondFactorStepUpResult,
    private val onStateChange: (SecondFactorStepUpDialogState?) -> Unit = {},
) {
    private val _state = MutableStateFlow<SecondFactorStepUpDialogState?>(null)
    val state: StateFlow<SecondFactorStepUpDialogState?> = _state.asStateFlow()

    private var pending: CompletableDeferred<Boolean>? = null

    suspend fun <T> run(request: suspend () -> DataResult<T>): SecondFactorStepUpOutcome<T> {
        val first = request()
        val challenge = first.secondFactorStepUpChallengeOrNull() ?: return SecondFactorStepUpOutcome.Completed(first)
        if (!awaitVerified(challenge)) return SecondFactorStepUpOutcome.Cancelled
        return SecondFactorStepUpOutcome.Completed(request())
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
                if (current.submitting || intent.method == current.method || intent.method !in current.methods) {
                    current
                } else {
                    current.copy(method = intent.method, input = "", fieldError = null, banner = null)
                }
            }
            SecondFactorStepUpIntent.Submit -> submit()
            SecondFactorStepUpIntent.Cancel,
            SecondFactorStepUpIntent.SignInAgain -> if (_state.value?.submitting != true) settle(verified = false)
        }
    }

    private suspend fun awaitVerified(challenge: SecondFactorStepUpChallenge): Boolean {
        pending?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        val methods = challenge.usableMethods
        publish(SecondFactorStepUpDialogState(methods = methods, method = methods.firstOrNull()))
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
        if (!current.canSubmit) return
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
            SecondFactorStepUpResult.NotEnabled -> settle(verified = true)
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

    private fun settle(verified: Boolean) {
        val deferred = pending ?: return
        pending = null
        publish(null)
        deferred.complete(verified)
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
