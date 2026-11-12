package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.RUNTIME_INSTALL_COMMANDS
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthLoginRegion
import build.raft.app.data.RuntimeAuthProviderKeyEntry
import build.raft.app.data.RuntimeAuthProviderOAuthFlow
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthSignedInProvider
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthUnavailableReason
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.oauthMethods
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftChoiceListGroup
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * The runtime card's sign-in part and its dialogs (web RuntimeUsageSurface.tsx `RuntimeAuthHeaderLine`,
 * `RuntimeAuthUnavailable`, `runtimeAuthAction`, `PerProviderRows`; RuntimeSignInDialog.tsx,
 * RuntimeSignOutDialog.tsx, PiSignInDialog.tsx at botiverse/slock staging 853c6998b).
 */

/** What a card knows about this runtime's sign-in (web `SurfaceAuth`). */
internal data class ComputerRuntimeAuthCardState(
    val raftRuntimeId: String,
    val runtime: RuntimeAuthRuntime,
    val view: RuntimeAuthView,
) {
    val status: RuntimeAuthStatus?
        get() = view.statusOf(runtime)

    val signedOut: Boolean
        get() = status == RuntimeAuthStatus.LoggedOut && !runtime.perProvider && !runtime.hasProviderKey
}

/** The card's sign-in action for a person who may act (web `runtimeAuthAction`), or why there is none. */
internal sealed interface ComputerRuntimeAuthAction {
    /** "Upgrade this Computer to sign in from here" / "This Computer has no owner, …". */
    data class Caption(val notAttached: Boolean) : ComputerRuntimeAuthAction

    /** The CLI is missing, too old or an installation OAR can't use: why, and how to install it. */
    data class Unavailable(val reason: RuntimeAuthUnavailableReason) : ComputerRuntimeAuthAction

    /** Pi: one more provider at a time; pink while none is stored. */
    data class AddProvider(val primary: Boolean) : ComputerRuntimeAuthAction

    data object SignOut : ComputerRuntimeAuthAction

    data object SignIn : ComputerRuntimeAuthAction
}

internal fun ComputerRuntimeAuthCardState.action(): ComputerRuntimeAuthAction? {
    if (!view.canAct) return null
    // A daemon that predates this runtime's sign-in from the web would drop a start.
    val needsNewerComputer = view.availability == RuntimeAuthAvailability.Ok && !view.computerSupports(runtime)
    if (view.availability == RuntimeAuthAvailability.ComputerUpgradeRequired ||
        view.availability == RuntimeAuthAvailability.ComputerNotAttached ||
        needsNewerComputer
    ) {
        return ComputerRuntimeAuthAction.Caption(notAttached = view.availability == RuntimeAuthAvailability.ComputerNotAttached)
    }
    if (view.availability != RuntimeAuthAvailability.Ok) return null
    val current = status
    if (current is RuntimeAuthStatus.Unavailable) return ComputerRuntimeAuthAction.Unavailable(current.reason)
    if (runtime.perProvider) return ComputerRuntimeAuthAction.AddProvider(primary = current !is RuntimeAuthStatus.LoggedIn)
    if (current is RuntimeAuthStatus.LoggedIn) {
        // Qoder has no sign-out; a Computer from before Antigravity's signs it in but not out.
        return if (view.computerSignsOut(runtime)) ComputerRuntimeAuthAction.SignOut else null
    }
    return ComputerRuntimeAuthAction.SignIn
}

/**
 * The sign-in state under the card's title (web `RuntimeAuthHeaderLine`): "Not signed in", the
 * account (masked email · plan) when no usage block names it, or "Signed in" on a card with no usage.
 */
@Composable
internal fun ComputerRuntimeAuthHeaderLine(auth: ComputerRuntimeAuthCardState, usageShowsAccount: Boolean, authOnly: Boolean) {
    val status = auth.status
    if (auth.runtime.perProvider) {
        // Its providers are listed below; none stored is no "signed out".
        if (status == RuntimeAuthStatus.LoggedOut) {
            RaftText(
                stringResource(Res.string.settings_computers_runtime_auth_pi_no_providers),
                modifier = Modifier.padding(top = 4.dp).testTag(COMPUTER_RUNTIME_AUTH_NO_PROVIDERS_TAG),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundMuted,
            )
        }
        return
    }
    when {
        status == RuntimeAuthStatus.LoggedOut ->
            ComputerRuntimeAuthStatusChip(signedIn = false, Modifier.padding(top = 4.dp).testTag(COMPUTER_RUNTIME_AUTH_LOGGED_OUT_TAG))
        status is RuntimeAuthStatus.LoggedIn && !usageShowsAccount && status.account?.text != null ->
            RaftText(
                status.account.text.orEmpty(),
                modifier = Modifier.padding(top = 4.dp),
                style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold),
                color = RaftTheme.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        authOnly && status is RuntimeAuthStatus.LoggedIn -> ComputerRuntimeAuthStatusChip(signedIn = true, Modifier.padding(top = 4.dp))
    }
}

/**
 * The account's sign-in state, Signed in or Signed out, as a soft badge with no dot: a light is
 * presence, and the words carry the state (raft-design patterns/status.md, the usage card).
 */
@Composable
private fun ComputerRuntimeAuthStatusChip(signedIn: Boolean, modifier: Modifier = Modifier) {
    RaftBadge(
        text = stringResource(if (signedIn) Res.string.settings_computers_runtime_auth_signed_in else Res.string.settings_computers_runtime_auth_not_signed_in),
        modifier = modifier,
        appearance = RaftBadgeAppearance.Soft,
        variant = if (signedIn) RaftBadgeVariant.Success else RaftBadgeVariant.Warning,
    )
}

/**
 * The card's sign-in action (web `runtimeAuthAction`): a button, or the caption that says why there
 * is none here. Nothing for a person who may not act. [fullWidth]: the phone sheet's stacked buttons.
 */
