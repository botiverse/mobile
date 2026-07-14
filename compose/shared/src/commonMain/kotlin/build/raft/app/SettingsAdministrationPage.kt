package build.raft.app

import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    // Header on the first frame, WebView body deferred one frame — same
    // attach-order reasoning as Billing (task #227): the standalone settings
    // environment + native WebView attach otherwise paint before the header.
    var bodyReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { bodyReady = true }
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = stringResource(MR.strings.settings_server_administration_title),
            iconName = SlockLucideIconName.Shield,
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
                SettingsAdministrationScreenBody(pager)
            } else {
                SettingsAdministrationLoadingContent()
            }
        }
    }
}

@Composable
private fun SettingsAdministrationScreenBody(pager: Pager) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val sharedStores = remember(bridge) { build.raft.app.data.SlockSharedStoreRegistry.get(bridge) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val authStore = remember(sharedStores, environment.storeScope) {
        sharedStores.authStore(environment.storeScope)
    }
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-administration") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    var session by remember { mutableStateOf<StoredAuthSession?>(null) }
    LaunchedEffect(Unit) {
        bridge.log("settings_administration_open")
        // Developer snapshot carries any configured web base URL override; the
        // destination open keeps refresh/auth behavior aligned with Billing.
        settingsViewModel.dispatch(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Administration))
    }
    LaunchedEffect(authStore) {
        session = runCatching { authStore.readSession() }.getOrNull()
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    SettingsAdministrationWebViewContent(
        session = session,
        webBaseUrl = settingsState.developer.configuredWebBaseUrlOrNull(),
        onUrlChange = { url -> bridge.log("settings_administration_webview_url_changed url=$url") },
        onLoadFailure = { reason -> bridge.log("settings_administration_webview_load_failure reason=$reason") }
    )
}

@Composable
internal fun SettingsAdministrationWebViewContent(
    session: StoredAuthSession?,
    webBaseUrl: String?,
    modifier: Modifier = Modifier,
    onUrlChange: (String) -> Unit = {},
    onLoadFailure: (String) -> Unit = {}
) {
    val localeTag =
        remember {
            try {
                KeyValueLocalePreferenceStore(slockSharedPreferenceStore()).readLocaleSync().languageTag
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                AppLocale.Default.languageTag
            }
        }
    val webUrl = remember(session, webBaseUrl, localeTag) { settingsAdministrationWebViewUrl(webBaseUrl, session, localeTag) }
    val injectedJavaScript = remember(session) { settingsAdministrationWebViewInjectedJavaScript(session) }
    if (session == null) {
        SettingsAdministrationLoadingContent()
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

private val settingsAdministrationWebViewJson = Json

internal fun settingsAdministrationWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    localeTag: String = AppLocale.Default.languageTag
): String {
    val serverSlug = session?.selectedServerSlug
        ?: session?.servers?.firstOrNull { it.id == session.currentServerId }?.slug
        ?: session?.servers?.firstOrNull()?.slug
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

internal fun settingsAdministrationWebViewInjectedJavaScript(session: StoredAuthSession?): String {
    if (session == null) return ""
    val currentServerSlug = session.selectedServerSlug
        ?: session.servers.firstOrNull { it.id == session.currentServerId }?.slug
        ?: session.servers.firstOrNull()?.slug
    val writes =
        buildList {
            add(settingsAdministrationLocalStorageWrite("slock_access_token", session.accessToken))
            add(settingsAdministrationLocalStorageWrite("slock_refresh_token", session.refreshToken))
            currentServerSlug?.takeIf { it.isNotBlank() }?.let {
                add(settingsAdministrationLocalStorageWrite("slock_last_server_slug", it))
            }
        }
            .joinToString(separator = ";")
    return """
        (function() {
          try {
            $writes;
          } catch (error) {
            console.error("slock_mobile_administration_injected_js_error", error && (error.stack || error.message || error));
          }
        })();
    """.trimIndent()
}

private fun settingsAdministrationLocalStorageWrite(key: String, value: String): String =
    "window.localStorage.setItem(${settingsAdministrationWebViewJson.encodeToString(key)}, ${settingsAdministrationWebViewJson.encodeToString(value)})"
