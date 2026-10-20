package build.raft.app

import build.raft.app.data.StoredAuthSession
import build.raft.app.compat.pager.Pager

// MCP Servers renders the web settings tab in the in-app Settings WebView, the
// Administration / Billing / Applications pattern: the web owns the surface and
// the app adds no native mirror of it.
internal fun Pager.openSettingsMcpServersPage(storeScope: build.raft.app.data.RaftShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.McpServers, storeScope)
}

internal val settingsMcpServersWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.McpServers,
        buildWebUrl = { webBaseUrl, session, _ -> settingsMcpServersWebViewUrl(webBaseUrl, session) },
    )

internal fun settingsMcpServersWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?
): String {
    val serverSlug = standaloneSettingsServerSlug(session)
    val path =
        serverSlug
            ?.takeIf { it.isNotBlank() }
            ?.let { "s/$it/settings/mcp-servers" }
            ?: "settings/mcp-servers"
    return sharedSettingsHostShellWebUrl(webBaseUrl, path)
}