@Composable
internal fun ComputerRuntimeAuthActionView(
    auth: ComputerRuntimeAuthCardState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = false,
) {
    val action = auth.action() ?: return
    val size = if (fullWidth) RaftButtonSize.Lg else RaftButtonSize.Md
    val buttonModifier = if (fullWidth) modifier.fillMaxWidth() else modifier
    when (action) {
        is ComputerRuntimeAuthAction.Caption ->
            RaftText(
                stringResource(
                    if (action.notAttached) Res.string.settings_computers_runtime_auth_not_attached else Res.string.settings_computers_runtime_auth_upgrade_required
                ),
                modifier = modifier.testTag(COMPUTER_RUNTIME_AUTH_UNAVAILABLE_TAG),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundHint,
            )
        is ComputerRuntimeAuthAction.Unavailable -> ComputerRuntimeAuthUnavailable(auth.raftRuntimeId, action.reason, modifier)
        is ComputerRuntimeAuthAction.AddProvider ->
            RaftButton(
                text = stringResource(Res.string.settings_computers_runtime_auth_pi_add_provider),
                onClick = onSignIn,
                modifier = buttonModifier.testTag(COMPUTER_RUNTIME_AUTH_ADD_PROVIDER_TAG),
                variant = if (action.primary) RaftButtonVariant.Primary else RaftButtonVariant.Default,
                size = size,
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.Plus) },
            )
        // Raft DS Button: a sign-out can be undone (sign in again), so the card's is a quiet ghost
        // action; its confirm carries the warning tone.
        ComputerRuntimeAuthAction.SignOut ->
            RaftButton(
                text = stringResource(Res.string.settings_computers_runtime_auth_sign_out_action),
                onClick = onSignOut,
                modifier = buttonModifier.testTag(COMPUTER_RUNTIME_AUTH_SIGN_OUT_TAG),
                variant = RaftButtonVariant.Ghost,
                size = size,
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.LogOut) },
            )
        ComputerRuntimeAuthAction.SignIn ->
            RaftButton(
                text = stringResource(Res.string.settings_computers_runtime_auth_sign_in_action),
                onClick = onSignIn,
                modifier = buttonModifier.testTag(COMPUTER_RUNTIME_AUTH_SIGN_IN_TAG),
                variant = RaftButtonVariant.Primary,
                size = size,
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.LogIn) },
            )
    }
}

/**
 * Web `RuntimeAuthUnavailable` (#13581): sign-in is unavailable on the Computer; say why, and how to
 * install the CLI there (with a copy button) when a command is known and it can help.
 */
