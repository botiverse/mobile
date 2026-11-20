package build.raft.app

import build.raft.app.data.StoredAuthSession
import build.raft.app.data.percentEncode
import build.raft.app.compat.pager.Pager

internal fun Pager.openSettingsMarketplacePage(
    storeScope: build.raft.app.data.RaftShellStoreScope,
    /** An App bot's owning App row id (`?installed_app=<id>`, botiverse/slock #14286). */
    installedAppId: String? = null,
) {
    openSettingsWebViewPage(
        surface = SettingsWebViewSurface.Marketplace,
        storeScope = storeScope,
        installedAppId = installedAppId,
    )
}

internal val settingsMarketplaceWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Marketplace,
        buildWebUrl = { webBaseUrl, session, _ -> settingsMarketplaceWebViewUrl(webBaseUrl, session) },
    )

internal fun settingsMarketplaceWebViewSpec(installedAppId: String? = null): StandaloneSettingsWebViewSpec {
    val cleanId = installedAppId?.trim()?.takeIf { it.isNotEmpty() } ?: return settingsMarketplaceWebViewSpec
    return settingsMarketplaceWebViewSpec.copy(
        buildWebUrl = { webBaseUrl, session, _ ->
            settingsMarketplaceWebViewUrl(webBaseUrl, session, installedAppId = cleanId)
        },
    )
}

internal fun settingsMarketplaceWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    installedAppId: String? = null,
): String {
    val serverSlug = standaloneSettingsServerSlug(session)
    val path =
        serverSlug
            ?.takeIf { it.isNotBlank() }
            ?.let { "s/$it/settings/integrations" }
            ?: "settings/integrations"
    val extraQueryParameters =
        installedAppId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { listOf("installed_app" to it.percentEncode()) }
            ?: emptyList()
    return sharedSettingsHostShellWebUrl(webBaseUrl, path, extraQueryParameters)
}
