package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import build.raft.app.data.SecondFactorSignInBanner
import build.raft.app.data.SecondFactorSignInEnd
import build.raft.app.data.SecondFactorSignInIntent
import build.raft.app.data.SecondFactorSignInOutcome
import build.raft.app.data.SecondFactorSignInPending
import build.raft.app.data.SecondFactorSignInState
import build.raft.app.data.SecondFactorSignInStore
import build.raft.app.data.SecondFactorStepUpFieldError
import build.raft.app.data.SecondFactorStepUpMethod
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.defaultNowMillis
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val SECOND_FACTOR_SIGN_IN_TAG = "second-factor-sign-in"

/**
 * The second step of a sign-in (web SecondFactorSignInStep, slock v1.21.44 #12259) for
 * [pending]: owns its [SecondFactorSignInStore], ends the challenge at its `expiresAt` (no
 * countdown), and hands Back (system Back too) to the store. [onSignedIn] gets the session the
 * verify answer saved; [onBack] drops the challenge.
 */
@Composable
internal fun SecondFactorSignInHost(
    pending: SecondFactorSignInPending,
    auth: SharedAuthDataSource,
    onSignedIn: (StoredAuthSession) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = remember(pending, auth) { SecondFactorSignInStore(pending, verify = auth::verifySecondFactorSignIn) }
    val state by store.state.collectAsState()
    val outcome by store.outcome.collectAsState()
    val currentOnSignedIn by rememberUpdatedState(onSignedIn)
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(store) {
        delay((pending.challenge.expiresAtEpochMillis - defaultNowMillis()).coerceAtLeast(0L))
        store.accept(SecondFactorSignInIntent.Expire)
    }
    LaunchedEffect(outcome) {
        when (val done = outcome) {
            is SecondFactorSignInOutcome.SignedIn -> currentOnSignedIn(done.session)
            SecondFactorSignInOutcome.Cancelled -> currentOnBack()
            null -> Unit
        }
    }
    val onIntent: (SecondFactorSignInIntent) -> Unit = { intent -> scope.launch { store.accept(intent) } }
    RaftBackHandler(enabled = !state.submitting, label = "second_factor_sign_in", blocksBackSwipe = false) {
        onIntent(SecondFactorSignInIntent.Back)
    }
    SecondFactorSignInContent(state = state, onIntent = onIntent)
}

/**
 * The page: title, who is signing in and until when; then the field for the current method
 * (authenticator code or backup code; the other one under "or"), Verify, and Back to Sign In.
 * A wrong or used code sits under the field; a lock, an expired challenge or five wrong answers
 * leave only Back to Sign In. Only a passkey listed: a banner says to sign in through the
 * browser.
 */
@Composable
internal fun SecondFactorSignInContent(
    state: SecondFactorSignInState,
    onIntent: (SecondFactorSignInIntent) -> Unit,
    formatClock: (Long) -> String = ::secondFactorStepUpLocalClock,
) {
    AuthScaffold(
        modifier = Modifier.testTag(SECOND_FACTOR_SIGN_IN_TAG),
        contentScrollable = true,
        scrollContentVerticalArrangement = Arrangement.Center,
    ) {
        AuthCenterMark(size = 52.dp)
        AuthFormColumn {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftText(
                    text = stringResource(Res.string.auth_second_factor_title),
                    style = RaftTheme.typography.title,
                    color = RaftTheme.colors.foreground,
                )
                RaftText(
                    text =
                        if (state.ended == null) {
                            stringResource(
                                Res.string.auth_second_factor_signing_in_as_until,
                                state.maskedEmail,
                                formatClock(state.expiresAtEpochMillis),
                            )
                        } else {
                            stringResource(Res.string.auth_second_factor_signing_in_as, state.maskedEmail)
                        },
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted,
                )
            }
            val method = state.method
            val ended = state.ended
            if (ended != null || method == null) {
                RaftBanner(
                    description =
                        when (ended) {
                            SecondFactorSignInEnd.Expired -> stringResource(Res.string.auth_second_factor_expired)
                            SecondFactorSignInEnd.AttemptsExhausted -> stringResource(Res.string.auth_second_factor_attempts_exhausted)
                            is SecondFactorSignInEnd.Locked ->
                                ended.lockedUntilEpochMillis?.let {
                                    stringResource(Res.string.auth_second_factor_locked_until, formatClock(it))
                                } ?: stringResource(Res.string.auth_second_factor_locked)
                            null -> stringResource(Res.string.auth_second_factor_passkey_only)
                        },
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                )
                RaftButton(
                    text = stringResource(Res.string.auth_second_factor_back),
                    onClick = { onIntent(SecondFactorSignInIntent.Back) },
                    modifier = Modifier.fillMaxWidth(),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                )
                return@AuthFormColumn
            }
            state.banner?.let { banner ->
                RaftBanner(
                    description =
                        when (banner) {
                            SecondFactorSignInBanner.Unavailable -> stringResource(Res.string.auth_second_factor_unavailable)
                            // Localized copy first; the server's sentence only when it sent one.
                            is SecondFactorSignInBanner.Failed -> banner.serverMessage ?: stringResource(Res.string.auth_web_login_failed)
                        },
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                )
            }
            SecondFactorSignInCodeField(state, method, onIntent)
            RaftButton(
                text = stringResource(Res.string.settings_step_up_verify),
                onClick = { onIntent(SecondFactorSignInIntent.Submit) },
                modifier = Modifier.fillMaxWidth(),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
                enabled = state.canSubmit || state.submitting,
                loading = state.submitting,
                loadingLabel = stringResource(Res.string.settings_step_up_verifying),
            )
            if (state.otherMethods.isNotEmpty()) {
                RaftSeparator(label = stringResource(Res.string.auth_second_factor_or))
                state.otherMethods.forEach { other ->
                    RaftButton(
                        text =
                            stringResource(
                                if (other == SecondFactorStepUpMethod.BackupCode) {
                                    Res.string.settings_step_up_use_backup_code
                                } else {
                                    Res.string.settings_step_up_use_authenticator
                                }
                            ),
                        onClick = { onIntent(SecondFactorSignInIntent.SwitchMethod(other)) },
                        modifier = Modifier.fillMaxWidth(),
                        variant = RaftButtonVariant.Default,
                        size = RaftButtonSize.Lg,
                        enabled = !state.submitting,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RaftTextLink(
                    text = stringResource(Res.string.auth_second_factor_back),
                    onClick = { onIntent(SecondFactorSignInIntent.Back) },
                    variant = RaftTextLinkVariant.Strong,
                    style = RaftTheme.typography.callout,
                    enabled = !state.submitting,
                )
            }
        }
    }
}

@Composable
private fun SecondFactorSignInCodeField(
    state: SecondFactorSignInState,
    method: SecondFactorStepUpMethod,
    onIntent: (SecondFactorSignInIntent) -> Unit,
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
        description =
            stringResource(if (backup) Res.string.settings_step_up_backup_code_hint else Res.string.auth_second_factor_totp_hint),
        error = error,
        enabled = !state.submitting,
    ) {
        RaftTextField(
            value = state.input,
            onValueChange = { onIntent(SecondFactorSignInIntent.InputChanged(it)) },
            enabled = !state.submitting,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = if (backup) KeyboardType.Ascii else KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { onIntent(SecondFactorSignInIntent.Submit) }),
        )
    }
}
