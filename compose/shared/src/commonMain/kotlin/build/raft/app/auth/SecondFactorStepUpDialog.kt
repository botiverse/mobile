package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import build.raft.app.data.SecondFactorStepUpBanner
import build.raft.app.data.SecondFactorStepUpDialogState
import build.raft.app.data.SecondFactorStepUpFieldError
import build.raft.app.data.SecondFactorStepUpIntent
import build.raft.app.data.SecondFactorStepUpMethod
import build.raft.app.data.SecondFactorRecoveryEmailResult
import build.raft.app.data.SecondFactorStepUpLostDevice
import build.raft.app.data.SecondFactorStepUpRecovery
import build.raft.app.data.SecondFactorStepUpSignOutScope
import build.raft.app.data.SecondFactorStepUpView
import build.raft.app.data.RaftNativePasskeyAssertion
import build.raft.app.data.rememberRaftNativePasskeyAssertion
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.ui.text.buildAnnotatedString
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal const val SECOND_FACTOR_STEP_UP_DIALOG_TAG = "second-factor-step-up-dialog"

/**
 * "Verify it's you" (web VerifyItsYouDialog, slock packages/web/src/components/settings/): the
 * step-up of a refused sensitive action. A passkey (Use passkey, where the platform can assert
 * one: [passkeyAssertion] runs the coordinator's [SecondFactorStepUpDialogState.passkeyCeremony]),
 * an authenticator code or a backup code (the others as links under it); a wrong or used code
 * under the field, a refused passkey, a lock, Sign In Again or any other refusal in a banner,
 * with the server's trace ID. Only a passkey listed and none usable here: a banner saying to do
 * this on the web and Close. A scrim tap does not close it; Cancel, the close button and Back do (not
 * while the answer is being checked). With a [SecondFactorStepUpDialogState.recovery] (signing out
 * a lost device) it also offers "Lost the device with your authenticator?"
 * ([SecondFactorStepUpRecoveryPage]).
 */
@Composable
internal fun SecondFactorStepUpDialog(
    state: SecondFactorStepUpDialogState,
    onIntent: (SecondFactorStepUpIntent) -> Unit,
    /** What the step-up is for (web SecondFactorStepUpHost `reason`); changing how you sign in by default. */
    description: String = stringResource(Res.string.settings_step_up_description),
    formatClock: (Long) -> String = ::secondFactorStepUpLocalClock,
    passkeyAssertion: RaftNativePasskeyAssertion = rememberRaftNativePasskeyAssertion(),
) {
    // The passkey prompt, once per attempt; its answer goes back to the coordinator.
    val ceremony = state.passkeyCeremony
    val currentOnIntent by rememberUpdatedState(onIntent)
    LaunchedEffect(ceremony?.id) {
        if (ceremony != null) {
            val result = passkeyAssertion.assert(ceremony.request)
            currentOnIntent(SecondFactorStepUpIntent.PasskeyAssertionFinished(ceremony.id, result))
        }
    }
    // Signing out a lost device: "Lost the device with your authenticator?" and its pages.
    val recovery = state.recovery
    if (recovery != null && state.view != SecondFactorStepUpView.Answer) {
        SecondFactorStepUpRecoveryPage(state, recovery, onIntent)
        return
    }
    val dismiss = { if (!state.submitting) onIntent(SecondFactorStepUpIntent.Cancel) }
    val method = state.method
    RaftDialog(
        onDismissRequest = dismiss,
        title = stringResource(Res.string.settings_step_up_title),
        modifier = Modifier.testTag(SECOND_FACTOR_STEP_UP_DIALOG_TAG),
        size = RaftDialogSize.Sm,
        description = description,
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            if (method == null) {
                RaftButton(
                    text = stringResource(Res.string.common_close),
                    onClick = dismiss,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg
                )
            } else {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = dismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !state.submitting
                )
                RaftButton(
                    text =
                        stringResource(
                            if (method == SecondFactorStepUpMethod.Passkey) {
                                Res.string.settings_step_up_passkey_button
                            } else {
                                Res.string.settings_step_up_verify
                            }
                        ),
                    onClick = { onIntent(SecondFactorStepUpIntent.Submit) },
                    modifier = Modifier.testTag(SECOND_FACTOR_STEP_UP_SUBMIT_TAG),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    enabled = state.canSubmit || state.busy,
                    loading = state.busy,
                    loadingLabel = stringResource(Res.string.settings_step_up_verifying)
                )
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (method == null) {
                RaftBanner(
                    description = stringResource(Res.string.settings_step_up_passkey_only),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm
                )
            } else {
                // A passkey is its Use passkey button; a code its field (web VerifyItsYouDialog).
                if (method != SecondFactorStepUpMethod.Passkey) SecondFactorStepUpAnswerField(state, method, onIntent)
                state.otherMethods.forEach { other ->
                    RaftTextLink(
                        text =
                            stringResource(
                                when (other) {
                                    SecondFactorStepUpMethod.Passkey -> Res.string.settings_step_up_use_passkey
                                    SecondFactorStepUpMethod.BackupCode -> Res.string.settings_step_up_use_backup_code
                                    SecondFactorStepUpMethod.Totp -> Res.string.settings_step_up_use_authenticator
                                }
                            ),
                        onClick = { onIntent(SecondFactorStepUpIntent.SwitchMethod(other)) },
                        modifier = Modifier.testTag(secondFactorStepUpMethodLinkTag(other)),
                        variant = RaftTextLinkVariant.Strong,
                        style = RaftTheme.typography.callout,
                        enabled = !state.busy
                    )
                }
            }
            if (recovery != null) {
                RaftTextLink(
                    text = stringResource(Res.string.settings_step_up_lost_authenticator),
                    onClick = { onIntent(SecondFactorStepUpIntent.ShowLost) },
                    modifier = Modifier.testTag(SECOND_FACTOR_STEP_UP_LOST_LINK_TAG),
                    variant = RaftTextLinkVariant.Strong,
                    style = RaftTheme.typography.callout,
                    enabled = !state.submitting
                )
            }
            state.banner?.let { banner -> SecondFactorStepUpBannerView(banner, formatClock, onIntent) }
        }
    }
}

