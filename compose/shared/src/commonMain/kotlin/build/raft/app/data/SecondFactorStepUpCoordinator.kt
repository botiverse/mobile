package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the answer field is marked. */
enum class SecondFactorStepUpFieldError { CodeInvalid, BackupCodeInvalid, BackupCodeUsed }

/**
 * A refusal shown in a banner under the field. `traceId`: the refusing response's, shown with it
 * (AGENTS.md "Errors carry the trace ID"); null for a local failure.
 */
sealed interface SecondFactorStepUpBanner {
    data class Locked(val lockedUntilEpochMillis: Long?, val traceId: String? = null) : SecondFactorStepUpBanner
    /** 403 `session_family_required`: offer Sign In Again. */
    data object SignInAgain : SecondFactorStepUpBanner
    data class Failed(val serverMessage: String?, val traceId: String? = null) : SecondFactorStepUpBanner
    /** The server refused the passkey's assertion (web `auth.secondFactor.passkeyInvalid`). */
    data class PasskeyInvalid(val traceId: String?) : SecondFactorStepUpBanner
    /** 503 `passkeys_unavailable` (web `settings.passkeys.unavailable`). */
    data class PasskeysUnavailable(val traceId: String?) : SecondFactorStepUpBanner
    /** The system prompt was closed (web `auth.secondFactor.passkeyCancelled`). */
    data object PasskeyCancelled : SecondFactorStepUpBanner
    /** No passkey for Raft on this device: the dialog moved on to the other methods. */
    data object PasskeyNoCredential : SecondFactorStepUpBanner
    /** This device can't use a passkey here: the dialog moved on to the other methods. */
    data object PasskeyUnsupported : SecondFactorStepUpBanner
    /** Continue in browser: the web sheet was closed (or the page's Cancel) before it confirmed. */
    data object WebStepUpCancelled : SecondFactorStepUpBanner
    /** Continue in browser: the request or its code ran out (`error=expired`, 410). */
    data class WebStepUpExpired(val traceId: String? = null) : SecondFactorStepUpBanner
}

/**
 * Continue in browser (iOS, an account with only legacy passkeys; AppWebStepUp.kt): [Ready] to
 * start, [Opening] while the request is made, [Waiting] while the web page is up, [Completing]
 * while the page's code is exchanged.
 */
enum class SecondFactorWebStepUpPhase { Ready, Opening, Waiting, Completing }

/**
 * A web step-up page the dialog is to open now ([RaftAppWebStepUpBrowser]), handing back to
 * [callbackScheme]. [id] tells a late answer from an earlier attempt's.
 */
data class SecondFactorWebStepUpSession(
    val id: Long,
    val url: String,
    val callbackScheme: String,
)

/**
 * A passkey assertion the dialog is to run on the platform ([RaftNativePasskeyAssertion]): the
 * server's options for this attempt. [id] tells a late answer from an earlier attempt's.
 */
