package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Who a Sign out is for (web SignedInDevicesSection `Target`, with SignOutDeviceDialog's `scope`). */
@RaftCoreInternalApi sealed interface SignedInDevicesSignOutTarget {
    data class One(val device: SignedInDevice) : SignedInDevicesSignOutTarget
    /** Every other listed sign-in. */
    data object Others : SignedInDevicesSignOutTarget
    /** No other sign-in is listed, but ones signed out earlier still have Computers or agent keys working. */
    data object Leftovers : SignedInDevicesSignOutTarget
}

/**
 * The open sign-out question (web SignOutDeviceDialog). [reach] is what `lost_device` would also
 * disconnect; with some, the dialog asks why ([hasChoice]); without, it is one question, Sign out.
 * Leftovers only disconnect (`lost_device`).
 */
@RaftCoreInternalApi data class SignedInDevicesSignOutDialog(
    val target: SignedInDevicesSignOutTarget,
    val reach: SignedInDeviceLostReach,
    val reason: SignedInDeviceRevokeReason = SignedInDeviceRevokeReason.SignOut,
    /** Named disconnect preview for `lost_device` (`GET /api/me/sessions/disconnect-preview`); null while loading, unread, or failed. */
    val disconnectPreview: SecurityRevokePreview? = null,
    val submitting: Boolean = false,
    /** The last try failed: "Couldn't sign out. Try again." in the dialog. */
    val failed: Boolean = false,
) {
    val leftovers: Boolean get() = target == SignedInDevicesSignOutTarget.Leftovers
    val hasChoice: Boolean get() = !leftovers && reach.any

    /** What Confirm sends. */
    val reasonToSend: SignedInDeviceRevokeReason
        get() =
            when {
                leftovers -> SignedInDeviceRevokeReason.LostDevice
                hasChoice -> reason
                else -> SignedInDeviceRevokeReason.SignOut
            }

    /** Confirm is red (disconnects too) rather than the warning colour. */
    val disconnects: Boolean get() = reasonToSend == SignedInDeviceRevokeReason.LostDevice
}

/** The password dialog over the sign-out question (web ReauthDialog / NoPasswordDialog). */
@RaftCoreInternalApi data class SignedInDevicesReauthDialog(
    /** false: the account has no password; the dialog only says to sign in again. */
    val password: Boolean,
    val input: String = "",
    val submitting: Boolean = false,
    val passwordIncorrect: Boolean = false,
    /** Too many wrong passwords; [lockedUntilEpochMillis] null when the time is unknown. */
    val locked: Boolean = false,
    val lockedUntilEpochMillis: Long? = null,
    val failed: Boolean = false,
) {
    val canSubmit: Boolean get() = password && !submitting && input.isNotEmpty()
}

/** What a finished sign-out says (web SignedInDevicesSection `announce`); [cascadePending] adds "Disconnecting the rest shortly." */
@RaftCoreInternalApi sealed interface SignedInDevicesNotice {
    val cascadePending: Boolean

    data class SignedOut(val platform: SignedInDevicePlatform, val lost: Boolean, override val cascadePending: Boolean) : SignedInDevicesNotice
    data class SignedOutAll(val count: Int, override val cascadePending: Boolean) : SignedInDevicesNotice
    data class SignedOutAllLost(override val cascadePending: Boolean) : SignedInDevicesNotice
    data class LeftoversDone(override val cascadePending: Boolean) : SignedInDevicesNotice
}

@RaftCoreInternalApi data class SharedSignedInDevicesState(
    /** The last list read; kept when a later read fails. */
    val list: SignedInDeviceList? = null,
    /** The server has no such list (404): no section. */
    val unavailable: Boolean = false,
    val loading: Boolean = false,
    /** The last read failed; with no list yet, the section shows the failure and Try again. */
    val loadFailed: Boolean = false,
    val signOut: SignedInDevicesSignOutDialog? = null,
    val reauth: SignedInDevicesReauthDialog? = null,
    /** "Verify it's you" of a `lost_device` revoke (SecondFactorStepUpCoordinator). */
    val stepUp: SecondFactorStepUpDialogState? = null,
    val notice: SignedInDevicesNotice? = null,
) {
    /**
     * Web SignedInDevicesSection: nothing while the first read runs or when the server has no such
     * list, nothing for an empty list (the server always lists the asking sign-in), else the rows or
     * the failure.
     */
    val visible: Boolean
        get() =
            when {
                unavailable -> false
                list != null -> list.sessions.isNotEmpty()
                else -> loadFailed
            }
}

@RaftCoreInternalApi sealed interface SharedSignedInDevicesIntent {
    /** The page shown, the app back in the foreground, Try again. */
    data object Refresh : SharedSignedInDevicesIntent
    /** The account's password state and address (Account settings): the proof dialogs need them. */
    data class AccountKnown(val passwordConfigured: Boolean?, val email: String?) : SharedSignedInDevicesIntent
    data class OpenSignOut(val device: SignedInDevice) : SharedSignedInDevicesIntent
    data object OpenSignOutOthers : SharedSignedInDevicesIntent
    data class ChooseReason(val reason: SignedInDeviceRevokeReason) : SharedSignedInDevicesIntent
    data object Confirm : SharedSignedInDevicesIntent
    data object CloseSignOut : SharedSignedInDevicesIntent
    data class ReauthInput(val text: String) : SharedSignedInDevicesIntent
    data object ReauthSubmit : SharedSignedInDevicesIntent
    data object ReauthClose : SharedSignedInDevicesIntent
    data class StepUp(val intent: SecondFactorStepUpIntent) : SharedSignedInDevicesIntent
    data object NoticeShown : SharedSignedInDevicesIntent
}

private val SIGNED_IN_DEVICES_GONE_CODES: Set<String?> = setOf(SIGNED_IN_DEVICES_SESSION_NOT_FOUND_CODE, SIGNED_IN_DEVICES_CURRENT_SESSION_CODE)

/** How one send went. */
private sealed interface SignedInDevicesSendOutcome {
    data class Done(val notice: SignedInDevicesNotice) : SignedInDevicesSendOutcome
    /** The password or "Verify it's you" was closed: nothing happened, the question stays open. */
    data object Abandoned : SignedInDevicesSendOutcome
    /** Can't verify? › Sign it out now with your password. */
    data object SignOutInstead : SignedInDevicesSendOutcome
    data class Failed(val serverCode: String?) : SignedInDevicesSendOutcome
}

/**
 * Settings › Account › Signed-in devices (web SignedInDevicesSection). Reads are single-flight and
 * keep the rows on a failure. Signing out asks for the proof the server refuses without: 403
 * `reauth_required` opens the password dialog (or, without a password, says to sign in again) and
 * sends again; 403 `second_factor_step_up_required` (`lost_device` only) goes through the app's
 * "Verify it's you" ([SecondFactorStepUpCoordinator.runSigningOut], with "Lost the device with your
 * authenticator?"), each at most once. A sign-in already gone (`session_not_found`,
 * `current_session`) closes the question quietly; the list is read again after every sign-out.
 */
@RaftCoreInternalApi class SharedSignedInDevicesStore(
    private val dataSource: SignedInDevicesDataSource,
    private val scope: CoroutineScope,
    /** Settings › Account's web page for "Verify it's you"'s Open on the web; null: none offered. */
    private val stepUpWebUrl: (suspend (SecondFactorStepUpWebTarget) -> String?)? = null,
) {
    private val mutableState = MutableStateFlow(SharedSignedInDevicesState())
    val state: StateFlow<SharedSignedInDevicesState> = mutableState.asStateFlow()

    private val stepUp =
        SecondFactorStepUpCoordinator(
            stepUp = { answer -> dataSource.stepUp(answer) },
            onStateChange = { dialog -> update { it.copy(stepUp = dialog) } },
            requestPasswordEmail = { email -> dataSource.requestPasswordEmail(email) },
            requestTwoStepReset = { email -> dataSource.requestTwoStepReset(email) },
            passkeyOptions = { rp -> dataSource.passkeyAssertionOptions(rp) },
            webUrl = stepUpWebUrl,
            webTarget = { SecondFactorStepUpWebTarget.Account },
            webStepUp = dataSource.webStepUp(),
            // slock stepUpGate.ts: a lost device's sign-out.
            webStepUpAction = "session_revoke",
        )

    private var refreshJob: Job? = null
    private var commitJob: Job? = null
    private var previewJob: Job? = null
    private var previewGeneration: Long = 0
    private var pendingReauth: CompletableDeferred<Boolean>? = null
    private var passwordConfigured: Boolean? = null
    private var email: String? = null

    fun dispatch(intent: SharedSignedInDevicesIntent) {
        when (intent) {
            SharedSignedInDevicesIntent.Refresh -> refresh()
            is SharedSignedInDevicesIntent.AccountKnown -> {
                passwordConfigured = intent.passwordConfigured
                email = intent.email?.takeIf { it.isNotBlank() }
            }
            is SharedSignedInDevicesIntent.OpenSignOut -> openSignOut(SignedInDevicesSignOutTarget.One(intent.device))
            SharedSignedInDevicesIntent.OpenSignOutOthers -> {
                val list = mutableState.value.list ?: return
                openSignOut(if (list.othersListed) SignedInDevicesSignOutTarget.Others else SignedInDevicesSignOutTarget.Leftovers)
            }
            is SharedSignedInDevicesIntent.ChooseReason -> update { state ->
                val dialog = state.signOut?.takeIf { !it.submitting } ?: return@update state
                state.copy(signOut = dialog.copy(reason = intent.reason))
            }
            SharedSignedInDevicesIntent.Confirm -> confirm()
            SharedSignedInDevicesIntent.CloseSignOut -> {
                if (mutableState.value.signOut?.submitting == true) return
                cancelDisconnectPreview()
                update { it.copy(signOut = null) }
            }
            is SharedSignedInDevicesIntent.ReauthInput -> update { state ->
                val dialog = state.reauth?.takeIf { !it.submitting } ?: return@update state
                state.copy(reauth = dialog.copy(input = intent.text, passwordIncorrect = false))
            }
            SharedSignedInDevicesIntent.ReauthSubmit -> submitReauth()
            SharedSignedInDevicesIntent.ReauthClose -> {
                if (mutableState.value.reauth?.submitting == true) return
                settleReauth(false)
            }
            is SharedSignedInDevicesIntent.StepUp -> scope.launch { stepUp.accept(intent.intent) }
            SharedSignedInDevicesIntent.NoticeShown -> update { it.copy(notice = null) }
        }
    }

    private inline fun update(transform: (SharedSignedInDevicesState) -> SharedSignedInDevicesState) {
        val current = mutableState.value
        val next = transform(current)
        if (next != current) mutableState.value = next
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        update { it.copy(loading = true) }
        refreshJob =
            scope.launch {
                when (val result = dataSource.list()) {
                    is DataResult.Content -> {
                        val list = result.data
                        update { state ->
                            if (list == null) {
                                cancelDisconnectPreview()
                                SharedSignedInDevicesState(unavailable = true)
                            } else {
                                state.copy(list = list, unavailable = false, loading = false, loadFailed = false)
                            }
                        }
                    }
                    // A failed re-read keeps the rows on screen.
                    else -> update { it.copy(loading = false, loadFailed = true) }
                }
            }
    }

    private fun cancelDisconnectPreview() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
    }

    private fun openSignOut(target: SignedInDevicesSignOutTarget) {
        val list = mutableState.value.list ?: return
        if (mutableState.value.signOut != null) return
        val reach =
            when (target) {
                is SignedInDevicesSignOutTarget.One -> target.device.lostDeviceReach
                else -> list.othersLostDeviceReach
            }
        cancelDisconnectPreview()
        update { it.copy(signOut = SignedInDevicesSignOutDialog(target = target, reach = reach)) }
        // Read the named preview only when at least one Computer is counted (`slock#17081`).
        if (reach.computers <= 0) return
        val generation = ++previewGeneration
        val previewTarget =
            when (target) {
                is SignedInDevicesSignOutTarget.One -> SignedInDisconnectPreviewTarget.LostDeviceOne(target.device.id)
                SignedInDevicesSignOutTarget.Others,
                SignedInDevicesSignOutTarget.Leftovers -> SignedInDisconnectPreviewTarget.LostDeviceOthers
            }
        previewJob =
            scope.launch {
                val preview = dataSource.disconnectPreview(previewTarget)
                if (previewGeneration != generation) return@launch
                update { state ->
                    val dialog = state.signOut?.takeIf { it.target == target } ?: return@update state
                    state.copy(signOut = dialog.copy(disconnectPreview = preview))
                }
            }
    }

    private fun confirm() {
        val dialog = mutableState.value.signOut ?: return
        if (dialog.submitting || commitJob?.isActive == true) return
        update { it.copy(signOut = dialog.copy(submitting = true, failed = false)) }
        commitJob =
            scope.launch {
                val first = send(dialog.target, dialog.reasonToSend)
                // Can't verify? › Sign it out now with your password: the same sign-in(s), `sign_out`.
                val outcome =
                    if (first == SignedInDevicesSendOutcome.SignOutInstead) {
                        send(dialog.target, SignedInDeviceRevokeReason.SignOut)
                    } else {
                        first
                    }
                when (outcome) {
                    is SignedInDevicesSendOutcome.Done -> {
                        val notice = outcome.notice
                        cancelDisconnectPreview()
                        update { it.copy(signOut = null, notice = notice) }
                        refresh()
                    }
                    is SignedInDevicesSendOutcome.Failed ->
                        if (outcome.serverCode in SIGNED_IN_DEVICES_GONE_CODES) {
                            // Gone meanwhile (signed out elsewhere, expired): the list catches up, nothing to say.
                            cancelDisconnectPreview()
                            update { it.copy(signOut = null) }
                            refresh()
                        } else {
                            update { state -> state.copy(signOut = state.signOut?.copy(submitting = false, failed = true)) }
                        }
                    // Closed the password or "Verify it's you": nothing happened; the choice stays open.
                    SignedInDevicesSendOutcome.Abandoned,
                    SignedInDevicesSendOutcome.SignOutInstead ->
                        update { state -> state.copy(signOut = state.signOut?.copy(submitting = false)) }
                }
            }
    }

    /** Sends [reason] for [target] with the proof it asks for, and says what happened. */
    private suspend fun send(target: SignedInDevicesSignOutTarget, reason: SignedInDeviceRevokeReason): SignedInDevicesSendOutcome {
        val othersListed = mutableState.value.list?.othersListed == true
        val recovery = recoveryFor(target, reason, othersListed)
        return when (target) {
            is SignedInDevicesSignOutTarget.One ->
                when (val proven = withProof(recovery) { dataSource.revoke(target.device.id, reason) }) {
                    is ProofOutcome.Done ->
                        SignedInDevicesSendOutcome.Done(
                            SignedInDevicesNotice.SignedOut(
                                platform = target.device.platform,
                                lost = proven.value == SignedInDeviceCascade.Done,
                                cascadePending = proven.value == SignedInDeviceCascade.Pending,
                            )
                        )
                    is ProofOutcome.Other -> proven.outcome
                }
            SignedInDevicesSignOutTarget.Others,
            SignedInDevicesSignOutTarget.Leftovers ->
                when (val proven = withProof(recovery) { dataSource.revokeOthers(reason) }) {
                    is ProofOutcome.Done -> {
                        val pending = proven.value.cascade == SignedInDeviceCascade.Pending
                        SignedInDevicesSendOutcome.Done(
                            when {
                                !othersListed -> SignedInDevicesNotice.LeftoversDone(pending)
                                proven.value.cascade == SignedInDeviceCascade.Done -> SignedInDevicesNotice.SignedOutAllLost(pending)
                                else -> SignedInDevicesNotice.SignedOutAll(proven.value.revoked, pending)
                            }
                        )
                    }
                    is ProofOutcome.Other -> proven.outcome
                }
        }
    }

    /**
     * Web `send`: a `lost_device` revoke whose second step can't be passed offers, under Can't
     * verify?, the password email (a first password without one) and, with a password, `sign_out`
     * with it alone; not for the leftovers (nothing listed to sign out), which get the two-step
     * reset only. No address known: no Can't verify? at all.
     */
    private fun recoveryFor(
        target: SignedInDevicesSignOutTarget,
        reason: SignedInDeviceRevokeReason,
        othersListed: Boolean,
    ): SecondFactorStepUpRecovery? {
        val address = email ?: return null
        val password = passwordConfigured != false
        val lostDevice =
            if (reason == SignedInDeviceRevokeReason.LostDevice && (target is SignedInDevicesSignOutTarget.One || othersListed)) {
                SecondFactorStepUpLostDevice(
                    signOut =
                        when {
                            !password -> null
                            target is SignedInDevicesSignOutTarget.One -> SecondFactorStepUpSignOutScope.One
                            else -> SecondFactorStepUpSignOutScope.Others
                        },
                    password = password,
                )
            } else {
                null
            }
        return SecondFactorStepUpRecovery(email = address, lostDevice = lostDevice)
    }

    private sealed interface ProofOutcome<out T> {
        data class Done<T>(val value: T) : ProofOutcome<T>
        data class Other(val outcome: SignedInDevicesSendOutcome) : ProofOutcome<Nothing>
    }

    /** The request with the proof it asks for: the password, then a second step, each once (web `withProof`). */
    private suspend fun <T> withProof(
        recovery: SecondFactorStepUpRecovery?,
        request: suspend () -> DataResult<T>,
    ): ProofOutcome<T> {
        suspend fun proven(): SecondFactorStepUpSigningOutOutcome<T> =
            if (recovery != null) {
                stepUp.runSigningOut(recovery, request)
            } else {
                when (val outcome = stepUp.run(request)) {
                    is SecondFactorStepUpOutcome.Completed -> SecondFactorStepUpSigningOutOutcome.Completed(outcome.result)
                    SecondFactorStepUpOutcome.Cancelled -> SecondFactorStepUpSigningOutOutcome.Cancelled
                }
            }

        var outcome = proven()
        if (outcome is SecondFactorStepUpSigningOutOutcome.Completed && outcome.result.serverCode() == SIGNED_IN_DEVICES_REAUTH_REQUIRED_CODE) {
            if (!askReauth()) return ProofOutcome.Other(SignedInDevicesSendOutcome.Abandoned)
            outcome = proven()
        }
        return when (outcome) {
            is SecondFactorStepUpSigningOutOutcome.Completed ->
                when (val result = outcome.result) {
                    is DataResult.Content -> ProofOutcome.Done(result.data)
                    else -> ProofOutcome.Other(SignedInDevicesSendOutcome.Failed(result.serverCode()))
                }
            SecondFactorStepUpSigningOutOutcome.Cancelled -> ProofOutcome.Other(SignedInDevicesSendOutcome.Abandoned)
            SecondFactorStepUpSigningOutOutcome.SignOutInstead -> ProofOutcome.Other(SignedInDevicesSendOutcome.SignOutInstead)
        }
    }

    private fun DataResult<*>.serverCode(): String? = (this as? DataResult.Error)?.error?.serverCode

    /** The password dialog over the question; true once this sign-in re-authenticated. */
    private suspend fun askReauth(): Boolean {
        pendingReauth?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        pendingReauth = deferred
        update { it.copy(reauth = SignedInDevicesReauthDialog(password = passwordConfigured != false)) }
        return try {
            deferred.await()
        } finally {
            if (pendingReauth === deferred) {
                pendingReauth = null
                update { it.copy(reauth = null) }
            }
        }
    }

    private fun settleReauth(verified: Boolean) {
        val deferred = pendingReauth ?: return
        pendingReauth = null
        update { it.copy(reauth = null) }
        deferred.complete(verified)
    }

    private fun submitReauth() {
        val dialog = mutableState.value.reauth ?: return
        if (!dialog.canSubmit) return
        val deferred = pendingReauth ?: return
        update { it.copy(reauth = dialog.copy(submitting = true, passwordIncorrect = false, locked = false, failed = false)) }
        scope.launch {
            val result = dataSource.reauthenticateWithPassword(dialog.input)
            if (pendingReauth !== deferred) return@launch
            if (result == SignedInDevicesReauthResult.Verified) {
                settleReauth(true)
                return@launch
            }
            update { state ->
                val shown = state.reauth ?: return@update state
                state.copy(
                    reauth =
                        when (result) {
                            SignedInDevicesReauthResult.PasswordIncorrect -> shown.copy(submitting = false, passwordIncorrect = true)
                            is SignedInDevicesReauthResult.Locked ->
                                shown.copy(submitting = false, locked = true, lockedUntilEpochMillis = result.lockedUntilEpochMillis)
                            else -> shown.copy(submitting = false, failed = true)
                        }
                )
            }
        }
    }
}