@Composable
internal fun ComputerRuntimeAuthUnavailable(raftRuntimeId: String, reason: RuntimeAuthUnavailableReason, modifier: Modifier = Modifier) {
    val install = if (reason == RuntimeAuthUnavailableReason.UnsupportedInstallation) null else RUNTIME_INSTALL_COMMANDS[raftRuntimeId]
    val hint = RaftTheme.colors.foregroundHint
    Column(modifier.fillMaxWidth().testTag(COMPUTER_RUNTIME_AUTH_CLI_UNAVAILABLE_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftText(
            stringResource(
                when (reason) {
                    RuntimeAuthUnavailableReason.NotInstalled -> Res.string.settings_computers_runtime_auth_unavailable_not_installed
                    RuntimeAuthUnavailableReason.VersionUnsupported ->
                        if (raftRuntimeId == "opencode") {
                            Res.string.settings_computers_runtime_auth_opencode_version_unsupported
                        } else {
                            Res.string.settings_computers_runtime_auth_unavailable_version_unsupported
                        }
                    RuntimeAuthUnavailableReason.UnsupportedInstallation -> Res.string.settings_computers_runtime_auth_unavailable_unsupported_installation
                }
            ),
            style = RaftTheme.typography.caption,
            color = hint,
        )
        if (install != null) {
            RaftText(stringResource(Res.string.settings_computers_runtime_auth_unavailable_install_on), style = RaftTheme.typography.caption, color = hint)
            ComputerRuntimeAuthCopyableCode(install, stringResource(Res.string.settings_computers_copy_command), Modifier.testTag(COMPUTER_RUNTIME_AUTH_INSTALL_LINE_TAG))
        }
    }
}

/** A command or code people copy (raft-ui CopyableCode): the code block with its copy button. */
@Composable
private fun ComputerRuntimeAuthCopyableCode(text: String, copyLabel: String, modifier: Modifier = Modifier) {
    val copy = rememberRaftCopyAction()
    var copied by remember(text) { mutableStateOf(false) }
    RaftCodeBlock(
        text = text,
        modifier = modifier,
        action = {
            RaftIconButton(
                onClick = {
                    copy(text)
                    copied = true
                },
                tooltip = if (copied) stringResource(Res.string.common_copied) else copyLabel,
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Md,
            ) {
                RaftComputersIcon(if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy)
            }
        },
    )
}

/**
 * Pi's stored sign-ins, one row each (web `PerProviderRows`): the provider, how it is signed in, and
 * its own Sign Out (an account) or Remove (a key) for a person who may act here.
 */
@Composable
internal fun ComputerRuntimeAuthProviderRows(auth: ComputerRuntimeAuthCardState, onSignOutProvider: (RuntimeAuthSignedInProvider) -> Unit) {
    val status = auth.status as? RuntimeAuthStatus.LoggedIn ?: return
    if (!auth.runtime.perProvider || status.providers.isEmpty()) return
    val canAct = auth.view.canActNow && auth.view.computerSignsOut(auth.runtime)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp).testTag(COMPUTER_RUNTIME_AUTH_PROVIDERS_TAG)) {
        status.providers.forEach { provider ->
            RaftListRow(
                title = provider.label,
                variant = RaftListRowVariant.Plain,
                description =
                    stringResource(
                        when {
                            provider.apiKey -> Res.string.settings_computers_runtime_auth_pi_method_api_key
                            // OpenCode does not say whether an account is a subscription: neither is claimed.
                            provider.subscription == null -> Res.string.settings_computers_runtime_auth_signed_in
                            provider.subscription -> Res.string.settings_computers_runtime_auth_pi_method_subscription
                            else -> Res.string.settings_computers_runtime_auth_pi_method_account
                        }
                    ),
                leading = {
                    RaftIconTile(size = RaftIconTileSize.Sm) {
                        RaftComputersIcon(if (provider.apiKey) RaftLucideIconName.KeyRound else RaftLucideIconName.User, size = 14.dp)
                    }
                },
                trailing =
                    if (canAct) {
                        {
                            RaftButton(
                                text =
                                    stringResource(
                                        if (provider.apiKey) Res.string.settings_computers_runtime_auth_pi_remove_key_confirm else Res.string.settings_computers_runtime_auth_sign_out_action
                                    ),
                                onClick = { onSignOutProvider(provider) },
                                variant = RaftButtonVariant.Ghost,
                                size = RaftButtonSize.Md,
                                leadingIcon = if (provider.apiKey) null else ({ RaftComputersIcon(RaftLucideIconName.LogOut) }),
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

/** The text of a stopped step, in the web's words. */
@Composable
internal fun ComputerRuntimeAuthProblem.text(runtimeName: String): String =
    when (this) {
        ComputerRuntimeAuthProblem.ComputerOffline -> stringResource(Res.string.settings_computers_runtime_auth_error_computer_offline)
        ComputerRuntimeAuthProblem.UpgradeRequired -> stringResource(Res.string.settings_computers_runtime_auth_upgrade_required)
        ComputerRuntimeAuthProblem.NotAttached -> stringResource(Res.string.settings_computers_runtime_auth_not_attached)
        ComputerRuntimeAuthProblem.Busy -> stringResource(Res.string.settings_computers_runtime_auth_error_busy)
        ComputerRuntimeAuthProblem.PiBusy -> stringResource(Res.string.settings_computers_runtime_auth_pi_error_busy)
        ComputerRuntimeAuthProblem.OpencodeBusy -> stringResource(Res.string.settings_computers_runtime_auth_opencode_error_busy)
        ComputerRuntimeAuthProblem.LoopbackRequired -> stringResource(Res.string.settings_computers_runtime_auth_error_loopback_required, runtimeName)
        ComputerRuntimeAuthProblem.OpencodeVersionUnsupported -> stringResource(Res.string.settings_computers_runtime_auth_opencode_version_unsupported)
        ComputerRuntimeAuthProblem.SignInInProgress -> stringResource(Res.string.settings_computers_runtime_auth_opencode_error_sign_in_in_progress)
        ComputerRuntimeAuthProblem.RateLimited -> stringResource(Res.string.settings_computers_runtime_auth_error_rate_limited)
        ComputerRuntimeAuthProblem.Forbidden -> stringResource(Res.string.settings_computers_runtime_auth_error_forbidden)
        ComputerRuntimeAuthProblem.NotFound -> stringResource(Res.string.settings_computers_runtime_auth_error_not_found)
        ComputerRuntimeAuthProblem.StartFailed -> stringResource(Res.string.settings_computers_runtime_auth_error_start_failed)
        ComputerRuntimeAuthProblem.LoginGone -> stringResource(Res.string.settings_computers_runtime_auth_error_login_gone)
        ComputerRuntimeAuthProblem.Cancelled -> stringResource(Res.string.settings_computers_runtime_auth_error_cancelled)
        ComputerRuntimeAuthProblem.VersionUnsupported -> stringResource(Res.string.settings_computers_runtime_auth_error_version_unsupported, runtimeName)
        ComputerRuntimeAuthProblem.NotInstalled -> stringResource(Res.string.settings_computers_runtime_auth_error_not_installed, runtimeName)
        ComputerRuntimeAuthProblem.UnsupportedInstallation ->
            stringResource(Res.string.settings_computers_runtime_auth_error_unsupported_installation, runtimeName)
        ComputerRuntimeAuthProblem.TimedOut -> stringResource(Res.string.settings_computers_runtime_auth_error_timed_out)
        ComputerRuntimeAuthProblem.Rejected -> stringResource(Res.string.settings_computers_runtime_auth_error_rejected, runtimeName)
        ComputerRuntimeAuthProblem.NotLoggedIn -> stringResource(Res.string.settings_computers_runtime_auth_error_not_logged_in, runtimeName)
        ComputerRuntimeAuthProblem.InteractionFailed -> stringResource(Res.string.settings_computers_runtime_auth_error_interaction_failed, runtimeName)
        ComputerRuntimeAuthProblem.ProcessFailed -> stringResource(Res.string.settings_computers_runtime_auth_error_process_failed, runtimeName)
        ComputerRuntimeAuthProblem.SignOutStillLoggedIn -> stringResource(Res.string.settings_computers_runtime_auth_sign_out_still_logged_in)
        ComputerRuntimeAuthProblem.SignOutStillLoggedInAntigravity ->
            stringResource(Res.string.settings_computers_runtime_auth_sign_out_still_logged_in_antigravity)
        ComputerRuntimeAuthProblem.SignOutStillLoggedInGrok -> stringResource(Res.string.settings_computers_runtime_auth_sign_out_still_logged_in_grok)
        ComputerRuntimeAuthProblem.SignOutTimedOut -> stringResource(Res.string.settings_computers_runtime_auth_sign_out_timed_out)
        ComputerRuntimeAuthProblem.SignOutFailed -> stringResource(Res.string.settings_computers_runtime_auth_sign_out_failed, runtimeName)
        ComputerRuntimeAuthProblem.ProviderUpgradeRequired -> stringResource(Res.string.settings_computers_runtime_auth_provider_upgrade_required)
        ComputerRuntimeAuthProblem.ProviderNotAttached -> stringResource(Res.string.settings_computers_runtime_auth_provider_not_attached)
        ComputerRuntimeAuthProblem.ProviderForbidden -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_forbidden)
        ComputerRuntimeAuthProblem.ProviderRateLimited -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_rate_limited)
        ComputerRuntimeAuthProblem.ProviderComputerTimeout -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_computer_timeout)
        ComputerRuntimeAuthProblem.ProviderRequestFailed -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_request_failed)
        ComputerRuntimeAuthProblem.ProviderInvalidInput -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_invalid_input)
        ComputerRuntimeAuthProblem.ProviderRejected -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_rejected, runtimeName)
        ComputerRuntimeAuthProblem.ProviderListTimedOut -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_list_timed_out, runtimeName)
        ComputerRuntimeAuthProblem.ProviderConnectTimedOut ->
            stringResource(Res.string.settings_computers_runtime_auth_provider_error_connect_timed_out, runtimeName)
        ComputerRuntimeAuthProblem.ProviderProcessFailed -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_process_failed, runtimeName)
        ComputerRuntimeAuthProblem.ProviderInvalidResponse ->
            stringResource(Res.string.settings_computers_runtime_auth_provider_error_invalid_response, runtimeName)
        ComputerRuntimeAuthProblem.ProviderUnsupported -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_unsupported, runtimeName)
        ComputerRuntimeAuthProblem.ProviderBusy -> stringResource(Res.string.settings_computers_runtime_auth_provider_error_busy)
    }