data class SecondFactorPasskeyCeremony(
    val id: Long,
    val request: RaftNativePasskeyRequest,
)

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
 * others as links. [method] is [SecondFactorStepUpMethod.Passkey] (first) where this platform can
 * assert one natively; it is null when the server listed only a passkey and this platform can't
 * use it (desktop) or the passkey fell through (none on this device): the dialog then says to do
 * this on the web and has no field. [passkeyCeremony]: a passkey prompt the dialog is to run. [recovery]: signing out a lost device (see
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
    val passkeyCeremony: SecondFactorPasskeyCeremony? = null,
    /**
     * The refused action's web page ([SecondFactorStepUpWebTarget] on the environment's web URL):
     * with no way to answer here ([method] null) the dialog offers Open on the web, to make the
     * change there and confirm it with the passkey. Null: the dialog only says to.
     */
    val webUrl: String? = null,
    /**
     * The web step-up for only legacy passkeys (iOS, [withOnlyOlderPasskeys]): the passkey is
     * checked on a web page and this sign-in verified. With no way to answer here it is Continue in
     * browser ([continuesInBrowser]); beside a code, the link under the field
     * ([webStepUpBesideCode]). Null: not offered (other platforms, or the server has none: 404),
     * and with no way to answer here Open on the web stands in.
     */
    val webStepUp: SecondFactorWebStepUpPhase? = null,
    /** The web page the dialog is to open for Continue in browser. */
    val webStepUpSession: SecondFactorWebStepUpSession? = null,
) {
    /** No way to answer here, no Continue in browser, and the action's web page is known: Open on the web. */
    val opensOnWeb: Boolean
        get() = method == null && webStepUp == null && webUrl != null

    /** No way to answer here, and Continue in browser is offered. */
    val continuesInBrowser: Boolean
        get() = method == null && webStepUp != null

    /** A code answers here, and the web step-up is offered under its field too (#158). */
    val webStepUpBesideCode: Boolean
        get() = method in SECOND_FACTOR_STEP_UP_CODE_METHODS && webStepUp != null

    /** The web step-up is starting, its page is up, or its code is being exchanged. */
    val webStepUpUnderWay: Boolean
        get() = webStepUp != null && webStepUp != SecondFactorWebStepUpPhase.Ready

    /** Checking an answer, or the passkey prompt is up. */
    val busy: Boolean
        get() = submitting || passkeyCeremony != null

    val canSubmit: Boolean
        get() =
            when (method) {
                null -> false
                SecondFactorStepUpMethod.Passkey -> !busy
                // Not while the web step-up beside it runs: one answer at a time.
                else -> !busy && !webStepUpUnderWay && normalizedSecondFactorAnswer(input).isNotEmpty()
            }

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
    /** Continue in browser: start the web step-up and open its page. */
    data object ContinueInBrowser : SecondFactorStepUpIntent
    /** The platform's web session for [SecondFactorStepUpDialogState.webStepUpSession] [sessionId] ended. */
    data class WebStepUpBrowserFinished(
        val sessionId: Long,
        val result: RaftAppWebStepUpBrowserResult,
    ) : SecondFactorStepUpIntent
    /** A web step-up callback handed to the app outside the web session ([RaftAppWebStepUp.callbacks]). */
    data class WebStepUpCallback(val uri: String) : SecondFactorStepUpIntent
    /** The platform's answer to [SecondFactorStepUpDialogState.passkeyCeremony] [ceremonyId]. */
    data class PasskeyAssertionFinished(
        val ceremonyId: Long,
        val result: RaftNativePasskeyAssertionResult,
    ) : SecondFactorStepUpIntent
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
 * The dialog without the passkey: the next method with [banner] saying why, or, with none left,
 * no method (the dialog's own "do this on the web" says it then).
 */
internal fun SecondFactorStepUpDialogState.withoutPasskey(banner: SecondFactorStepUpBanner): SecondFactorStepUpDialogState {
    val rest = methods - SecondFactorStepUpMethod.Passkey
    val next = rest.firstOrNull()
    return copy(
        methods = rest,
        method = next,
        input = "",
        fieldError = null,
        banner = banner.takeIf { next != null },
        submitting = false,
        passkeyCeremony = null,
    )
}

/**
 * "Verify it's you" once Use passkey found only passkeys this platform can't ask for: iOS asks for
 * those under the current RP ID (`app.raft.build`), and the person has only older ones, under
 * `raft.build` (`passkey_rp_unavailable`), which only a web page can use. The passkey leaves the
 * methods and a code, if there is one, takes over saying why ([withoutPasskey]). Where this
 * platform and the server have the web step-up ([webStepUpOffered]), it checks the passkey
 * instead: with no code the dialog goes straight on to it
 * ([SecondFactorStepUpDialogState.continuesInBrowser], #5129); with a code the code entry stays
 * and the web step-up is offered under the field ([SecondFactorStepUpDialogState.webStepUpBesideCode],
 * #158). Without it: the code alone, or the web hand-off (Open on the web).
 */
internal fun SecondFactorStepUpDialogState.withOnlyOlderPasskeys(webStepUpOffered: Boolean): SecondFactorStepUpDialogState {
    val rest = withoutPasskey(SecondFactorStepUpBanner.PasskeyUnsupported)
    return if (webStepUpOffered && rest.webStepUp == null) rest.copy(webStepUp = SecondFactorWebStepUpPhase.Ready) else rest
}

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
 *
 * A passkey (only with [passkeyOptions], where [nativePasskeys]): Use passkey fetches the server's
 * assertion options (asking for [passkeyRpGroup]; with none, and no passkey on this device in the
 * server's default group, once more for the options' `otherRps`), hands them to the dialog as a
 * [SecondFactorPasskeyCeremony] for the platform prompt, and sends the assertion it gets back
 * ([SecondFactorStepUpIntent.PasskeyAssertionFinished]) as the step-up's `webauthn`. No passkey
 * on this device, or none the platform can use, drops the passkey and goes on with the other
 * methods (or the web, with none).
 *
 * The web, with none: the dialog's Open on the web goes to the action's page, [webUrl] of the
 * run's target ([webTarget] unless [run] names another); without a [webUrl] it only says to.
 *
 * Continue in browser (only with [webStepUp], iOS): when the passkey drops out because the person
 * has only legacy passkeys (`passkey_rp_unavailable`, [withOnlyOlderPasskeys]), the dialog offers
 * it instead of Open on the web when no other method is left, and under the code's field when
 * one is (#158). It starts a web step-up for this sign-in (the refused
 * action's `StepUpAction` key: [webStepUpAction], or the one [run] names), keeps the request and
 * its PKCE verifier on disk, has the dialog open the page in a web session, takes the page's
 * callback for that request only, exchanges its code (this sign-in's bearer and the verifier) and
 * settles as verified: the action goes once more. A 404 from the start (flag off, no legacy
 * passkey) drops back to Open on the web for this coordinator.
 */
class SecondFactorStepUpCoordinator(
    private val stepUp: suspend (SecondFactorStepUpAnswer) -> SecondFactorStepUpResult,
    private val onStateChange: (SecondFactorStepUpDialogState?) -> Unit = {},
    private val requestPasswordEmail: suspend (String) -> SecondFactorRecoveryEmailResult = { SecondFactorRecoveryEmailResult.Failed },
    private val requestTwoStepReset: suspend (String) -> SecondFactorRecoveryEmailResult = { SecondFactorRecoveryEmailResult.Failed },
    private val passkeyOptions: (suspend (rp: String?) -> SecondFactorPasskeyOptionsResult)? = null,
    private val nativePasskeys: Boolean = raftNativePasskeyAssertionAvailable(),
    private val passkeyRpGroup: String? = raftNativePasskeyRpGroup(),
    private val webUrl: (suspend (SecondFactorStepUpWebTarget) -> String?)? = null,
    /** The action's web page when [run] names none, read when the dialog opens. */
    private val webTarget: () -> SecondFactorStepUpWebTarget? = { null },
    private val webStepUp: SecondFactorWebStepUp? = null,
    /** The refused action's `StepUpAction` key for the web step-up when [run] names none. */
    private val webStepUpAction: String? = null,
) {
    /** The server answered the web step-up's start with 404: Open on the web from now on. */
    private var webStepUpUnavailable = false
    private var webSessionSeq = 0L
    /** The open dialog's action key, sent with the web step-up's start. */
    private var openAction: String? = null
    /** The web step-up request this dialog put on file (cleared with the dialog). */
    private var openWebGeneration: String? = null

    private var ceremonySeq = 0L

    /**
     * The other RP group to try once when this device has no passkey in the first one: set from
     * the options' `otherRps` only where the platform asked for the server's default group
     * ([passkeyRpGroup] null: Android, where both RP IDs assert), cleared when used or on a new
     * Use passkey.
     */
    private var passkeyFallbackRp: String? = null

    private val _state = MutableStateFlow<SecondFactorStepUpDialogState?>(null)
    val state: StateFlow<SecondFactorStepUpDialogState?> = _state.asStateFlow()

    private var pending: CompletableDeferred<SecondFactorStepUpSettle>? = null

    suspend fun <T> run(request: suspend () -> DataResult<T>): SecondFactorStepUpOutcome<T> = run(webTarget(), webStepUpAction, request)

    /**
     * [run] for an action done on the web at [webTarget] (Open on the web), whose `StepUpAction`
     * key is [action] (Continue in browser).
     */
    suspend fun <T> run(
        webTarget: SecondFactorStepUpWebTarget?,
        action: String? = webStepUpAction,
        request: suspend () -> DataResult<T>,
    ): SecondFactorStepUpOutcome<T> {
        val first = request()
        val challenge = first.secondFactorStepUpChallengeOrNull() ?: return SecondFactorStepUpOutcome.Completed(first)
        if (awaitSettled(challenge, recovery = null, webTarget, action) != SecondFactorStepUpSettle.Verified) return SecondFactorStepUpOutcome.Cancelled
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
        return when (awaitSettled(challenge, recovery, webTarget(), webStepUpAction)) {
            SecondFactorStepUpSettle.Verified -> SecondFactorStepUpSigningOutOutcome.Completed(request())
            SecondFactorStepUpSettle.Cancelled -> SecondFactorStepUpSigningOutOutcome.Cancelled
            SecondFactorStepUpSettle.SignOutInstead -> SecondFactorStepUpSigningOutOutcome.SignOutInstead
        }
    }

    suspend fun accept(intent: SecondFactorStepUpIntent) {
        when (intent) {
            is SecondFactorStepUpIntent.InputChanged -> update { current ->
                if (current.busy) return@update current
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
                if (current.busy || sameAnswer || intent.method !in current.methods) {
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
            is SecondFactorStepUpIntent.PasskeyAssertionFinished -> finishPasskey(intent)
            SecondFactorStepUpIntent.ContinueInBrowser -> continueInBrowser()
            is SecondFactorStepUpIntent.WebStepUpBrowserFinished -> finishWebSession(intent)
            is SecondFactorStepUpIntent.WebStepUpCallback -> acceptWebCallback(intent.uri, fromSession = false)
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
        webTarget: SecondFactorStepUpWebTarget?,
        action: String?,
    ): SecondFactorStepUpSettle {
        pending?.let { return it.await() }
        val deferred = CompletableDeferred<SecondFactorStepUpSettle>()
        pending = deferred
        openAction = action
        openWebGeneration = null
        // A page that can't be named (no session) leaves the dialog without Open on the web.
        val actionWebUrl = webTarget?.let { target -> runCatchingPreservingCancellation { webUrl?.invoke(target) }.getOrNull() }
        val passkey = challenge.passkeyListed && nativePasskeys && passkeyOptions != null
        val methods = listOfNotNull(SecondFactorStepUpMethod.Passkey.takeIf { passkey }) + challenge.usableMethods
        publish(
            SecondFactorStepUpDialogState(
                methods = methods,
                method = methods.firstOrNull(),
                recovery = recovery,
                passkeyListed = challenge.passkeyListed,
                webUrl = actionWebUrl,
            )
        )
        return try {
            deferred.await()
        } finally {
            // Also when the waiting action is cancelled (the page went away): no dialog left open.
            if (pending === deferred) {
                pending = null
                publish(null)
                forgetWebRequest()
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
                SecondFactorStepUpMethod.Passkey -> return startPasskey(current, deferred)
                SecondFactorStepUpMethod.Totp -> SecondFactorStepUpAnswer.AuthenticatorCode(entered)
                SecondFactorStepUpMethod.BackupCode -> SecondFactorStepUpAnswer.BackupCode(entered)
            }
        publish(current.copy(submitting = true, fieldError = null, banner = null))
        answerWith(answer, method, deferred)
    }

    /** Sends [answer] and settles, or shows the refusal. */
    private suspend fun answerWith(
        answer: SecondFactorStepUpAnswer,
        method: SecondFactorStepUpMethod,
        deferred: CompletableDeferred<SecondFactorStepUpSettle>,
    ) {
        val result = stepUp(answer)
        if (pending !== deferred) return
        val shown = _state.value ?: return
        val next = shown.copy(submitting = false)
        val passkey = method == SecondFactorStepUpMethod.Passkey
        when (result) {
            is SecondFactorStepUpResult.Verified,
            SecondFactorStepUpResult.NotEnabled -> settle(SecondFactorStepUpSettle.Verified)
            is SecondFactorStepUpResult.CodeInvalid ->
                publish(
                    when (method) {
                        SecondFactorStepUpMethod.Passkey -> next.copy(banner = SecondFactorStepUpBanner.PasskeyInvalid(result.traceId))
                        SecondFactorStepUpMethod.BackupCode -> next.copy(fieldError = SecondFactorStepUpFieldError.BackupCodeInvalid)
                        SecondFactorStepUpMethod.Totp -> next.copy(fieldError = SecondFactorStepUpFieldError.CodeInvalid)
                    }
                )
            SecondFactorStepUpResult.BackupCodeUsed ->
                publish(
                    if (passkey) {
                        next.copy(banner = SecondFactorStepUpBanner.Failed(null))
                    } else {
                        next.copy(fieldError = SecondFactorStepUpFieldError.BackupCodeUsed)
                    }
                )
            is SecondFactorStepUpResult.PasskeyNotFound ->
                // The passkey answered with was removed meanwhile (web: passkeyInvalid).
                publish(next.copy(banner = SecondFactorStepUpBanner.PasskeyInvalid(result.traceId)))
            else -> publish(next.copy(banner = refusalBanner(result)))
        }
    }

    /** Use passkey: the server's options, then the platform prompt (the dialog runs it). */
    private suspend fun startPasskey(
        current: SecondFactorStepUpDialogState,
        deferred: CompletableDeferred<SecondFactorStepUpSettle>,
    ) {
        passkeyFallbackRp = null
        publish(current.copy(submitting = true, fieldError = null, banner = null))
        requestPasskeyCeremony(passkeyRpGroup, firstAsk = true, deferred = deferred)
    }

    /** Fetches options for [rp] (null: the server's default group) and hands them to the dialog. */
    private suspend fun requestPasskeyCeremony(
        rp: String?,
        firstAsk: Boolean,
        deferred: CompletableDeferred<SecondFactorStepUpSettle>,
    ) {
        val fetch = passkeyOptions ?: return
        val options = fetch(rp)
        if (pending !== deferred) return
        val shown = _state.value?.copy(submitting = false) ?: return
        when (options) {
            is SecondFactorPasskeyOptionsResult.Ready -> {
                if (firstAsk && passkeyRpGroup == null) {
                    passkeyFallbackRp = options.otherRps.firstOrNull { it != options.rp }
                }
                publish(
                    shown.copy(
                        passkeyCeremony =
                            SecondFactorPasskeyCeremony(
                                id = ++ceremonySeq,
                                request = RaftNativePasskeyRequest(options.requestJson, options.options),
                            )
                    )
                )
            }
            is SecondFactorPasskeyOptionsResult.Refused ->
                when (val refusal = options.result) {
                    // No passkey (in the group this platform asked for): go on without it.
                    is SecondFactorStepUpResult.PasskeyNotFound -> publish(shown.withoutPasskey(SecondFactorStepUpBanner.PasskeyNoCredential))
                    // None this platform can use (iOS: only passkeys under the legacy RP ID): the
                    // other methods, or the web with none; where the platform has it, the web
                    // step-up too (Continue in browser, or beside a code).
                    is SecondFactorStepUpResult.PasskeyRpUnavailable -> publish(shown.withOnlyOlderPasskeys(webStepUpOffered()))
                    is SecondFactorStepUpResult.CodeInvalid -> publish(shown.copy(banner = SecondFactorStepUpBanner.PasskeyInvalid(refusal.traceId)))
                    else -> publish(shown.copy(banner = refusalBanner(refusal)))
                }
        }
    }

    /** The platform's answer to the passkey prompt. */
    private suspend fun finishPasskey(intent: SecondFactorStepUpIntent.PasskeyAssertionFinished) {
        val current = _state.value ?: return
        if (current.passkeyCeremony?.id != intent.ceremonyId) return
        val deferred = pending ?: return
        val shown = current.copy(passkeyCeremony = null)
        when (val result = intent.result) {
            is RaftNativePasskeyAssertionResult.Success -> {
                val assertion = parseSharedPasskeyAssertionResponse(result.responseJson)
                if (assertion == null) {
                    publish(shown.copy(banner = SecondFactorStepUpBanner.Failed(null)))
                    return
                }
                publish(shown.copy(submitting = true, banner = null))
                answerWith(SecondFactorStepUpAnswer.Passkey(assertion), SecondFactorStepUpMethod.Passkey, deferred)
            }
            RaftNativePasskeyAssertionResult.Cancelled -> publish(shown.copy(banner = SecondFactorStepUpBanner.PasskeyCancelled))
            RaftNativePasskeyAssertionResult.NoCredential -> {
                val fallback = passkeyFallbackRp
                passkeyFallbackRp = null
                if (fallback != null) {
                    // None in the first group on this device: ask once for the person's other one.
                    publish(shown.copy(submitting = true, banner = null))
                    requestPasskeyCeremony(fallback, firstAsk = false, deferred = deferred)
                } else {
                    publish(shown.withoutPasskey(SecondFactorStepUpBanner.PasskeyNoCredential))
                }
            }
            RaftNativePasskeyAssertionResult.Unsupported -> publish(shown.withoutPasskey(SecondFactorStepUpBanner.PasskeyUnsupported))
            is RaftNativePasskeyAssertionResult.Error -> publish(shown.copy(banner = SecondFactorStepUpBanner.Failed(null)))
        }
    }

    /** Whether the open dialog can have the web step-up: the platform and server have one. */
    private fun webStepUpOffered(): Boolean =
        // Start needs the action's key (400 `invalid_action` without): none, no web step-up.
        webStepUp != null && openAction != null && !webStepUpUnavailable

    /**
     * Continue in browser, or beside a code Verify with your passkey in the browser: the request,
     * then the page in a web session (the dialog opens it).
     */
    private suspend fun continueInBrowser() {
        val current = _state.value ?: return
        val web = webStepUp ?: return
        // Not while a code is being checked (or the passkey prompt is up).
        if (current.webStepUp != SecondFactorWebStepUpPhase.Ready || current.busy) return
        val deferred = pending ?: return
        val action = openAction ?: return
        publish(current.copy(webStepUp = SecondFactorWebStepUpPhase.Opening, webStepUpSession = null, banner = null))
        forgetWebRequest()
        val pkce = web.pkce()
        val generation = web.generation()
        val started =
            runCatchingPreservingCancellation {
                web.start(AppWebStepUpStartBody(returnUri = web.returnUri, codeChallenge = pkce.challenge, action = action))
            }.getOrElse { AppWebStepUpStartResult.Failed(serverCode = "threw") }
        if (pending !== deferred) return
        val shown = _state.value ?: return
        if (shown.webStepUp != SecondFactorWebStepUpPhase.Opening) return
        when (started) {
            is AppWebStepUpStartResult.Started -> {
                if (!isAllowedAppLoginUrl(started.stepUpUrl)) {
                    web.log("app_web_step_up_start result=failed reason=url_not_allowed")
                    publish(shown.copy(webStepUp = SecondFactorWebStepUpPhase.Ready, banner = SecondFactorStepUpBanner.Failed(null)))
                    return
                }
                web.pending.save(
                    AppWebStepUpPending(generation, started.requestId, pkce.verifier, web.returnUri, web.pending.now())
                )
                openWebGeneration = generation
                web.log("app_web_step_up_start result=started")
                publish(
                    shown.copy(
                        webStepUp = SecondFactorWebStepUpPhase.Waiting,
                        webStepUpSession =
                            SecondFactorWebStepUpSession(
                                id = ++webSessionSeq,
                                url = started.stepUpUrl,
                                callbackScheme = web.returnUri.substringBefore("://"),
                            ),
                    )
                )
            }
            AppWebStepUpStartResult.Unavailable -> {
                // Flag off, or no legacy passkey after all: Open on the web (#5035), from now on;
                // beside a code, the code alone again, saying why.
                web.log("app_web_step_up_start result=unavailable")
                webStepUpUnavailable = true
                publish(shown.copy(webStepUp = null, banner = SecondFactorStepUpBanner.PasskeyUnsupported.takeIf { shown.method != null }))
            }
            AppWebStepUpStartResult.NotEnabled -> settle(SecondFactorStepUpSettle.Verified)
            AppWebStepUpStartResult.SessionFamilyRequired ->
                publish(shown.copy(webStepUp = SecondFactorWebStepUpPhase.Ready, banner = SecondFactorStepUpBanner.SignInAgain))
            is AppWebStepUpStartResult.Failed -> {
                web.log("app_web_step_up_start result=failed code=${started.serverCode}")
                publish(
                    shown.copy(
                        webStepUp = SecondFactorWebStepUpPhase.Ready,
                        banner = SecondFactorStepUpBanner.Failed(started.serverMessage, started.traceId),
                    )
                )
            }
        }
    }

    /** The web session ended: its callback, or the sheet closed. */
    private suspend fun finishWebSession(intent: SecondFactorStepUpIntent.WebStepUpBrowserFinished) {
        val current = _state.value ?: return
        if (current.webStepUpSession?.id != intent.sessionId) return
        val shown = current.copy(webStepUpSession = null)
        when (val result = intent.result) {
            is RaftAppWebStepUpBrowserResult.Callback -> {
                publish(shown)
                acceptWebCallback(result.uri, fromSession = true)
            }
            RaftAppWebStepUpBrowserResult.Closed ->
                publish(
                    if (shown.webStepUp == SecondFactorWebStepUpPhase.Waiting) {
                        forgetWebRequest()
                        shown.copy(webStepUp = SecondFactorWebStepUpPhase.Ready, banner = SecondFactorStepUpBanner.WebStepUpCancelled)
                    } else {
                        shown
                    }
                )
            is RaftAppWebStepUpBrowserResult.Failed ->
                publish(
                    if (shown.webStepUp == SecondFactorWebStepUpPhase.Waiting) {
                        webStepUp?.log("app_web_step_up_session result=failed reason=${result.reason}")
                        forgetWebRequest()
                        shown.copy(webStepUp = SecondFactorWebStepUpPhase.Ready, banner = SecondFactorStepUpBanner.Failed(null))
                    } else {
                        shown
                    }
                )
        }
    }

    /**
     * The page's callback: only for the request on file (anything else is stale and ignored; one
     * the web session itself handed back ends the attempt), exchanged once, then Verified.
     */
    private suspend fun acceptWebCallback(uri: String, fromSession: Boolean) {
        val web = webStepUp ?: return
        val current = _state.value ?: return
        val deferred = pending ?: return
        fun exit(reason: String) = web.log("app_web_step_up_callback result=$reason source=${if (fromSession) "session" else "open_url"}")
        if (current.webStepUp != SecondFactorWebStepUpPhase.Waiting) return exit("ignored_not_waiting")
        val giveUp = { banner: SecondFactorStepUpBanner ->
            publish(current.copy(webStepUp = SecondFactorWebStepUpPhase.Ready, webStepUpSession = null, banner = banner))
        }
        val callback = parseAppLoginCallback(uri)
        val onFile = web.pending.read()
        if (callback == null || onFile == null || onFile.requestId != callback.requestId || onFile.returnUri != callback.returnUri) {
            exit(if (callback == null) "ignored_malformed" else "stale")
            // The session is over without this attempt's answer: let the person try again.
            if (fromSession) giveUp(SecondFactorStepUpBanner.Failed(null))
            return
        }
        // Remove before exchanging: a repeated callback cannot use the code again.
        web.pending.clear()
        openWebGeneration = null
        when {
            !web.pending.isFresh(onFile) -> return giveUp(SecondFactorStepUpBanner.WebStepUpExpired()).also { exit("expired_local") }
            callback.error == "expired" -> return giveUp(SecondFactorStepUpBanner.WebStepUpExpired()).also { exit("expired") }
            callback.error == "access_denied" -> return giveUp(SecondFactorStepUpBanner.WebStepUpCancelled).also { exit("denied") }
            callback.error.isNotBlank() -> return giveUp(SecondFactorStepUpBanner.Failed(null)).also { exit("callback_error") }
        }
        publish(
            current.copy(
                webStepUp = SecondFactorWebStepUpPhase.Completing,
                webStepUpSession = null,
                submitting = true,
                banner = null,
            )
        )
        val result =
            runCatchingPreservingCancellation {
                web.complete(AppWebStepUpCompleteBody(code = callback.code, codeVerifier = onFile.verifier, returnUri = onFile.returnUri))
            }.getOrElse { AppWebStepUpCompleteResult.Failed(serverCode = "threw") }
        if (pending !== deferred) return
        val shown = _state.value?.copy(submitting = false, webStepUp = SecondFactorWebStepUpPhase.Ready) ?: return
        when (result) {
            AppWebStepUpCompleteResult.Verified -> {
                exit("verified")
                settle(SecondFactorStepUpSettle.Verified)
            }
            AppWebStepUpCompleteResult.SessionFamilyRequired -> {
                exit("exchange_session_family_required")
                publish(shown.copy(banner = SecondFactorStepUpBanner.SignInAgain))
            }
            is AppWebStepUpCompleteResult.Expired -> {
                exit("exchange_expired")
                publish(shown.copy(banner = SecondFactorStepUpBanner.WebStepUpExpired(result.traceId)))
            }
            is AppWebStepUpCompleteResult.Failed -> {
                exit("exchange_failed code=${result.serverCode}")
                publish(shown.copy(banner = SecondFactorStepUpBanner.Failed(result.serverMessage, result.traceId)))
            }
        }
    }

    /** Drops the web step-up request this dialog put on file (not a newer one of another dialog). */
    private fun forgetWebRequest() {
        val generation = openWebGeneration ?: return
        openWebGeneration = null
        val web = webStepUp ?: return
        if (web.pending.read()?.generation == generation) web.pending.clear()
    }

    private fun refusalBanner(result: SecondFactorStepUpResult): SecondFactorStepUpBanner =
        when (result) {
            is SecondFactorStepUpResult.Locked -> SecondFactorStepUpBanner.Locked(result.lockedUntilEpochMillis, result.traceId)
            SecondFactorStepUpResult.SessionFamilyRequired -> SecondFactorStepUpBanner.SignInAgain
            is SecondFactorStepUpResult.PasskeysUnavailable -> SecondFactorStepUpBanner.PasskeysUnavailable(result.traceId)
            is SecondFactorStepUpResult.PasskeyRpUnavailable -> SecondFactorStepUpBanner.PasskeyUnsupported
            is SecondFactorStepUpResult.PasskeyNotFound -> SecondFactorStepUpBanner.PasskeyInvalid(result.traceId)
            is SecondFactorStepUpResult.Failed -> SecondFactorStepUpBanner.Failed(result.serverMessage, result.traceId)
            is SecondFactorStepUpResult.CodeInvalid,
            SecondFactorStepUpResult.BackupCodeUsed,
            SecondFactorStepUpResult.NotEnabled,
            is SecondFactorStepUpResult.Verified -> SecondFactorStepUpBanner.Failed(null)
        }

    private fun settle(outcome: SecondFactorStepUpSettle) {
        val deferred = pending ?: return
        pending = null
        forgetWebRequest()
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
