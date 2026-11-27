package build.raft.app.settings

import build.raft.app.compat.pager.Pager
import build.raft.app.data.AppLocale
import build.raft.app.data.StoredAuthSession
import build.raft.app.sharedSettingsHostShellWebUrl

// App authorizations (botiverse/slock #16572 050f7d16a, formerly Authorized accounts #16176):
// `/s/<slug>/settings/app-authorizations`, visible to every member of the current server
// (guests too) in the "On <server>" group. Holds both the member's connected accounts (RFC 087,
// server flag `mcp_personal_accounts`, never guests) and their own link-preview toggles.
internal fun Pager.openSettingsAppAuthorizationsPage(
    storeScope: build.raft.app.data.RaftShellStoreScope,
    anchor: SettingsSearchAnchorRequest? = null,
) {
    openSettingsWebViewPage(SettingsWebViewSurface.AppAuthorizations, storeScope, anchor = anchor)
}

internal val settingsAppAuthorizationsWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.AppAuthorizations,
        allowNavigation = true,
        lockToWebOrigin = true,
        buildWebUrl = ::settingsAppAuthorizationsWebViewUrl,
    )

internal fun settingsAppAuthorizationsWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    localeTag: String = AppLocale.Default.languageTag,
): String {
    val path =
        standaloneSettingsServerSlug(session)
            ?.takeIf { it.isNotBlank() }
            ?.let { "s/$it/settings/app-authorizations" }
            ?: "settings/app-authorizations"
    return sharedSettingsHostShellWebUrl(
        webUrl = webBaseUrl,
        path = path,
        extraQueryParameters = listOf("lang" to localeTag),
    )
}