/** A stopped step's warning, with the failed request's trace id under it (AGENTS.md "Errors carry the trace ID"). */
@Composable
private fun ComputerRuntimeAuthFailureBanner(problem: ComputerRuntimeAuthProblem, traceId: String?, runtimeName: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag(COMPUTER_RUNTIME_AUTH_FAILURE_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(description = problem.text(runtimeName), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
        RaftTraceIdNote(traceId)
    }
}

/** The card's open dialog, if any. [runtimeName] names the badge's runtime ("Kimi Code"). */
@Composable
internal fun ComputerRuntimeAuthDialogHost(
    controller: ComputerRuntimeAuthController,
    runtimeName: String,
    computerName: String?,
    /** The Computer's agents; null when the page does not know their runtimes (the sign-out names none then). */
    agents: List<SettingsComputerAgentSnapshot>?,
    openUrl: (String) -> Unit,
) {
    val computer = computerName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.settings_computers_runtime_auth_this_computer)
    RaftComputersTheme {
        when (val dialog = controller.dialog) {
            is ComputerRuntimeAuthDialog.SignIn ->
                ComputerRuntimeSignInDialog(dialog.session, runtimeName, computer, openUrl, onClose = controller::closeDialog)
            is ComputerRuntimeAuthDialog.SignOut ->
                ComputerRuntimeSignOutDialog(
                    state = dialog.state,
                    runtime = controller.runtime,
                    runtimeName = runtimeName,
                    computerName = computer,
                    // Every agent on this Computer on a runtime that uses this sign-in (for Kimi: Kimi Code's, not Kimi CLI's).
                    agents = agents?.filter { RuntimeAuthRuntime.forRaftRuntime(it.runtime) == controller.runtime }?.map { it.name },
                    onConfirm = controller::confirmSignOut,
                    onDismiss = controller::closeDialog,
                )
            is ComputerRuntimeAuthDialog.AddProvider ->
                ComputerRuntimePiDialog(dialog.session, runtimeName, computer, onClose = controller::closeDialog)
            null -> Unit
        }
    }
}

/**
 * Web `RuntimeSignInDialog`: the region first for Kimi, then what the Computer sent — a sign-in page
 * to open (with the code field when the runtime waits for a pasted code) or a device code to enter on
 * the provider's page — and how it ended. Only one button is pink at a time.
 */
@Composable
internal fun ComputerRuntimeSignInDialog(
    session: ComputerRuntimeSignInSession,
    runtimeName: String,
    computerName: String,
    openUrl: (String) -> Unit,
    onClose: () -> Unit,
) {
    val state = session.state
    val name = session.provider?.label ?: runtimeName
    RaftDialog(
        onDismissRequest = onClose,
        title = stringResource(Res.string.settings_computers_runtime_auth_sign_in_title, name),
        modifier = Modifier.testTag(COMPUTER_RUNTIME_SIGN_IN_DIALOG_TAG),
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            when {
                state.choosingRegion -> {
                    RaftButton(stringResource(Res.string.common_cancel), onClick = onClose, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                    RaftButton(
                        stringResource(Res.string.settings_computers_runtime_auth_continue),
                        onClick = session::continueWithRegion,
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Lg,
                        enabled = state.region != null,
                    )
                }
                state.done ->
                    RaftButton(stringResource(Res.string.settings_computers_runtime_auth_done), onClick = onClose, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg)
                else -> {
                    RaftButton(
                        stringResource(if (state.ended) Res.string.common_close else Res.string.common_cancel),
                        onClick = onClose,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                    )
                    if (state.failure?.retryable == true) {
                        RaftButton(
                            stringResource(Res.string.settings_computers_runtime_auth_retry),
                            onClick = session::retry,
                            variant = RaftButtonVariant.Primary,
                            size = RaftButtonSize.Lg,
                        )
                    }
                }
            }
        },
    ) {
        ComputerRuntimeSignInBody(session, state, name, computerName, openUrl)
    }
}

