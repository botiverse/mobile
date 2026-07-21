package build.raft.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalIconButton
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
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    var bodyReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { bodyReady = true }
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream)) {
        SlockTopPageHeader(
            title = stringResource(MR.strings.settings_computers_title),
            iconName = SlockLucideIconName.Monitor,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
                    size = 30.dp,
                    shadowOffset = 2.dp
                )
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(SlockColor.SurfaceCream)) {
            if (bodyReady) {
                SettingsComputersScreenBody(pager)
            } else {
                SettingsComputersLoadingContent()
            }
        }
    }
}

@Composable
private fun SettingsComputersScreenBody(pager: Pager) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val sharedStores = remember(bridge) { build.raft.app.data.SlockSharedStoreRegistry.get(bridge) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val authStore = remember(sharedStores, environment.storeScope) {
        sharedStores.authStore(environment.storeScope)
    }
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-computers") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    var session by remember { mutableStateOf<StoredAuthSession?>(null) }
    LaunchedEffect(Unit) {
        bridge.log("settings_computers_open")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Developer))
    }
    LaunchedEffect(authStore) {
        session = runCatching { authStore.readSession() }.getOrNull()
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    SettingsComputersWebViewContent(
        session = session,
        webBaseUrl = settingsState.developer.configuredWebBaseUrlOrNull(),
        bottomContentPadding = 0.dp,
        onUrlChange = { url -> bridge.log("settings_computers_webview_url_changed url=$url") },
        onLoadFailure = { reason -> bridge.log("settings_computers_webview_load_failure reason=$reason") }
    )
}

/**
 * Computers is owned by the Web settings surface. Mobile supplies only the
 * selected server identity and auth bootstrap, while the Web MVI/data layer
 * loads and mutates the canonical computer state.
 */
@Composable
internal fun SettingsComputersWebViewContent(
    session: StoredAuthSession?,
    webBaseUrl: String?,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
    onUrlChange: (String) -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    controller: SlockWebViewController = rememberSlockWebViewController(),
    onNavigationStateChange: (SlockWebViewNavigationState) -> Unit = {}
) {
    val webUrl = remember(session, webBaseUrl) {
        settingsComputersWebViewUrl(webBaseUrl, session)
    }
    val injectedJavaScript = remember(session) {
        settingsComputersWebViewInjectedJavaScript(session)
    }
    var contentVisible by remember(webUrl) { mutableStateOf(false) }
    if (session == null) {
        SettingsComputersLoadingContent()
        return
    }
    Box(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceCream)) {
        SlockWebView(
            url = webUrl,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.SurfaceCream)
                    .padding(bottom = bottomContentPadding)
                    .alpha(if (contentVisible) 1f else 0f),
            allowNavigation = true,
            allowedNavigationSchemes = setOf("http", "https"),
            domStorageEnabled = true,
            cacheEnabled = true,
            ephemeral = false,
            injectedJavaScript = injectedJavaScript,
            controller = controller,
            onLoadSuccess = { contentVisible = true },
            onUrlChange = onUrlChange,
            onLoadFailure = onLoadFailure,
            onNavigationStateChange = onNavigationStateChange
        )
        if (!contentVisible) SettingsComputersLoadingContent()
    }
}

@Composable
internal fun SettingsComputersLoadingContent() {
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream))
}

internal fun settingsComputersWebViewUrl(
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
            ?.let { "s/$it/computers" }
            ?: "computers"
    return sharedSettingsHostShellWebUrl(webBaseUrl, path)
}

internal fun settingsComputersWebViewInjectedJavaScript(session: StoredAuthSession?): String {
    val writes = settingsWebViewAuthStorageJavaScript(session)
    return """
        (function() {
          try {
            $writes;
          } catch (error) {
            console.error("slock_mobile_computers_injected_js_error", error && (error.stack || error.message || error));
          }
        })();
    """.trimIndent()
}
