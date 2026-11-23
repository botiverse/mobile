package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.ACCOUNT_ACTION_PROVIDER_DISCONNECT
import build.raft.app.data.DataResult
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.sendSharedPasswordSetupEmail
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Settings › Account for an account without a password (web
 * `features/settings/account/AccountSection.tsx`, botiverse/slock staging
 * 4be606f7fd789488e69df1095d4af028cc21ee49; the flow came with slock #6005):
 * - the Password part is "Set a password" with Set password by email instead of Change password
 *   (lines 442-469);
 * - Disconnect on the account's one linked sign-in asks "Set a password first", whose confirm sends
 *   the same email instead of disconnecting (lines 139-141, 594-629);
 * - both send `POST /api/auth/forgot-password` for the account's address (`handleSendPasswordSetup`,
 *   lines 223-239) and say how it went, the failure with the server's words and its trace ID.
 */

/** The provider whose "Set a password first" sent the email (web `passwordSetupSentForProvider`). */
internal data class SettingsAccountPasswordSetupProvider(val id: String, val label: String)

/** Web AccountSection's `passwordSetup*` state. */
internal data class SettingsAccountPasswordSetupState(
    /** Web `passwordSetupLoading`: a send is in flight; its buttons say "Sending setup email…" and do nothing. */
    val sending: Boolean = false,
    /** Web `passwordSetupEmailSent`: an email went out while the page was open. */
    val sent: Boolean = false,
    /** Web `passwordSetupSentForProvider`: "Set a password first" sent it; that success line goes under Connected accounts. */
    val sentForProvider: SettingsAccountPasswordSetupProvider? = null,
    /** Web `passwordSetupError`: the last send failed (the server's words when it sent some, and the request's trace ID). */
    val error: ScreenError? = null,
)

internal sealed interface SettingsAccountPasswordSetupIntent {
    /** Set password by email ([provider] null), or "Set a password first"'s Send setup email for [provider]. */
    data class Send(val email: String, val provider: SettingsAccountPasswordSetupProvider? = null) : SettingsAccountPasswordSetupIntent

    /** Disconnect pressed on any provider (web `handleRequestDisconnectProvider`): the last failure and the provider's success line go. */
    data object DisconnectRequested : SettingsAccountPasswordSetupIntent
}

/**
 * Web `handleSendPasswordSetup`: one send at a time (the web turns its buttons off while one runs;
 * a second send meanwhile is dropped here as well). A send clears the last failure; success marks
 * the email sent, for the provider when "Set a password first" sent it; a failure keeps an earlier
 * success, as the web does.
 */
internal class SettingsAccountPasswordSetupStore(
    private val sendEmail: suspend (email: String) -> DataResult<Unit>,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SettingsAccountPasswordSetupState())
    val state: StateFlow<SettingsAccountPasswordSetupState> = mutableState.asStateFlow()

    fun dispatch(intent: SettingsAccountPasswordSetupIntent) {
        when (intent) {
            is SettingsAccountPasswordSetupIntent.Send -> send(intent.email, intent.provider)
            SettingsAccountPasswordSetupIntent.DisconnectRequested ->
                mutableState.update { it.copy(error = null, sentForProvider = null) }
        }
    }

    private fun send(email: String, provider: SettingsAccountPasswordSetupProvider?) {
        if (mutableState.value.sending) return
        mutableState.update { it.copy(sending = true, error = null) }
        scope.launch {
            val result = sendEmail(email)
            mutableState.update { current ->
                when (result) {
                    is DataResult.Content -> current.copy(sending = false, sent = true, sentForProvider = provider)
                    is DataResult.Error -> current.copy(sending = false, error = result.error)
                    // The send never answers these; a failure all the same, never "sent".
                    is DataResult.Empty,
                    is DataResult.NoPermission -> current.copy(sending = false, error = ScreenError(code = "account_password_setup_failed", traceId = null))
                }
            }
        }
    }
}

/** The page's store; it reads nothing (whether there is a password comes with the account, GET /auth/identities). */
@Composable
internal fun rememberSettingsAccountPasswordSetupStore(networkClient: RaftNetworkClient): SettingsAccountPasswordSetupStore {
    val scope = rememberCoroutineScope()
    return remember(networkClient, scope) {
        SettingsAccountPasswordSetupStore(sendEmail = { email -> networkClient.sendSharedPasswordSetupEmail(email) }, scope = scope)
    }
}

/** What the account page needs for an account without a password; null (visual fixtures): Change password as before. */
internal class SettingsAccountPasswordSetupUi(
    /** GET /auth/identities' `passwordConfigured`; null until it is read, or when that read failed. */
    val passwordConfigured: Boolean?,
    val state: SettingsAccountPasswordSetupState,
    val dispatch: (SettingsAccountPasswordSetupIntent) -> Unit,
)

/** What the Password part of the account card shows. */
internal enum class SettingsAccountPasswordRow {
    /** Change password, which opens the current-password form. */
    ChangePassword,

    /** "Set a password" with Set password by email. */
    SetPassword,
}