/** The sign-in dialog's body, for the dialog and its screenshots. */
@Composable
internal fun ComputerRuntimeSignInBody(
    session: ComputerRuntimeSignInSession?,
    state: ComputerRuntimeSignInState,
    runtimeName: String,
    computerName: String,
    openUrl: (String) -> Unit,
    runtime: RuntimeAuthRuntime? = session?.runtime,
    provider: ComputerRuntimeSignInProvider? = session?.provider,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            state.choosingRegion -> {
                RaftText(stringResource(Res.string.settings_computers_runtime_auth_region_prompt, runtimeName), style = typography.subhead, color = colors.foregroundMuted)
                // Kimi's own site names, untranslated in every language, with nothing chosen (xxchan).
                val items =
                    RuntimeAuthLoginRegion.entries.map { region ->
                        RaftChoiceListItem(
                            value = region,
                            label =
                                stringResource(
                                    if (region == RuntimeAuthLoginRegion.MainlandCn) {
                                        Res.string.settings_computers_runtime_auth_region_mainland_china
                                    } else {
                                        Res.string.settings_computers_runtime_auth_region_international
                                    }
                                ),
                        )
                    }
                RaftChoiceList(
                    items = items,
                    value = state.region,
                    onValueChange = { session?.chooseRegion(it) },
                    searchPlaceholder = stringResource(Res.string.settings_computers_runtime_auth_region_search),
                    clearSearchLabel = stringResource(Res.string.common_clear),
                    noMatchLabel = "",
                    emptyLabel = "",
                )
            }
            state.done -> {
                RaftBanner(
                    description = stringResource(Res.string.settings_computers_runtime_auth_success, runtimeName, computerName),
                    status = RaftBannerStatus.Success,
                    size = RaftBannerSize.Sm,
                )
                state.account?.text?.let { account ->
                    RaftText(account, modifier = Modifier.testTag(COMPUTER_RUNTIME_SIGN_IN_ACCOUNT_TAG), style = typography.monoMeta, color = colors.foregroundMuted)
                }
            }
            state.failure != null -> ComputerRuntimeAuthFailureBanner(state.failure.problem, state.failure.traceId, runtimeName)
            !state.started ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftSpinner()
                    RaftText(stringResource(Res.string.settings_computers_runtime_auth_starting, computerName), style = typography.subhead, color = colors.foregroundMuted)
                }
            else -> {
                state.authUrl?.let { url ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Our words for the prompt's kind, never the runtime's own English line.
                        RaftText(stringResource(Res.string.settings_computers_runtime_auth_auth_url_hint, runtimeName), style = typography.subhead, color = colors.foregroundMuted)
                        RaftButton(
                            text = stringResource(Res.string.settings_computers_runtime_auth_open_page),
                            onClick = { openUrl(url) },
                            modifier = Modifier.testTag(COMPUTER_RUNTIME_SIGN_IN_OPEN_PAGE_TAG),
                            variant = if (state.codeEntered) RaftButtonVariant.Default else RaftButtonVariant.Primary,
                            size = RaftButtonSize.Lg,
                            trailingIcon = { RaftComputersIcon(RaftLucideIconName.ExternalLink) },
                        )
                    }
                }
                state.deviceCode?.let { device ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RaftText(stringResource(Res.string.settings_computers_runtime_auth_device_code_hint), style = typography.subhead, color = colors.foregroundMuted)
                        RaftTextLink(
                            text = device.verificationUri,
                            onClick = { openUrl(device.verificationUri) },
                            variant = RaftTextLinkVariant.Strong,
                            style = typography.subhead,
                        )
                        ComputerRuntimeAuthCopyableCode(
                            device.userCode,
                            stringResource(Res.string.settings_computers_runtime_auth_copy_code),
                            Modifier.testTag(COMPUTER_RUNTIME_SIGN_IN_DEVICE_CODE_TAG),
                        )
                        device.expiresInSeconds?.let { seconds ->
                            val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
                            RaftText(
                                stringResource(
                                    if (minutes == 1) Res.string.settings_computers_runtime_auth_device_code_expiry_one else Res.string.settings_computers_runtime_auth_device_code_expiry_other,
                                    minutes,
                                ),
                                style = typography.caption,
                                color = colors.foregroundHint,
                            )
                        }
                    }
                }
                state.info?.let { RaftText(it, style = typography.caption, color = colors.foregroundHint) }
                if (state.codePrompt != null) ComputerRuntimeSignInCodeForm(session, state, runtime, provider)
            }
        }
    }
}

/** The pasted code (Claude, Antigravity; a Pi provider's code or landing address). Never logged. */
@Composable
private fun ComputerRuntimeSignInCodeForm(
    session: ComputerRuntimeSignInSession?,
    state: ComputerRuntimeSignInState,
    runtime: RuntimeAuthRuntime?,
    provider: ComputerRuntimeSignInProvider?,
) {
    val busy = state.codeState == ComputerRuntimeSignInCodeState.Submitting || state.codeState == ComputerRuntimeSignInCodeState.Sent
    // Pi's pasted step is ours to word (its prompt's own message is pi's English); the others keep theirs.
    val hint =
        when {
            provider != null ->
                stringResource(
                    if (provider.pastesAddress) Res.string.settings_computers_runtime_auth_pi_paste_address_hint else Res.string.settings_computers_runtime_auth_pi_paste_code_hint
                )
            state.codePrompt?.message != null -> state.codePrompt.message
            runtime == RuntimeAuthRuntime.Antigravity -> stringResource(Res.string.settings_computers_runtime_auth_code_hint_antigravity)
            else -> stringResource(Res.string.settings_computers_runtime_auth_code_hint)
        }
    val error =
        if (state.codeRejected) {
            stringResource(
                if (state.codeState == ComputerRuntimeSignInCodeState.Invalid) Res.string.settings_computers_runtime_auth_code_invalid else Res.string.settings_computers_runtime_auth_code_retry
            )
        } else {
            null
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftField(
            label =
                stringResource(
                    if (provider?.pastesAddress == true) Res.string.settings_computers_runtime_auth_pi_paste_address_label else Res.string.settings_computers_runtime_auth_code_label
                ),
            description = if (error == null) hint else null,
            error = error,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                RaftTextField(
                    value = state.code,
                    onValueChange = { session?.codeChanged(it) },
                    modifier = Modifier.weight(1f).testTag(COMPUTER_RUNTIME_SIGN_IN_CODE_TAG),
                    enabled = !busy,
                    invalid = state.codeRejected,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                )
                RaftButton(
                    text = stringResource(Res.string.settings_computers_runtime_auth_submit_code),
                    onClick = { session?.submitCode() },
                    variant = if (state.codeEntered) RaftButtonVariant.Primary else RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                    loading = state.codeState == ComputerRuntimeSignInCodeState.Submitting,
                    loadingLabel = stringResource(Res.string.settings_computers_runtime_auth_sending_code),
                    enabled = state.codeState != ComputerRuntimeSignInCodeState.Sent && state.code.isNotBlank(),
                )
            }
        }
        if (state.codeState == ComputerRuntimeSignInCodeState.SendFailed) {
            RaftBanner(description = stringResource(Res.string.settings_computers_runtime_auth_code_send_failed), status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
        }
        if (state.codeState == ComputerRuntimeSignInCodeState.Sent) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftSpinner()
                RaftText(stringResource(Res.string.settings_computers_runtime_auth_checking_code), style = RaftTheme.typography.caption, color = RaftTheme.colors.foregroundHint)
            }
        }
    }
}

