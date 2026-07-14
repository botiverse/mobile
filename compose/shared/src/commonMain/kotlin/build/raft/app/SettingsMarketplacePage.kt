package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.StoredAuthSession
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    var bodyReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { bodyReady = true }
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = stringResource(MR.strings.settings_connected_apps_title),
            iconName = SlockLucideIconName.Link2,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = {
                        pager.acquireModule<com.tencent.kuikly.core.module.RouterModule>(
                            com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
                        ).closePage()
                    },
                    size = 30.dp,
                    shadowOffset = 2.dp
                )
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (bodyReady) {
                SettingsMarketplaceScreenBody(pager)
            } else {
                SlockCenteredLoading(stringResource(MR.strings.common_loading))
            }
        }
    }
}

@Composable
private fun SettingsMarketplaceScreenBody(pager: Pager) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val sharedStores = remember(bridge) { build.raft.app.data.SlockSharedStoreRegistry.get(bridge) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val authStore = remember(sharedStores, environment.storeScope) {
        sharedStores.authStore(environment.storeScope)
    }
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-marketplace") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    var session by remember { mutableStateOf<StoredAuthSession?>(null) }
    LaunchedEffect(Unit) {
        bridge.log("settings_marketplace_open")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Developer))
    }
    LaunchedEffect(authStore) {
        session = runCatching { authStore.readSession() }.getOrNull()
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    if (settingsState.developer is ScreenLoadState.Loading) {
        SlockCenteredLoading(stringResource(MR.strings.common_loading))
        return
    }
    SettingsMarketplaceWebViewContent(
        session = session,
        webBaseUrl = settingsState.developer.configuredWebBaseUrlOrNull(),
        onUrlChange = { url -> bridge.log("settings_marketplace_webview_url_changed url=$url") },
        onLoadFailure = { reason -> bridge.log("settings_marketplace_webview_load_failure reason=$reason") }
    )
}

@Composable
internal fun SettingsMarketplaceWebViewContent(
    session: StoredAuthSession?,
    webBaseUrl: String?,
    modifier: Modifier = Modifier,
    onUrlChange: (String) -> Unit = {},
    onLoadFailure: (String) -> Unit = {}
) {
    val webUrl = remember(session, webBaseUrl) {
        settingsMarketplaceWebViewUrl(webBaseUrl, session)
    }
    val injectedJavaScript = remember(session) { settingsMarketplaceWebViewInjectedJavaScript(session) }
    if (session == null) {
        SlockCenteredLoading(stringResource(MR.strings.common_loading))
        return
    }
    SlockWebView(
        url = webUrl,
        modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        allowNavigation = true,
        domStorageEnabled = true,
        cacheEnabled = true,
        ephemeral = false,
        injectedJavaScript = injectedJavaScript,
        onUrlChange = onUrlChange,
        onLoadFailure = onLoadFailure
    )
}

internal fun settingsMarketplaceWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?
): String {
    val serverSlug =
        session?.selectedServerSlug
            ?: session?.servers?.firstOrNull { it.id == session.currentServerId }?.slug
            ?: session?.servers?.firstOrNull()?.slug
    val path =
        serverSlug
            ?.takeIf { it.isNotBlank() }
            ?.let { "s/$it/settings/integrations" }
            ?: "settings/integrations"
    return sharedSettingsHostShellWebUrl(webBaseUrl, path)
}

internal fun settingsMarketplaceWebViewInjectedJavaScript(session: StoredAuthSession?): String {
    if (session == null) return ""
    val currentServerSlug =
        session.selectedServerSlug
            ?: session.servers.firstOrNull { it.id == session.currentServerId }?.slug
            ?: session.servers.firstOrNull()?.slug
    val writes =
        buildList {
            add(settingsMarketplaceLocalStorageWrite("slock_access_token", session.accessToken))
            add(settingsMarketplaceLocalStorageWrite("slock_refresh_token", session.refreshToken))
            currentServerSlug?.takeIf { it.isNotBlank() }?.let {
                add(settingsMarketplaceLocalStorageWrite("slock_last_server_slug", it))
            }
        }.joinToString(separator = ";")
    return """
        (function() {
          try {
            $writes;
          } catch (error) {
            console.error("slock_mobile_marketplace_injected_js_error", error && (error.stack || error.message || error));
          }
        })();
    """.trimIndent()
}

private val settingsMarketplaceWebViewJson = Json

private fun settingsMarketplaceLocalStorageWrite(key: String, value: String): String =
    "window.localStorage.setItem(${settingsMarketplaceWebViewJson.encodeToString(key)}, ${settingsMarketplaceWebViewJson.encodeToString(value)})"
