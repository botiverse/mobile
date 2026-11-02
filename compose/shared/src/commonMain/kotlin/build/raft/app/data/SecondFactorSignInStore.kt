package build.raft.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why this sign-in is over: only Back to Sign In is left (web `ended`). */
sealed interface SecondFactorSignInEnd {
    data object Expired : SecondFactorSignInEnd
    data object AttemptsExhausted : SecondFactorSignInEnd
    data class Locked(val lockedUntilEpochMillis: Long?) : SecondFactorSignInEnd
}

/** A refusal that leaves the sign-in open, shown in a banner above the field. */
sealed interface SecondFactorSignInBanner {
    data object Unavailable : SecondFactorSignInBanner
    data class Failed(val serverMessage: String?) : SecondFactorSignInBanner
}

/**
 * The "Two-step verification" page of a sign-in (web SecondFactorSignInStep): one way to
 * answer at a time, the others under it. [method] is null when only a passkey is listed: the
 * page then says to sign in through the browser and has no field.
 */
data class SecondFactorSignInState(
    val maskedEmail: String,
    val expiresAtEpochMillis: Long,
    val methods: List<SecondFactorStepUpMethod>,
    val method: SecondFactorStepUpMethod?,
    val input: String = "",
    val submitting: Boolean = false,
    val fieldError: SecondFactorStepUpFieldError? = null,
    val banner: SecondFactorSignInBanner? = null,
    val ended: SecondFactorSignInEnd? = null,
) {
    val canSubmit: Boolean
        get() = method != null && ended == null && !submitting && normalizedSecondFactorAnswer(input).isNotEmpty()

    val otherMethods: List<SecondFactorStepUpMethod>
        get() = methods.filter { it != method }
}

sealed interface SecondFactorSignInIntent {
    data class InputChanged(val text: String) : SecondFactorSignInIntent
    data class SwitchMethod(val method: SecondFactorStepUpMethod) : SecondFactorSignInIntent
    data object Submit : SecondFactorSignInIntent
    /** The challenge's `expiresAt` went by (the page's timer; no countdown is shown). */
    data object Expire : SecondFactorSignInIntent
    /** Back to Sign In and system Back: drop this challenge (not while an answer is being checked). */
    data object Back : SecondFactorSignInIntent
}

sealed interface SecondFactorSignInOutcome {
    /** Signed in exactly as a token answer to the first step would have. */
    data class SignedIn(val session: StoredAuthSession) : SecondFactorSignInOutcome
    /** Back to the sign-in page; the challenge is discarded. */
    data object Cancelled : SecondFactorSignInOutcome
}

private const val SIGN_IN_TOTP_CODE_LENGTH = 6

/**
 * MVI store of one pending second step ([SecondFactorSignInPending]). [verify] answers the
 * challenge (`SharedAuthDataSource.verifySecondFactorSignIn`); [outcome] is set once, when the
 * person is signed in or went back. A wrong or used code marks the field; an expired challenge,
 * five wrong answers or the lock end the sign-in.
 */
class SecondFactorSignInStore(
    private val pending: SecondFactorSignInPending,
    private val verify: suspend (SecondFactorSignInPending, SecondFactorStepUpAnswer) -> SecondFactorSignInResult,
    private val now: () -> Long = ::defaultNowMillis,
) {
    private val _state =
        MutableStateFlow(
            pending.challenge.usableMethods.let { methods ->
                SecondFactorSignInState(
                    maskedEmail = pending.challenge.maskedEmail,
                    expiresAtEpochMillis = pending.challenge.expiresAtEpochMillis,
                    methods = methods,
                    method = methods.firstOrNull(),
                )
            }
        )
    val state: StateFlow<SecondFactorSignInState> = _state.asStateFlow()

    private val _outcome = MutableStateFlow<SecondFactorSignInOutcome?>(null)
    val outcome: StateFlow<SecondFactorSignInOutcome?> = _outcome.asStateFlow()

    suspend fun accept(intent: SecondFactorSignInIntent) {
        if (_outcome.value != null) return
        when (intent) {
            is SecondFactorSignInIntent.InputChanged -> update { current ->
                if (current.submitting || current.ended != null) return@update current
                val text =
                    if (current.method == SecondFactorStepUpMethod.Totp) {
                        intent.text.filter { it.isDigit() }.take(SIGN_IN_TOTP_CODE_LENGTH)
                    } else {
                        intent.text
                    }
                if (text == current.input) current else current.copy(input = text, fieldError = null)
            }
            is SecondFactorSignInIntent.SwitchMethod -> update { current ->
                if (current.submitting || current.ended != null || intent.method == current.method || intent.method !in current.methods) {
                    current
                } else {
                    current.copy(method = intent.method, input = "", fieldError = null, banner = null)
                }
            }
            SecondFactorSignInIntent.Submit -> submit()
            SecondFactorSignInIntent.Expire -> update { current ->
                if (current.ended != null) current else current.copy(ended = SecondFactorSignInEnd.Expired, submitting = false)
            }
            SecondFactorSignInIntent.Back ->
                if (!_state.value.submitting) _outcome.value = SecondFactorSignInOutcome.Cancelled
        }
    }

    private suspend fun submit() {
        val current = _state.value
        val method = current.method ?: return
        if (!current.canSubmit) return
        if (now() >= current.expiresAtEpochMillis) {
            _state.value = current.copy(ended = SecondFactorSignInEnd.Expired)
            return
        }
        val entered = normalizedSecondFactorAnswer(current.input)
        val answer =
            when (method) {
                SecondFactorStepUpMethod.Totp -> SecondFactorStepUpAnswer.AuthenticatorCode(entered)
                SecondFactorStepUpMethod.BackupCode -> SecondFactorStepUpAnswer.BackupCode(entered)
            }
        _state.value = current.copy(submitting = true, fieldError = null, banner = null)
        val result = verify(pending, answer)
        val next = _state.value.copy(submitting = false)
        _state.value =
            when (result) {
                is SecondFactorSignInResult.SignedIn -> {
                    _outcome.value = SecondFactorSignInOutcome.SignedIn(result.session)
                    next
                }
                SecondFactorSignInResult.CodeInvalid ->
                    next.copy(
                        fieldError =
                            if (method == SecondFactorStepUpMethod.BackupCode) {
                                SecondFactorStepUpFieldError.BackupCodeInvalid
                            } else {
                                SecondFactorStepUpFieldError.CodeInvalid
                            }
                    )
                SecondFactorSignInResult.BackupCodeUsed -> next.copy(fieldError = SecondFactorStepUpFieldError.BackupCodeUsed)
                SecondFactorSignInResult.Expired -> next.copy(ended = SecondFactorSignInEnd.Expired)
                SecondFactorSignInResult.AttemptsExhausted -> next.copy(ended = SecondFactorSignInEnd.AttemptsExhausted)
                is SecondFactorSignInResult.Locked -> next.copy(ended = SecondFactorSignInEnd.Locked(result.lockedUntilEpochMillis))
                SecondFactorSignInResult.Unavailable -> next.copy(banner = SecondFactorSignInBanner.Unavailable)
                is SecondFactorSignInResult.Failed -> next.copy(banner = SecondFactorSignInBanner.Failed(result.serverMessage))
            }
    }

    private inline fun update(transform: (SecondFactorSignInState) -> SecondFactorSignInState) {
        val current = _state.value
        val next = transform(current)
        if (next != current) _state.value = next
    }
}
