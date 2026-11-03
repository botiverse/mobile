package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ACCOUNT_DELETION_FEATURE_FLAG_KEY
import build.raft.app.data.AppLocale
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ServerId
import kotlinx.coroutines.launch

// Self-service account deletion is the WEB flow (botiverse/slock #12459, Settings > Account >
// Delete Account) in the Settings WebView, with no native re-implementation (artin, 2026-11-02).
// The same page shows the pending state (with its Cancel) to a person whose deletion is scheduled;
// the app opens it for them on the first 403 `account_deletion_pending` (app shell). A scheduled
// deletion ends with the page's sign-out, which tells the host with `session:logout`.

internal fun Pager.openSettingsAccountDeletionPage(storeScope: RaftShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.AccountDeletion, storeScope)
}

internal val settingsAccountDeletionWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.AccountDeletion,
        includeLocaleQuery = true,
        // The page emits `session:logout` through window.RaftHost (embed/hostBridge.ts).
        hostEventsEnabled = true,
        buildWebUrl = ::settingsAccountDeletionWebViewUrl,
    )

internal fun settingsAccountDeletionWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    localeTag: String = AppLocale.Default.languageTag
): String {
    val path = standaloneSettingsServerSlug(session)
        ?.takeIf { it.isNotBlank() }
        ?.let { "s/$it/settings/account/delete" }
        ?: "settings/account/delete"
    return sharedSettingsHostShellWebUrl(
        webUrl = webBaseUrl,
        path = path,
        extraQueryParameters = listOf("lang" to localeTag)
    )
}

/**
 * The Settings intent a host event of the deletion page asks for: `session:logout` (the page signed
 * out, after scheduling the deletion or from its pending state) is Settings > Account > Log out.
 * Cancelling needs nothing: the page goes back to Settings > Account by itself.
 */
internal fun settingsAccountDeletionHostIntent(kind: String): SharedSettingsIntent? =
    when (kind) {
        RAFT_HOST_EVENT_SESSION_LOGOUT -> SharedSettingsIntent.Logout
        else -> null
    }

/** What Settings > Account > Delete account does. */
internal enum class SettingsAccountDeletionEntry {
    /** No row (the Compose desktop app without the flag: it cannot send the ticket). */
    Hidden,

    /** The Hands feedback ticket and its confirm dialog (RaftAccountDeletionRequestStore). */
    Ticket,

    /** The web deletion page in the Settings WebView, on every platform. */
    WebPage,
}

/** `account_deletion_v0` on: the web page; off or unresolved: the ticket where the host can send it. */
internal fun settingsAccountDeletionEntry(
    deletionFlagOn: Boolean,
    ticketSupported: Boolean,
): SettingsAccountDeletionEntry =
    when {
        deletionFlagOn -> SettingsAccountDeletionEntry.WebPage
        ticketSupported -> SettingsAccountDeletionEntry.Ticket
        else -> SettingsAccountDeletionEntry.Hidden
    }

/** `account_deletion_v0` evaluated on for [serverId]; off with no server or before it resolves. */
internal fun settingsAccountDeletionFlagOn(flags: SharedServerFeatureFlags, serverId: String?): Boolean {
    val id = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return flags.isEnabled(ServerId(id), ACCOUNT_DELETION_FEATURE_FLAG_KEY)
}

@Composable
internal fun SettingsAccountDeletionWebViewPage(pager: Pager) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authDataSource = remember(sharedStores, storeScope) {
        sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope)
    }
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    StandaloneSettingsWebViewPage(
        pager = pager,
        title = stringResource(Res.string.settings_account_delete),
        iconName = RaftLucideIconName.Trash2,
        spec = settingsAccountDeletionWebViewSpec,
        onHostMessage = { kind, _ ->
            when (settingsAccountDeletionHostIntent(kind)) {
                SharedSettingsIntent.Logout ->
                    if (!loggingOut) {
                        loggingOut = true
                        bridge.log("settings_account_deletion_host_logout")
                        scope.launch { pager.performStandaloneSettingsLogout(authDataSource) }
                    }
                else -> Unit
            }
        },
    )
}