/**
 * Web `RuntimeSignOutDialog`: names the agents on this Computer that use the sign-in (they keep
 * running; their next start can't authenticate until someone signs in again). An orange confirm.
 * For a Pi provider, that provider's sign-in only (Remove for a key).
 */
@Composable
internal fun ComputerRuntimeSignOutDialog(
    state: ComputerRuntimeSignOutState,
    runtime: RuntimeAuthRuntime,
    runtimeName: String,
    computerName: String,
    agents: List<String>?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val provider = state.provider
    val name = provider?.label ?: runtimeName
    val removesKey = provider?.apiKey == true
    val dismiss = { if (!state.submitting) onDismiss() }
    RaftDialog(
        onDismissRequest = dismiss,
        title =
            stringResource(
                if (removesKey) Res.string.settings_computers_runtime_auth_pi_remove_key_title else Res.string.settings_computers_runtime_auth_sign_out_title,
                name,
            ),
        modifier = Modifier.testTag(COMPUTER_RUNTIME_SIGN_OUT_DIALOG_TAG),
        size = RaftDialogSize.Sm,
        dismissOnClickOutside = !state.submitting,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(stringResource(Res.string.common_cancel), onClick = dismiss, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg, enabled = !state.submitting)
            RaftButton(
                text =
                    stringResource(
                        if (removesKey) Res.string.settings_computers_runtime_auth_pi_remove_key_confirm else Res.string.settings_computers_runtime_auth_sign_out_confirm
                    ),
                onClick = onConfirm,
                modifier = Modifier.testTag(COMPUTER_RUNTIME_SIGN_OUT_CONFIRM_TAG),
                variant = RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                loading = state.submitting,
            )
        },
    ) {
        ComputerRuntimeSignOutBody(state, runtime, runtimeName, computerName, agents)
    }
}

