package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.StoredAuthSession
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

const val SETTINGS_COMPUTERS_PAGE = "compose_settings_computers"

@Page(SETTINGS_COMPUTERS_PAGE, supportInLocal = true)
internal class ComposeSettingsComputersPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsComputersScreen() }
    }
}

internal fun settingsComputersPageData(storeScope: build.raft.app.data.SlockShellStoreScope): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_COMPUTERS_PAGE)
        put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
    }

internal fun Pager.openSettingsComputersPage(storeScope: build.raft.app.data.SlockShellStoreScope) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_COMPUTERS_PAGE,
        settingsComputersPageData(storeScope)
    )
}

@Composable
private fun SettingsComputersScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    StandaloneSettingsWebViewPage(
        pager = pager,
        title = stringResource(MR.strings.settings_computers_title),
        iconName = SlockLucideIconName.Monitor,
        spec = settingsComputersWebViewSpec,
        loadingContent = { SettingsComputersLoadingContent() },
    )
}

/**
 * Computers is owned by the Web settings surface. Mobile supplies only the
 * selected server identity and auth bootstrap, while the Web MVI/data layer
 * loads and mutates the canonical computer state.
 */
private val settingsComputersWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Computers,
        destination = SharedSettingsDestination.Developer,
        backgroundColor = SlockColor.SurfaceCream,
        coverUntilContentCommit = true,
        allowedNavigationSchemes = setOf("http", "https"),
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
