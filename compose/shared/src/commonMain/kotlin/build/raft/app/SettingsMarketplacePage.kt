package build.raft.app

import build.raft.app.data.StoredAuthSession
import build.raft.app.compat.pager.Pager

internal fun Pager.openSettingsMarketplacePage(storeScope: build.raft.app.data.RaftShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.Marketplace, storeScope)
}

internal val settingsMarketplaceWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Marketplace,
        buildWebUrl = { webBaseUrl, session, _ -> settingsMarketplaceWebViewUrl(webBaseUrl, session) },
    )

internal fun settingsMarketplaceWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?
): String {
    val serverSlug = standaloneSettingsServerSlug(session)
    val path =
        serverSlug
            ?.takeIf { it.isNotBlank() }
            ?.let { "s/$it/settings/integrations" }
            ?: "settings/integrations"
    return sharedSettingsHostShellWebUrl(webBaseUrl, path)
}

internal fun settingsMarketplaceWebViewInjectedJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding? = null,
): String {
    return standaloneSettingsWebViewInjectedJavaScript(session, hostBinding, SettingsWebViewSurface.Marketplace)
}
