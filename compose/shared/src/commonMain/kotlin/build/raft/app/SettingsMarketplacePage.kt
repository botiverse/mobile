package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.StoredAuthSession
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

const val SETTINGS_MARKETPLACE_PAGE = "compose_settings_marketplace"

@Page(SETTINGS_MARKETPLACE_PAGE, supportInLocal = true)
internal class ComposeSettingsMarketplacePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsMarketplaceScreen() }
    }
}

internal fun Pager.openSettingsMarketplacePage(storeScope: build.raft.app.data.SlockShellStoreScope) {
    acquireModule<com.tencent.kuikly.core.module.RouterModule>(
        com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
    ).openPage(
        SETTINGS_MARKETPLACE_PAGE,
        com.tencent.kuikly.core.nvi.serialization.json.JSONObject().apply {
            put("pageName", SETTINGS_MARKETPLACE_PAGE)
            put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
            storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
        }
    )
}

@Composable
private fun SettingsMarketplaceScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    StandaloneSettingsWebViewPage(
        pager = pager,
        title = stringResource(MR.strings.settings_connected_apps_title),
        iconName = SlockLucideIconName.Link2,
        spec = settingsMarketplaceWebViewSpec,
        loadingContent = { SlockCenteredLoading(stringResource(MR.strings.common_loading)) },
    )
}

private val settingsMarketplaceWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Marketplace,
        destination = SharedSettingsDestination.Developer,
        backgroundColor = SlockColor.SurfaceWhite,
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