@Composable
internal fun ComputerRuntimeSignOutBody(
    state: ComputerRuntimeSignOutState,
    runtime: RuntimeAuthRuntime,
    runtimeName: String,
    computerName: String,
    agents: List<String>?,
) {
    val provider = state.provider
    val name = provider?.label ?: runtimeName
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftText(
            when {
                provider == null -> stringResource(Res.string.settings_computers_runtime_auth_sign_out_description, name, computerName)
                provider.apiKey -> stringResource(Res.string.settings_computers_runtime_auth_pi_remove_key_description, name, runtimeName, computerName)
                else -> stringResource(Res.string.settings_computers_runtime_auth_pi_sign_out_description, name, runtimeName, computerName)
            },
            style = typography.subhead,
            color = colors.foregroundMuted,
        )
        if (agents == null) {
            // The page does not know which agents use it: nothing said either way.
        } else if (agents.isEmpty()) {
            RaftText(stringResource(Res.string.settings_computers_runtime_auth_sign_out_no_agents, runtimeName), style = typography.subhead, color = colors.foregroundMuted)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val one = agents.size == 1
                RaftText(
                    if (provider != null) {
                        stringResource(
                            if (one) Res.string.settings_computers_runtime_auth_pi_sign_out_agents_one else Res.string.settings_computers_runtime_auth_pi_sign_out_agents_other,
                            name,
                            runtimeName,
                            agents.size,
                        )
                    } else {
                        stringResource(
                            if (one) Res.string.settings_computers_runtime_auth_sign_out_agents_one else Res.string.settings_computers_runtime_auth_sign_out_agents_other,
                            name,
                            agents.size,
                        )
                    },
                    style = typography.subhead,
                    color = colors.foregroundMuted,
                )
                Column(Modifier.testTag(COMPUTER_RUNTIME_SIGN_OUT_AGENTS_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    agents.forEach { agent ->
                        RaftText(agent, style = typography.callout.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        state.problem?.let { ComputerRuntimeAuthFailureBanner(it, state.traceId, name) }
    }
}

/**
 * Web `PiSignInDialog`: every provider pi lists on the Computer, in pi's two groups (an account; an
 * API key), unselectable ones with why. An account goes on to the sign-in dialog; a key is typed
 * here in a password field and saved in Pi's sign-ins on the Computer.
 */
@Composable
internal fun ComputerRuntimePiDialog(session: ComputerRuntimePiSession, runtimeName: String, computerName: String, onClose: () -> Unit) {
    val list = session.list
    val keyStep = session.step == ComputerRuntimePiStep.Key && session.chosen?.account == false
    RaftDialog(
        onDismissRequest = onClose,
        title = stringResource(Res.string.settings_computers_runtime_auth_pi_title, runtimeName),
        modifier = Modifier.testTag(COMPUTER_RUNTIME_PI_DIALOG_TAG),
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            when {
                session.step == ComputerRuntimePiStep.Done ->
                    RaftButton(stringResource(Res.string.settings_computers_runtime_auth_done), onClick = onClose, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg)
                list is ComputerRuntimePiProviderList.Failed -> {
                    RaftButton(stringResource(Res.string.common_close), onClick = onClose, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                    if (list.failure.retryable) {
                        RaftButton(stringResource(Res.string.settings_computers_runtime_auth_retry), onClick = session::load, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg)
                    }
                }
                keyStep -> {
                    RaftButton(
                        stringResource(Res.string.settings_computers_runtime_auth_provider_back),
                        onClick = session::back,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                        enabled = !session.connecting,
                    )
                    RaftButton(
                        stringResource(Res.string.settings_computers_runtime_auth_provider_connect),
                        onClick = session::connect,
                        modifier = Modifier.testTag(COMPUTER_RUNTIME_PI_CONNECT_TAG),
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Lg,
                        enabled = session.apiKey.isNotBlank() && !session.keyRefusedHere,
                        loading = session.connecting,
                    )
                }
                else -> {
                    RaftButton(stringResource(Res.string.common_cancel), onClick = onClose, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                    if (list is ComputerRuntimePiProviderList.Listed) {
                        RaftButton(
                            stringResource(Res.string.settings_computers_runtime_auth_continue),
                            onClick = session::continueWithChoice,
                            variant = RaftButtonVariant.Primary,
                            size = RaftButtonSize.Lg,
                            enabled = session.chosenProvider != null,
                        )
                    }
                }
            }
        },
    ) {
        ComputerRuntimePiBody(session, runtimeName, computerName)
    }
}

@Composable
private fun ComputerRuntimePiBody(session: ComputerRuntimePiSession, runtimeName: String, computerName: String) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val chosenLabel = session.chosenProvider?.label ?: runtimeName
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val list = session.list
        when {
            session.step == ComputerRuntimePiStep.Done ->
                RaftBanner(
                    description =
                        stringResource(
                            if (session.runtime == RuntimeAuthRuntime.Opencode) {
                                Res.string.settings_computers_runtime_auth_opencode_key_success
                            } else {
                                Res.string.settings_computers_runtime_auth_pi_key_success
                            },
                            chosenLabel,
                        ),
                    status = RaftBannerStatus.Success,
                    size = RaftBannerSize.Sm,
                )
            list == ComputerRuntimePiProviderList.Loading ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftSpinner()
                    RaftText(stringResource(Res.string.settings_computers_runtime_auth_provider_loading, computerName), style = typography.subhead, color = colors.foregroundMuted)
                }
            list is ComputerRuntimePiProviderList.Failed -> ComputerRuntimeAuthFailureBanner(list.failure.problem, list.failure.traceId, runtimeName)
            list is ComputerRuntimePiProviderList.Listed ->
                if (session.step == ComputerRuntimePiStep.Key && session.chosen?.account == false) {
                    ComputerRuntimePiKeyForm(session, runtimeName, chosenLabel)
                } else {
                    RaftText(stringResource(Res.string.settings_computers_runtime_auth_pi_choose_prompt, runtimeName, computerName), style = typography.subhead, color = colors.foregroundMuted)
                    ComputerRuntimePiProviderChoices(list, session.chosen, session::choose, runtimeName, session.runtime, session.machineId)
                    RaftText(stringResource(Res.string.settings_computers_runtime_auth_pi_scope_note, runtimeName), style = typography.caption, color = colors.foregroundHint)
                }
        }
    }
}

/** Pi's providers in its two groups, as the web's ChoiceList: an account, then an API key. */
@Composable
internal fun ComputerRuntimePiProviderChoices(
    list: ComputerRuntimePiProviderList.Listed,
    chosen: ComputerRuntimePiChoice?,
    onChoose: (ComputerRuntimePiChoice) -> Unit,
    runtimeName: String,
    runtime: RuntimeAuthRuntime = RuntimeAuthRuntime.Pi,
    machineId: String = "",
) {
    val signedIn = stringResource(Res.string.settings_computers_runtime_auth_pi_signed_in)
    val success = RaftTheme.colors.successStrong
    fun signedInLine(stored: Boolean): AnnotatedString? =
        if (stored) AnnotatedString(signedIn, androidx.compose.ui.text.SpanStyle(color = success)) else null
    val flowText =
        mapOf(
            RuntimeAuthProviderOAuthFlow.DeviceCode to stringResource(Res.string.settings_computers_runtime_auth_pi_flow_device_code),
            RuntimeAuthProviderOAuthFlow.PasteCode to stringResource(Res.string.settings_computers_runtime_auth_pi_flow_paste_code),
            RuntimeAuthProviderOAuthFlow.PasteAddress to stringResource(Res.string.settings_computers_runtime_auth_pi_flow_paste_address),
            RuntimeAuthProviderOAuthFlow.ComputerOnly to stringResource(Res.string.settings_computers_runtime_auth_pi_flow_computer_only),
            RuntimeAuthProviderOAuthFlow.Link to stringResource(Res.string.settings_computers_runtime_auth_opencode_flow_link),
        )
    val moreFields = stringResource(Res.string.settings_computers_runtime_auth_pi_key_more_fields)
    val outside =
        stringResource(
            if (runtime == RuntimeAuthRuntime.Opencode) Res.string.settings_computers_runtime_auth_opencode_key_outside else Res.string.settings_computers_runtime_auth_pi_key_outside
        )
    val accountRows = mutableListOf<RaftChoiceListItem<ComputerRuntimePiChoice>>()
    val keyRows = mutableListOf<RaftChoiceListItem<ComputerRuntimePiChoice>>()
    list.providers.forEach { provider ->
        provider.oauthMethods.forEachIndexed { index, method ->
            // A sign-in that ended as loopback_required on this Computer: greyed out for the session.
            val needsComputer =
                method.flow == RuntimeAuthProviderOAuthFlow.ComputerOnly ||
                    ComputerRuntimeLoopbackRequired.contains(machineId, runtime, provider.id, method.label)
            // OpenCode's method labels ("Login with GitHub (device)") do not name the provider.
            val label =
                if (runtime.choosesOAuthMethod) {
                    stringResource(Res.string.settings_computers_runtime_auth_opencode_method_label, provider.label, method.label)
                } else {
                    method.label
                }
            accountRows +=
                RaftChoiceListItem(
                    value = ComputerRuntimePiChoice(account = true, providerId = provider.id, methodIndex = index),
                    label = label,
                    descriptions =
                        listOfNotNull(
                            AnnotatedString(flowText.getValue(if (needsComputer) RuntimeAuthProviderOAuthFlow.ComputerOnly else method.flow)),
                            signedInLine(provider.stored == "oauth"),
                        ),
                    enabled = !needsComputer,
                    searchText = "${method.label} ${provider.label}",
                    group = "account",
                )
        }
        val entry = provider.apiKeyEntry
        if (entry != null) {
            val why =
                when (entry) {
                    RuntimeAuthProviderKeyEntry.MoreFields -> moreFields
                    RuntimeAuthProviderKeyEntry.Outside -> outside
                    RuntimeAuthProviderKeyEntry.Key -> null
                }
            keyRows +=
                RaftChoiceListItem(
                    value = ComputerRuntimePiChoice(account = false, providerId = provider.id),
                    label = provider.label,
                    descriptions = listOfNotNull(why?.let(::AnnotatedString), signedInLine(provider.stored == "api_key")),
                    enabled = entry == RuntimeAuthProviderKeyEntry.Key,
                    group = "key",
                )
        }
    }
    val groups =
        listOfNotNull(
            RaftChoiceListGroup("account", stringResource(Res.string.settings_computers_runtime_auth_pi_group_account)).takeIf { accountRows.isNotEmpty() },
            RaftChoiceListGroup("key", stringResource(Res.string.settings_computers_runtime_auth_pi_group_api_key)).takeIf { keyRows.isNotEmpty() },
        )
    RaftChoiceList(
        items = accountRows + keyRows,
        value = chosen,
        onValueChange = onChoose,
        searchPlaceholder = stringResource(Res.string.settings_computers_runtime_auth_provider_search),
        clearSearchLabel = stringResource(Res.string.common_clear),
        noMatchLabel = stringResource(Res.string.settings_computers_runtime_auth_provider_no_match),
        emptyLabel = stringResource(Res.string.settings_computers_runtime_auth_provider_empty, runtimeName),
        modifier = Modifier.testTag(COMPUTER_RUNTIME_PI_CHOICES_TAG),
        groups = groups,
    )
}

/** A Pi provider's API key: a password field; a key Pi would run or fill in is refused as it is typed. */
@Composable
private fun ComputerRuntimePiKeyForm(session: ComputerRuntimePiSession, runtimeName: String, providerLabel: String) {
    val colors = RaftTheme.colors
    val error =
        when {
            session.keyRefusedHere ->
                stringResource(
                    if (session.runtime == RuntimeAuthRuntime.Opencode) {
                        Res.string.settings_computers_runtime_auth_opencode_key_refused
                    } else {
                        Res.string.settings_computers_runtime_auth_pi_key_refused
                    }
                )
            session.keyRefusedByServer -> stringResource(Res.string.settings_computers_runtime_auth_provider_field_api_key)
            else -> null
        }
    RaftText(stringResource(Res.string.settings_computers_runtime_auth_pi_key_chosen, providerLabel), style = RaftTheme.typography.subhead, color = colors.foreground)
    RaftField(
        label = stringResource(Res.string.settings_computers_runtime_auth_provider_api_key_label),
        required = true,
        description = if (error == null) stringResource(Res.string.settings_computers_runtime_auth_pi_key_hint, providerLabel) else null,
        error = error,
    ) {
        RaftTextField(
            value = session.apiKey,
            onValueChange = session::apiKeyChanged,
            modifier = Modifier.fillMaxWidth().testTag(COMPUTER_RUNTIME_PI_KEY_TAG),
            enabled = !session.connecting,
            invalid = error != null,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
        )
    }
    RaftText(stringResource(Res.string.settings_computers_runtime_auth_pi_scope_note, runtimeName), style = RaftTheme.typography.caption, color = colors.foregroundHint)
    session.failure?.let { ComputerRuntimeAuthFailureBanner(it.problem, it.traceId, providerLabel) }
}

internal const val COMPUTER_RUNTIME_AUTH_SIGN_IN_TAG = "runtime-auth-sign-in"
internal const val COMPUTER_RUNTIME_AUTH_SIGN_OUT_TAG = "runtime-auth-sign-out"
internal const val COMPUTER_RUNTIME_AUTH_ADD_PROVIDER_TAG = "runtime-auth-add-provider"
internal const val COMPUTER_RUNTIME_AUTH_UNAVAILABLE_TAG = "runtime-auth-unavailable"
internal const val COMPUTER_RUNTIME_AUTH_CLI_UNAVAILABLE_TAG = "runtime-auth-cli-unavailable"
internal const val COMPUTER_RUNTIME_AUTH_INSTALL_LINE_TAG = "runtime-auth-install-line"
internal const val COMPUTER_RUNTIME_AUTH_LOGGED_OUT_TAG = "runtime-auth-logged-out"
internal const val COMPUTER_RUNTIME_AUTH_NO_PROVIDERS_TAG = "runtime-auth-no-providers"
internal const val COMPUTER_RUNTIME_AUTH_PROVIDERS_TAG = "runtime-auth-providers"
internal const val COMPUTER_RUNTIME_AUTH_FAILURE_TAG = "runtime-auth-failure"
internal const val COMPUTER_RUNTIME_SIGN_IN_DIALOG_TAG = "runtime-sign-in-dialog"
internal const val COMPUTER_RUNTIME_SIGN_IN_OPEN_PAGE_TAG = "runtime-sign-in-open-page"
internal const val COMPUTER_RUNTIME_SIGN_IN_DEVICE_CODE_TAG = "runtime-sign-in-device-code"
internal const val COMPUTER_RUNTIME_SIGN_IN_CODE_TAG = "runtime-sign-in-code"
internal const val COMPUTER_RUNTIME_SIGN_IN_ACCOUNT_TAG = "runtime-sign-in-account"
internal const val COMPUTER_RUNTIME_SIGN_OUT_DIALOG_TAG = "runtime-sign-out-dialog"
internal const val COMPUTER_RUNTIME_SIGN_OUT_CONFIRM_TAG = "runtime-sign-out-confirm"
internal const val COMPUTER_RUNTIME_SIGN_OUT_AGENTS_TAG = "runtime-sign-out-agents"
internal const val COMPUTER_RUNTIME_PI_DIALOG_TAG = "runtime-pi-sign-in-dialog"
internal const val COMPUTER_RUNTIME_PI_CHOICES_TAG = "runtime-pi-provider-choices"
internal const val COMPUTER_RUNTIME_PI_KEY_TAG = "runtime-pi-key"
internal const val COMPUTER_RUNTIME_PI_CONNECT_TAG = "runtime-pi-connect"