/**
 * Web AccountSection lines 442-487: only an account the server says has no password gets "Set a
 * password"; one with a password, or whose sign-in methods are not read (null: still loading, or
 * GET /auth/identities failed, the web's `null`) keeps Change password. The web's rows for its
 * `?open=change-password` link (password managers, slock #7182) have no mobile entry.
 */
internal fun settingsAccountPasswordRow(passwordConfigured: Boolean?): SettingsAccountPasswordRow =
    if (passwordConfigured == false) SettingsAccountPasswordRow.SetPassword else SettingsAccountPasswordRow.ChangePassword

/**
 * Web `pendingDisconnectRequiresPassword` (`linkedIdentityCount === 1 && passwordConfigured === false`):
 * disconnecting the one linked sign-in of an account without a password would lock the person out,
 * so the confirm is "Set a password first". [connectedAccounts] are the rows Connected accounts
 * lists; the server lists every social provider there, so its connected rows are the linked ones.
 */
internal fun settingsAccountDisconnectRequiresPassword(
    connectedAccounts: List<RaftSettingsConnectedAccount>,
    passwordConfigured: Boolean?,
): Boolean = passwordConfigured == false && connectedAccounts.count { it.status == "connected" } == 1

/** Web `err.response?.data?.error || formatMessage({ id: "settings.account.failedSendPasswordSetup" })`. */
internal fun settingsAccountPasswordSetupErrorText(error: ScreenError, fallback: String): String =
    error.serverMessage?.takeIf { it.isNotBlank() } ?: fallback

/** slock `PasswordCredentialRequiredError.code`: Disconnect's 409 for the last sign-in of an account without a password. */
internal const val SETTINGS_ACCOUNT_PASSWORD_CREDENTIAL_REQUIRED_CODE = "PASSWORD_CREDENTIAL_REQUIRED"

/**
 * Web `handleDisconnectProvider`: Disconnect refused with `PASSWORD_CREDENTIAL_REQUIRED` (what the
 * page knew of the sign-in methods was out of date) reads them again, so the open confirm turns
 * into "Set a password first".
 */
internal fun settingsAccountDisconnectRefusedForPassword(actionState: SharedSettingsActionState?): Boolean =
    actionState != null &&
        actionState.action == ACCOUNT_ACTION_PROVIDER_DISCONNECT &&
        !actionState.inProgress &&
        actionState.screenError?.serverCode == SETTINGS_ACCOUNT_PASSWORD_CREDENTIAL_REQUIRED_CODE

internal const val SettingsAccountSetPasswordTag = "settings-account-set-password"
internal const val SettingsAccountSetPasswordButtonTag = "settings-account-set-password-by-email"

/**
 * Web AccountSection lines 442-469: "Set a password" over its description, the failure (warning,
 * with the trace ID) and "Password setup email sent…" (success, unless "Set a password first" sent
 * it), then the accent Set password by email: "Sending setup email…" and off while a send runs.
 * The button sits under the text, as the web puts it in a narrow row.
 */
@Composable
internal fun SettingsAccountSetPasswordRow(
    state: SettingsAccountPasswordSetupState,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Web AccountSection lines 442-469 (`ListRow variant="plain"` inside `ListRowGroup`: `px-4 py-3`).
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(SettingsAccountSetPasswordTag),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RaftText(
                stringResource(Res.string.settings_account_set_password),
                style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                color = RaftTheme.colors.foreground,
            )
            RaftText(
                stringResource(Res.string.settings_account_set_password_description),
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundMuted,
            )
        }
        state.error?.let { error ->
            SettingsAccountFailureBanner(
                message = settingsAccountPasswordSetupErrorText(error, stringResource(Res.string.settings_account_password_setup_failed)),
                traceId = error.traceId,
                size = RaftBannerSize.Sm,
            )
        }
        if (state.sent && state.sentForProvider == null) {
            RaftBanner(
                description = stringResource(Res.string.settings_account_password_setup_email_sent),
                status = RaftBannerStatus.Success,
                size = RaftBannerSize.Sm,
            )
        }
        RaftButton(
            text =
                stringResource(
                    if (state.sending) Res.string.settings_account_sending_setup_email else Res.string.settings_account_set_password_by_email
                ),
            onClick = onSend,
            modifier = Modifier.testTag(SettingsAccountSetPasswordButtonTag),
            enabled = !state.sending,
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Md,
        )
    }
}

/** Web AccountSection lines 583-590: under Connected accounts once "Set a password first" sent the email. */
@Composable
internal fun SettingsAccountPasswordSetupSentForProvider(provider: SettingsAccountPasswordSetupProvider) {
    RaftBanner(
        description = stringResource(Res.string.settings_account_password_setup_sent_for_provider, provider.label),
        status = RaftBannerStatus.Success,
        size = RaftBannerSize.Sm,
    )
}

/** A failure from a server request: the warning banner and, under it, the request's trace ID when a server answered. */
@Composable
internal fun SettingsAccountFailureBanner(message: String, traceId: String?, size: RaftBannerSize) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(description = message, status = RaftBannerStatus.Warning, size = size)
        RaftTraceIdNote(traceId)
    }
}
