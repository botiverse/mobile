package build.raft.app

import build.raft.app.data.AppLocale
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.StoredAuthSession
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

const val SETTINGS_ADMINISTRATION_PAGE = "compose_settings_administration"

// Administration renders the WEB settings page in an in-app WebView instead of
// a native re-implementation (artin, 2026-07-14): the admin surface (admins /
// invites / join links / pre-join agreement / onboarding / member permissions /
// translation) changes often on web and keeping a native mirror in visual
// parity was a constant drift source. Same architecture as Plan & Billing
// (task #227): its own single-page pager so the WebView gets a full-height
// native viewport and a clean lifecycle.
@Page(SETTINGS_ADMINISTRATION_PAGE, supportInLocal = true)
internal class ComposeSettingsAdministrationPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsAdministrationScreen() }
    }
}

internal fun Pager.openSettingsAdministrationPage(storeScope: build.raft.app.data.SlockShellStoreScope) {
    acquireModule<com.tencent.kuikly.core.module.RouterModule>(
        com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
    ).openPage(
        SETTINGS_ADMINISTRATION_PAGE,
        com.tencent.kuikly.core.nvi.serialization.json.JSONObject().apply {
            put("pageName", SETTINGS_ADMINISTRATION_PAGE)
            put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
            storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
        }
    )
}

@Composable
private fun SettingsAdministrationScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    StandaloneSettingsWebViewPage(
        pager = pager,
        title = stringResource(MR.strings.settings_server_administration_title),
        iconName = SlockLucideIconName.Shield,
        spec = settingsAdministrationWebViewSpec,
        loadingContent = { SettingsAdministrationLoadingContent() },
    )
}

private val settingsAdministrationWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Administration,
        destination = SharedSettingsDestination.Administration,
        backgroundColor = SlockColor.SurfaceWhite,
        includeLocaleQuery = true,
        buildWebUrl = ::settingsAdministrationWebViewUrl,
    )

@Composable
private fun SettingsAdministrationLoadingContent() {
    Column(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(4) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .background(SlockColor.SurfaceCream)
            )
        }
    }
}

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
