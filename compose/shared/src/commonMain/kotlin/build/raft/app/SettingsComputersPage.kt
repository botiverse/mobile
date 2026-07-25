package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.StoredAuthSession
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.pager.Pager

internal fun settingsComputersPageData(storeScope: build.raft.app.data.SlockShellStoreScope) =
    settingsWebViewPageData(SettingsWebViewSurface.Computers, storeScope)

internal fun Pager.openSettingsComputersPage(storeScope: build.raft.app.data.SlockShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.Computers, storeScope)
}

/**
 * Computers is owned by the Web settings surface. Mobile supplies only the
 * selected server identity and auth bootstrap, while the Web MVI/data layer
 * loads and mutates the canonical computer state.
 */
internal val settingsComputersWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Computers,
        destination = SharedSettingsDestination.Developer,
        headerFollowsDocumentTitle = true,
        buildWebUrl = { webBaseUrl, session, _ -> settingsComputersWebViewUrl(webBaseUrl, session) },
    )

@Composable
internal fun SettingsComputersLoadingContent() {
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream))
}

internal fun settingsComputersWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?
): String {
    val serverSlug = standaloneSettingsServerSlug(session)
    val path =
        serverSlug
            ?.takeIf { it.isNotBlank() }
            ?.let { "s/$it/computers" }
            ?: "computers"
    return sharedSettingsHostShellWebUrl(webBaseUrl, path)
}

internal fun settingsComputersWebViewInjectedJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding? = null,
): String {
    return standaloneSettingsWebViewInjectedJavaScript(session, hostBinding, SettingsWebViewSurface.Computers)
}