internal const val SECOND_FACTOR_STEP_UP_LOST_LINK_TAG = "step-up-lost-authenticator"
internal const val SECOND_FACTOR_STEP_UP_SUBMIT_TAG = "step-up-submit"
internal const val SECOND_FACTOR_STEP_UP_BANNER_TAG = "step-up-banner"

/** The "Use …" link that switches to [method]. */
internal fun secondFactorStepUpMethodLinkTag(method: SecondFactorStepUpMethod): String = "step-up-use-${method.wireName}"
internal const val SECOND_FACTOR_STEP_UP_SIGN_OUT_INSTEAD_TAG = "step-up-sign-out-instead"

/**
 * Web VerifyItsYouDialog's other pages for a lost device: "Can't verify?" (a backup code first;
 * with a lost device, the password email, Sign it out now with your password, the passkey warning
 * and the two-step reset as a link; without, the two-step reset by email) and the emails' "Check
 * your email". Back returns to the answer; Done and the close button end it like Cancel.
 */
@Composable
private fun SecondFactorStepUpRecoveryPage(
    state: SecondFactorStepUpDialogState,
    recovery: SecondFactorStepUpRecovery,
    onIntent: (SecondFactorStepUpIntent) -> Unit,
) {
    val dismiss = { if (!state.submitting) onIntent(SecondFactorStepUpIntent.Cancel) }
    when (state.view) {
        SecondFactorStepUpView.RecoverySent,
        SecondFactorStepUpView.PasswordEmailSent -> {
            val twoStep = state.view == SecondFactorStepUpView.RecoverySent
            RaftDialog(
                onDismissRequest = dismiss,
                title = stringResource(Res.string.settings_step_up_check_email_title),
                modifier = Modifier.testTag(SECOND_FACTOR_STEP_UP_DIALOG_TAG),
                size = RaftDialogSize.Sm,
                description =
                    if (twoStep) {
                        stringResource(Res.string.settings_step_up_recovery_sent, recovery.email)
                    } else {
                        stringResource(Res.string.settings_step_up_password_email_sent, recovery.email)
                    },
                dismissOnClickOutside = false,
                closeLabel = stringResource(Res.string.common_close),
                footer = {
                    RaftButton(
                        text = stringResource(Res.string.common_done),
                        onClick = dismiss,
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Lg
                    )
                }
            ) {
                if (twoStep) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RaftBanner(
                            description = stringResource(Res.string.settings_step_up_recovery_sent_notice),
                            status = RaftBannerStatus.Info,
                            size = RaftBannerSize.Sm
                        )
                        RaftText(
                            stringResource(Res.string.settings_step_up_recovery_signs_out_all),
                            style = RaftTheme.typography.footnote,
                            color = RaftTheme.colors.foregroundMuted
                        )
                    }
                }
            }
        }
        else -> {
            val lostDevice = recovery.lostDevice
            RaftDialog(
                onDismissRequest = dismiss,
                title = stringResource(Res.string.settings_step_up_recovery_title),
                modifier = Modifier.testTag(SECOND_FACTOR_STEP_UP_DIALOG_TAG),
                size = RaftDialogSize.Sm,
                dismissOnClickOutside = false,
                closeLabel = stringResource(Res.string.common_close),
                footer = {
                    RaftButton(
                        text = stringResource(Res.string.common_back),
                        onClick = { onIntent(SecondFactorStepUpIntent.ShowAnswer) },
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                        enabled = !state.submitting
                    )
                    RaftButton(
                        text =
                            stringResource(
                                when {
                                    lostDevice == null -> Res.string.settings_step_up_recovery_reset_by_email
                                    lostDevice.password -> Res.string.settings_step_up_reset_password_by_email
                                    else -> Res.string.settings_step_up_set_password_by_email
                                }
                            ),
                        onClick = {
                            onIntent(
                                if (lostDevice == null) {
                                    SecondFactorStepUpIntent.RequestTwoStepReset
                                } else {
                                    SecondFactorStepUpIntent.RequestPasswordEmail
                                }
                            )
                        },
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Lg,
                        enabled = !state.submitting,
                        loading = state.submitting
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (SecondFactorStepUpMethod.BackupCode in state.methods) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RaftText(
                                stringResource(Res.string.settings_step_up_recovery_backup_codes_first),
                                style = RaftTheme.typography.subhead,
                                color = RaftTheme.colors.foregroundMuted
                            )
                            RaftTextLink(
                                text = stringResource(Res.string.settings_step_up_use_backup_code),
                                onClick = { onIntent(SecondFactorStepUpIntent.SwitchMethod(SecondFactorStepUpMethod.BackupCode)) },
                                variant = RaftTextLinkVariant.Strong,
                                style = RaftTheme.typography.callout,
                                enabled = !state.submitting
                            )
                        }
                    }
                    if (lostDevice != null) {
                        SecondFactorStepUpLostDeviceBody(state, lostDevice, onIntent)
                    } else {
                        RaftText(
                            stringResource(Res.string.settings_step_up_recovery_email_description),
                            style = RaftTheme.typography.subhead,
                            color = RaftTheme.colors.foregroundMuted
                        )
                    }
                    state.recoveryFailure?.let { failure ->
                        RaftBanner(
                            description =
                                stringResource(
                                    if (failure == SecondFactorRecoveryEmailResult.TooManyRequests) {
                                        Res.string.settings_step_up_recovery_too_many_requests
                                    } else {
                                        Res.string.settings_step_up_recovery_request_failed
                                    }
                                ),
                            status = RaftBannerStatus.Warning,
                            size = RaftBannerSize.Sm
                        )
                    }
                }
            }
        }
    }
}

