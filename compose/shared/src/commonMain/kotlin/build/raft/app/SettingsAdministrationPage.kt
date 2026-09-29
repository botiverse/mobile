package build.raft.app

import build.raft.app.data.AppLocale
import build.raft.app.data.StoredAuthSession
import com.tencent.kuikly.core.pager.Pager

// Administration renders the WEB settings page in an in-app WebView instead of
// a native re-implementation (artin, 2026-07-14): the admin surface (admins /
// invites / join links / pre-join agreement / onboarding / member permissions /
// translation) changes often on web and keeping a native mirror in visual
// parity was a constant drift source. It uses the single production Settings
// WebView page so attach, cover, lifecycle, and Back/history behavior cannot drift.
internal fun Pager.openSettingsAdministrationPage(storeScope: build.raft.app.data.SlockShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.Administration, storeScope)
}

internal val settingsAdministrationWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Administration,
        includeLocaleQuery = true,
        buildWebUrl = ::settingsAdministrationWebViewUrl,
    )

internal fun settingsAdministrationWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    localeTag: String = AppLocale.Default.languageTag
): String {
    val serverSlug = standaloneSettingsServerSlug(session)
    val path = serverSlug
        ?.takeIf { it.isNotBlank() }
        ?.let { "s/$it/settings/administration" }
        ?: "settings/administration"
    return sharedSettingsHostShellWebUrl(
        webUrl = webBaseUrl,
        path = path,
        extraQueryParameters = listOf("lang" to localeTag)
    )
}

internal fun settingsAdministrationWebViewInjectedJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding? = null,
): String {
    return standaloneSettingsWebViewInjectedJavaScript(session, hostBinding, SettingsWebViewSurface.Administration)
}
