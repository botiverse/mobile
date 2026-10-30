package build.raft.app

import build.raft.app.compat.pager.Pager
import build.raft.app.data.AppLocale
import build.raft.app.data.SLACK_BRIDGE_FEATURE_FLAG_KEY
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.StoredAuthSession
import build.raft.app.model.ServerId

// IM Bridges is the web's Settings section (botiverse/slock packages/web/src/components/settings/
// IMBridgesSettingsSection.tsx, `/settings/im-bridges`) in the Settings web page, like
// Administration: the Slack connection is an OAuth flow the web owns. The row is shown only while
// the server's `slack_bridge_v0` flag is on (web `isSlackBridgeSurfaceEnabled`).
internal fun Pager.openSettingsImBridgesPage(storeScope: build.raft.app.data.RaftShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.ImBridges, storeScope)
}

internal val settingsImBridgesWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.ImBridges,
        includeLocaleQuery = true,
        buildWebUrl = ::settingsImBridgesWebViewUrl,
    )

internal fun settingsImBridgesWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    localeTag: String = AppLocale.Default.languageTag
): String {
    val path = standaloneSettingsServerSlug(session)
        ?.takeIf { it.isNotBlank() }
        ?.let { "s/$it/settings/im-bridges" }
        ?: "settings/im-bridges"
    return sharedSettingsHostShellWebUrl(
        webUrl = webBaseUrl,
        path = path,
        extraQueryParameters = listOf("lang" to localeTag)
    )
}

/**
 * Whether Settings shows IM Bridges for [serverId]: web `isSlackBridgeSurfaceEnabled(
 * useServerFeatureFlag(SLACK_BRIDGE_FEATURE_FLAG_KEYS.master))`, i.e. the server's `slack_bridge_v0`
 * evaluated on. No role gate (the web section itself limits management to `manageIntegrations`);
 * off with no server or before the flag resolves.
 */
internal fun settingsImBridgesEnabled(flags: SharedServerFeatureFlags, serverId: String?): Boolean {
    val id = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return flags.isEnabled(ServerId(id), SLACK_BRIDGE_FEATURE_FLAG_KEY)
}