/** The lost page with a lost device: the password email's consequence, Sign out instead, the passkey warning, the two-step reset link. */
@Composable
private fun SecondFactorStepUpLostDeviceBody(
    state: SecondFactorStepUpDialogState,
    lostDevice: SecondFactorStepUpLostDevice,
    onIntent: (SecondFactorStepUpIntent) -> Unit,
) {
    RaftText(
        stringResource(
            if (lostDevice.password) Res.string.settings_step_up_reset_password_body else Res.string.settings_step_up_set_password_body
        ),
        style = RaftTheme.typography.subhead,
        color = RaftTheme.colors.foreground
    )
    lostDevice.signOut?.let { scope ->
        val many = scope == SecondFactorStepUpSignOutScope.Others
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftText(
                stringResource(
                    if (many) {
                        Res.string.settings_step_up_sign_out_all_with_password_body
                    } else {
                        Res.string.settings_step_up_sign_out_with_password_body
                    }
                ),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foreground
            )
            RaftButton(
                text =
                    stringResource(
                        if (many) Res.string.settings_step_up_sign_out_all_with_password else Res.string.settings_step_up_sign_out_with_password
                    ),
                onClick = { onIntent(SecondFactorStepUpIntent.SignOutInstead) },
                modifier = Modifier.testTag(SECOND_FACTOR_STEP_UP_SIGN_OUT_INSTEAD_TAG),
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md,
                enabled = !state.submitting
            )
        }
    }
    if (state.passkeyListed) {
        RaftBanner(
            description = stringResource(Res.string.settings_step_up_lost_passkey_warning),
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm
        )
    }
    val linkText = stringResource(Res.string.settings_step_up_lost_two_step_reset_link)
    val sentence = stringResource(Res.string.settings_step_up_lost_two_step_reset, RAFT_LINK_SLOT)
    val enabled = !state.submitting
    RaftText(
        raftSentenceWithLink(sentence, linkText, enabled) { onIntent(SecondFactorStepUpIntent.RequestTwoStepReset) },
        style = RaftTheme.typography.footnote,
        color = RaftTheme.colors.foregroundMuted
    )
}

