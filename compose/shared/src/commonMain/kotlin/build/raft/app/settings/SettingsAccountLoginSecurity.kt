package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.data.AccountLoginSecurity
import build.raft.app.data.NetworkAccountLoginSecurityDataSource
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SharedAccountLoginSecurityIntent
import build.raft.app.data.SharedAccountLoginSecurityState
import build.raft.app.data.SharedAccountLoginSecurityStore
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowValue
import build.raft.designsystem.components.RaftListRowVariant

/*
 * Settings › Account › Login & security: the web Account page's Passkeys and Two-step
 * verification sections (slock `components/settings/PasskeysSection.tsx`,
 * `TwoStepVerificationSection.tsx`), read-only here. Each row opens its web section
 * (`/settings/account#passkeys`, `#two-step`, the ids those sections carry for
 * `useScrollToSettingsSection`) in the system browser: WebAuthn does not work in the in-app
 * WebView, and Raft's own App Links would route a plain VIEW back into the app.
 */

/** A web Account section the group links to, by its `#id`. */
internal enum class SettingsAccountLoginSecuritySection(val webAnchor: String) {
    Passkeys("passkeys"),
    TwoStep("two-step"),
}

internal const val SettingsAccountLoginSecurityGroupTag = "settings-account-login-security"
internal const val SettingsAccountTwoStepRowTag = "settings-account-two-step-row"
internal const val SettingsAccountPasskeysRowTag = "settings-account-passkeys-row"

/**
 * The web page of [section] for this account, on the Developer environment's [webBaseUrl] (AGENTS.md
 * Environment Web Link URL Rule) and the current server's `/s/{slug}` when there is one.
 */
internal fun settingsAccountLoginSecurityWebUrl(
    webBaseUrl: String?,
    serverSlug: String?,
    section: SettingsAccountLoginSecuritySection,
): String {
    val path = serverSlug?.takeIf { it.isNotBlank() }?.let { "s/$it/settings/account" } ?: "settings/account"
    return "${sharedSettingsWebUrl(webBaseUrl, path)}#${section.webAnchor}"
}

/**
 * What a row's tap does: the web section of this account in the system browser, whatever the
 * link setting (passkeys need WebAuthn, which the in-app WebView lacks). [openInBrowser] is
 * `bridge.openUrl(url, inBrowser = true)` (a browser even for Raft's own App Link origin).
 */
internal fun settingsAccountLoginSecurityOpener(
    webBaseUrl: String?,
    serverSlug: () -> String?,
    log: (String) -> Unit,
    openInBrowser: (String) -> Unit,
): (SettingsAccountLoginSecuritySection) -> Unit = { section ->
    log("settings_account_login_security_open section=${section.webAnchor}")
    openInBrowser(settingsAccountLoginSecurityWebUrl(webBaseUrl, serverSlug(), section))
}

/** The group's state and where its taps go. */
internal class SettingsAccountLoginSecurityUi(
    val state: SharedAccountLoginSecurityState,
    val onOpen: (SettingsAccountLoginSecuritySection) -> Unit,
    val onRetry: () -> Unit,
)

/**
 * The page's store: read when the page shows and again on every return to the foreground (the
 * person may just have changed it in the browser). Lifecycle ON_RESUME covers both: the observer
 * receives it when added to a resumed page.
 */
@Composable
internal fun rememberSettingsAccountLoginSecurityStore(networkClient: RaftNetworkClient): SharedAccountLoginSecurityStore {
    val scope = rememberCoroutineScope()
    val store = remember(networkClient, scope) {
        SharedAccountLoginSecurityStore(NetworkAccountLoginSecurityDataSource(networkClient), scope)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, store) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) store.dispatch(SharedAccountLoginSecurityIntent.Refresh)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return store
}

@Composable
internal fun SettingsAccountLoginSecurityGroup(ui: SettingsAccountLoginSecurityUi) {
    val state = ui.state
    val title = stringResource(Res.string.settings_account_login_security_title)
    val security = state.security
    if (security == null) {
        val error = state.error ?: return
        SettingsLoadStateCard(
            iconName = RaftLucideIconName.ShieldCheck,
            title = title,
            detail = stringResource(Res.string.settings_account_login_security_load_failed),
            retryLabel = stringResource(Res.string.common_retry),
            onRetry = ui.onRetry,
            loading = state.loading,
            traceId = error.traceId,
        )
        return
    }
    SettingsFormSection(iconName = RaftLucideIconName.ShieldCheck, title = title) {
        RaftListRowGroup(Modifier.fillMaxWidth().testTag(SettingsAccountLoginSecurityGroupTag)) {
            SettingsAccountLoginSecurityRows(security, ui.onOpen)
        }
        SettingsFormDetailText(stringResource(Res.string.settings_account_login_security_footer))
    }
}

@Composable
private fun SettingsAccountLoginSecurityRows(
    security: AccountLoginSecurity,
    onOpen: (SettingsAccountLoginSecuritySection) -> Unit,
) {
    // Web order: Passkeys, then Two-step verification.
    RaftListRow(
        title = stringResource(Res.string.settings_account_passkeys_title),
        modifier =
            Modifier
                .testTag(SettingsAccountPasskeysRowTag)
                .settingsSearchAnchor(SettingsAccountLoginSecuritySection.Passkeys.webAnchor),
        onClick = { onOpen(SettingsAccountLoginSecuritySection.Passkeys) },
        variant = RaftListRowVariant.Plain,
        trailing = {
            RaftListRowValue(settingsAccountPasskeyCountLabel(security.passkeyCount))
            SettingsAccountLoginSecurityOutbound()
        },
    )
    RaftListRow(
        title = stringResource(Res.string.settings_account_two_step_title),
        modifier =
            Modifier
                .testTag(SettingsAccountTwoStepRowTag)
                .settingsSearchAnchor(SettingsAccountLoginSecuritySection.TwoStep.webAnchor),
        onClick = { onOpen(SettingsAccountLoginSecuritySection.TwoStep) },
        variant = RaftListRowVariant.Plain,
        divider = true,
        // Web: the state badge right after the title (success soft On, default soft Off).
        titleTrailing = {
            RaftBadge(
                text =
                    stringResource(
                        if (security.twoStepEnabled) Res.string.settings_account_two_step_on else Res.string.settings_account_two_step_off
                    ),
                appearance = RaftBadgeAppearance.Soft,
                variant = if (security.twoStepEnabled) RaftBadgeVariant.Success else RaftBadgeVariant.Default,
            )
        },
        trailing = { SettingsAccountLoginSecurityOutbound() },
    )
}

/** raft-ui 0.26 (#758): a row that leaves Raft ends in ↗, in the hint ink; decorative. */
@Composable
private fun SettingsAccountLoginSecurityOutbound() {
    RaftLucideIcon(RaftLucideIconName.ArrowUpRight, Modifier.size(16.dp), color = raftArgbHex(RaftColor.ForegroundHint))
}

/** Web `settings.passkeys.empty` for none, else `settings.twoStep.passkeyCount`. */
@Composable
private fun settingsAccountPasskeyCountLabel(count: Int): String =
    when (count) {
        0 -> stringResource(Res.string.settings_account_passkeys_none)
        1 -> stringResource(Res.string.settings_account_passkeys_count_one, count)
        else -> stringResource(Res.string.settings_account_passkeys_count_other, count)
    }
