package build.raft.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.RaftLucideIconName
import build.raft.app.data.AppLogin
import build.raft.app.data.NetworkAppLoginsDataSource
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SharedAppLoginsIntent
import build.raft.app.data.SharedAppLoginsState
import build.raft.app.data.SharedAppLoginsStore
import build.raft.app.iconName
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.thread.RaftConnectedAppLogo
import build.raft.app.thread.dispatch
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftToastIntent

/*
 * Settings › Account › Apps you signed in to with Raft (web
 * `features/settings/account/AppLoginsSection.tsx`, `AppLoginRow.tsx`): the Apps that know who the
 * person is, each with Revoke → confirm. Shown only while the list has an entry; a failed read
 * shows nothing either.
 */

internal const val SettingsAppLoginsSectionTag = "app-logins-section"
internal const val SettingsAppLoginRevokeConfirmTag = "app-login-revoke-confirm"

internal fun settingsAppLoginRevokeTag(clientId: String) = "app-login-revoke-$clientId"

internal class SettingsAppLoginsUi(
    val state: SharedAppLoginsState,
    val dispatch: (SharedAppLoginsIntent) -> Unit,
    /** The clock "Signed in {date}" reads against (null: the app's live one; screenshots fix it). */
    val timeContext: RaftTimeContext? = null,
)

/** The page's store, read once when the page opens (web: on mount). */
@Composable
internal fun rememberSettingsAppLoginsStore(networkClient: RaftNetworkClient): SharedAppLoginsStore {
    val scope = rememberCoroutineScope()
    val store = remember(networkClient, scope) { SharedAppLoginsStore(NetworkAppLoginsDataSource(networkClient), scope) }
    LaunchedEffect(store) { store.dispatch(SharedAppLoginsIntent.Refresh) }
    return store
}

/** The list, only while it has an entry; its confirm and toast are [SettingsAppLoginsOverlays]. */
@Composable
internal fun SettingsAppLoginsSection(ui: SettingsAppLoginsUi) {
    val state = ui.state
    if (state.logins.isNotEmpty()) {
        SettingsFormSection(iconName = RaftLucideIconName.LogIn, title = stringResource(Res.string.settings_app_logins_title)) {
            Column(
                Modifier.fillMaxWidth().testTag(SettingsAppLoginsSectionTag).settingsSearchAnchor("app-logins"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsFormDetailText(stringResource(Res.string.settings_app_logins_description))
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    state.logins.forEachIndexed { index, login ->
                        SettingsAppLoginRow(login, divider = index > 0, timeContext = ui.timeContext) { ui.dispatch(SharedAppLoginsIntent.OpenRevoke(login)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsAppLoginsOverlays(ui: SettingsAppLoginsUi) {
    val state = ui.state
    SettingsAppLoginRevokedToast(state.revoked) { ui.dispatch(SharedAppLoginsIntent.RevokedShown) }
    state.pending?.let { login -> SettingsAppLoginRevokeDialog(login, state.revoking, state.revokeFailed, ui.dispatch) }
}

/** One App: logo, name, when, and Revoke. */
@Composable
private fun SettingsAppLoginRow(login: AppLogin, divider: Boolean, timeContext: RaftTimeContext?, onRevoke: () -> Unit) {
    RaftListRow(
        title = login.name,
        variant = RaftListRowVariant.Plain,
        divider = divider,
        description = stringResource(Res.string.settings_app_logins_signed_in, settingsCompactDateLabel(login.grantedAt, timeContext)),
        leading = { RaftConnectedAppLogo(name = login.name, logoUrl = login.logoUrl, size = RaftAvatarSize.Md) },
        trailing = {
            RaftButton(
                text = stringResource(Res.string.settings_app_logins_revoke),
                onClick = onRevoke,
                modifier = Modifier.testTag(settingsAppLoginRevokeTag(login.clientId)),
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Md
            )
        },
    )
}

/** Web ConfirmDialog with the warning (orange) Revoke; a failure shows in it and it stays open. */
@Composable
private fun SettingsAppLoginRevokeDialog(
    login: AppLogin,
    revoking: Boolean,
    failed: Boolean,
    dispatch: (SharedAppLoginsIntent) -> Unit,
) {
    val dismiss = { if (!revoking) dispatch(SharedAppLoginsIntent.CloseRevoke) }
    RaftDialog(
        onDismissRequest = dismiss,
        title = stringResource(Res.string.settings_app_logins_confirm_title, login.name),
        size = RaftDialogSize.Sm,
        description = stringResource(Res.string.settings_app_logins_confirm_body, login.name),
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = dismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !revoking
            )
            RaftButton(
                text = stringResource(Res.string.settings_app_logins_revoke),
                onClick = { dispatch(SharedAppLoginsIntent.ConfirmRevoke) },
                modifier = Modifier.testTag(SettingsAppLoginRevokeConfirmTag),
                variant = RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                loading = revoking
            )
        }
    ) {
        if (failed) {
            RaftBanner(
                description = stringResource(Res.string.settings_app_logins_revoke_failed, login.name),
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm
            )
        }
    }
}

@Composable
private fun SettingsAppLoginRevokedToast(revoked: AppLogin?, onShown: () -> Unit) {
    val toastHost = LocalRaftToastHostState.current
    val title = revoked?.let { stringResource(Res.string.settings_app_logins_revoked, it.name) }
    LaunchedEffect(revoked) {
        if (title == null) return@LaunchedEffect
        toastHost?.show(title, intent = RaftToastIntent.Success)
        onShown()
    }
}