/** Where a sentence's link goes: the `%1$s` filled with this, then split around it. */
internal const val RAFT_LINK_SLOT = "\u0000"

/**
 * A web `<link>` sentence: [sentence] (its `%1$s` already filled with [RAFT_LINK_SLOT]) with
 * [linkText] as a strong text link where the slot was.
 */
internal fun raftSentenceWithLink(
    sentence: String,
    linkText: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = buildAnnotatedString {
    val at = sentence.indexOf(RAFT_LINK_SLOT)
    if (at < 0) {
        append(sentence)
        return@buildAnnotatedString
    }
    append(sentence.substring(0, at))
    appendRaftTextLink(linkText, onClick = onClick, enabled = enabled)
    append(sentence.substring(at + RAFT_LINK_SLOT.length))
}

@Composable
private fun SecondFactorStepUpAnswerField(
    state: SecondFactorStepUpDialogState,
    method: SecondFactorStepUpMethod,
    onIntent: (SecondFactorStepUpIntent) -> Unit,
) {
    val backup = method == SecondFactorStepUpMethod.BackupCode
    val error =
        when (state.fieldError) {
            SecondFactorStepUpFieldError.CodeInvalid -> stringResource(Res.string.settings_step_up_code_invalid)
            SecondFactorStepUpFieldError.BackupCodeInvalid -> stringResource(Res.string.settings_step_up_backup_code_invalid)
            SecondFactorStepUpFieldError.BackupCodeUsed -> stringResource(Res.string.settings_step_up_backup_code_used)
            null -> null
        }
    RaftField(
        label =
            stringResource(
                if (backup) Res.string.settings_step_up_backup_code_label else Res.string.settings_step_up_code_label
            ),
        modifier = Modifier.fillMaxWidth(),
        description = if (backup) stringResource(Res.string.settings_step_up_backup_code_hint) else null,
        error = error,
        enabled = !state.submitting
    ) {
        RaftTextField(
            value = state.input,
            onValueChange = { onIntent(SecondFactorStepUpIntent.InputChanged(it)) },
            enabled = !state.submitting,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = if (backup) KeyboardType.Ascii else KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
            keyboardActions = KeyboardActions(onDone = { onIntent(SecondFactorStepUpIntent.Submit) })
        )
    }
}

@Composable
private fun SecondFactorStepUpBannerView(
    banner: SecondFactorStepUpBanner,
    formatClock: (Long) -> String,
    onIntent: (SecondFactorStepUpIntent) -> Unit,
) {
    val tagged = Modifier.testTag(SECOND_FACTOR_STEP_UP_BANNER_TAG)
    when (banner) {
        is SecondFactorStepUpBanner.Locked ->
            SecondFactorStepUpTracedBanner(
                description =
                    banner.lockedUntilEpochMillis?.let { stringResource(Res.string.settings_step_up_locked_until, formatClock(it)) }
                        ?: stringResource(Res.string.settings_step_up_locked),
                traceId = banner.traceId
            )
        SecondFactorStepUpBanner.SignInAgain ->
            RaftBanner(
                description = stringResource(Res.string.settings_step_up_sign_in_again_message),
                modifier = tagged,
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                action = {
                    RaftButton(
                        text = stringResource(Res.string.settings_step_up_sign_in_again),
                        onClick = { onIntent(SecondFactorStepUpIntent.SignInAgain) },
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Md
                    )
                }
            )
        is SecondFactorStepUpBanner.Failed ->
            SecondFactorStepUpTracedBanner(
                // Localized copy first; the server's sentence only says more when there is one.
                description = banner.serverMessage ?: stringResource(Res.string.settings_step_up_failed),
                traceId = banner.traceId
            )
        is SecondFactorStepUpBanner.PasskeyInvalid ->
            SecondFactorStepUpTracedBanner(stringResource(Res.string.settings_step_up_passkey_invalid), banner.traceId)
        is SecondFactorStepUpBanner.PasskeysUnavailable ->
            SecondFactorStepUpTracedBanner(stringResource(Res.string.settings_step_up_passkeys_unavailable), banner.traceId)
        SecondFactorStepUpBanner.PasskeyCancelled ->
            RaftBanner(
                description = stringResource(Res.string.settings_step_up_passkey_cancelled),
                modifier = tagged,
                status = RaftBannerStatus.Info,
                size = RaftBannerSize.Sm
            )
        SecondFactorStepUpBanner.PasskeyNoCredential ->
            RaftBanner(
                description = stringResource(Res.string.settings_step_up_passkey_none_here),
                modifier = tagged,
                status = RaftBannerStatus.Info,
                size = RaftBannerSize.Sm
            )
        SecondFactorStepUpBanner.PasskeyUnsupported ->
            RaftBanner(
                description = stringResource(Res.string.settings_step_up_passkey_unsupported),
                modifier = tagged,
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
    }
}

/** A refusal from the server: the warning banner, its trace ID under it (AGENTS.md "Errors carry the trace ID"). */
@Composable
private fun SecondFactorStepUpTracedBanner(description: String, traceId: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(
            description = description,
            modifier = Modifier.testTag(SECOND_FACTOR_STEP_UP_BANNER_TAG),
            status = RaftBannerStatus.Warning,
            size = RaftBannerSize.Sm
        )
        RaftTraceIdNote(traceId)
    }
}

/**
 * A settings store's open step-up (`SharedSettingsState.secondFactorStepUp`, any action the store
 * runs through its [build.raft.app.data.SecondFactorStepUpCoordinator]) on a page that is not
 * Account: the dialog in the design-system theme with the generic reason (web
 * SecondFactorStepUpHost `other`), its intents back to the same store. Compose it after the sheet
 * or dialog whose action asked, so it opens over it.
 */
@Composable
internal fun SettingsSecondFactorStepUpHost(
    state: SecondFactorStepUpDialogState?,
    dispatch: (SharedSettingsIntent) -> Unit,
) {
    val dialog = state ?: return
    RaftDesignSystemTheme {
        SecondFactorStepUpDialog(
            state = dialog,
            onIntent = { intent -> dispatch(SharedSettingsIntent.SecondFactorStepUp(intent)) },
            description = stringResource(Res.string.settings_step_up_generic_description),
        )
    }
}

/** `HH:mm` in this device's time zone (web `formatClock` of `lockedUntil`). */
internal fun secondFactorStepUpLocalClock(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}
