package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import build.raft.designsystem.theme.RaftTheme
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal const val SECOND_FACTOR_STEP_UP_DIALOG_TAG = "second-factor-step-up-dialog"

/**
 * "Verify it's you" (web VerifyItsYouDialog, slock packages/web/src/components/settings/): the
 * step-up of a refused sensitive action. An authenticator code or a backup code (the other one
 * as a link under the field); a wrong or used code under the field, a lock, Sign In Again or
 * any other refusal in a warning banner. Only a passkey listed: a banner saying to do this on
 * the web and Close. A scrim tap does not close it; Cancel, the close button and Back do (not
 * while the answer is being checked).
 */
@Composable
internal fun SecondFactorStepUpDialog(
    state: SecondFactorStepUpDialogState,
    onIntent: (SecondFactorStepUpIntent) -> Unit,
    /** What the step-up is for (web SecondFactorStepUpHost `reason`); changing how you sign in by default. */
    description: String = stringResource(Res.string.settings_step_up_description),
    formatClock: (Long) -> String = ::secondFactorStepUpLocalClock,
) {
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
                    text = stringResource(Res.string.settings_step_up_verify),
                    onClick = { onIntent(SecondFactorStepUpIntent.Submit) },
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    enabled = state.canSubmit || state.submitting,
                    loading = state.submitting,
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
                SecondFactorStepUpAnswerField(state, method, onIntent)
                state.otherMethods.forEach { other ->
                    RaftTextLink(
                        text =
                            stringResource(
                                if (other == SecondFactorStepUpMethod.BackupCode) {
                                    Res.string.settings_step_up_use_backup_code
                                } else {
                                    Res.string.settings_step_up_use_authenticator
                                }
                            ),
                        onClick = { onIntent(SecondFactorStepUpIntent.SwitchMethod(other)) },
                        variant = RaftTextLinkVariant.Strong,
                        style = RaftTheme.typography.callout,
                        enabled = !state.submitting
                    )
                }
            }
            state.banner?.let { banner -> SecondFactorStepUpBannerView(banner, formatClock, onIntent) }
        }
    }
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
    when (banner) {
        is SecondFactorStepUpBanner.Locked ->
            RaftBanner(
                description =
                    banner.lockedUntilEpochMillis?.let { stringResource(Res.string.settings_step_up_locked_until, formatClock(it)) }
                        ?: stringResource(Res.string.settings_step_up_locked),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
        SecondFactorStepUpBanner.SignInAgain ->
            RaftBanner(
                description = stringResource(Res.string.settings_step_up_sign_in_again_message),
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
            RaftBanner(
                // Localized copy first; the server's sentence only says more when there is one.
                description = banner.serverMessage ?: stringResource(Res.string.settings_step_up_failed),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
    }
}

/** `HH:mm` in this device's time zone (web `formatClock` of `lockedUntil`). */
internal fun secondFactorStepUpLocalClock(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}
