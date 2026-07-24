package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.slockSharedPreferenceStore
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel

/**
 * Product-specific parameters for a standalone Settings Web surface. Route/pageData continues to
 * carry only shell identity; canonical environment, auth, and product state are resolved by the
 * shared host from the shell stores.
 */
internal data class StandaloneSettingsWebViewSpec(
    val surface: SettingsWebViewSurface,
    val destination: SharedSettingsDestination,
    val backgroundColor: Color,
    val includeLocaleQuery: Boolean = false,
    val coverUntilContentCommit: Boolean = false,
    val consumeWebHistoryOnBack: Boolean = false,
    val allowedNavigationSchemes: Set<String> = emptySet(),
    val bottomContentPadding: Dp = 0.dp,
    val buildWebUrl: (webBaseUrl: String?, session: StoredAuthSession?, localeTag: String) -> String,
)

/**
 * Shared native page shell for Computers, Billing, Marketplace, and Administration.
 *
 * The header is committed on the first frame and the heavier store/WebView body is attached on the
 * next frame. This keeps the native header stable while the native WebView is created.
 */
@Composable
internal fun StandaloneSettingsWebViewPage(
    pager: Pager,
    title: String,
    iconName: SlockLucideIconName,
    spec: StandaloneSettingsWebViewSpec,
    loadingContent: @Composable () -> Unit,
) {
    var bodyReady by remember { mutableStateOf(false) }
    val webViewController = rememberSlockWebViewController()
    var navigationState by remember(spec.surface) { mutableStateOf(SlockWebViewNavigationState()) }
    LaunchedEffect(Unit) { bodyReady = true }

    fun closePage() {
        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    fun goBackOrClose() {
        if (
            standaloneSettingsShouldConsumeWebHistoryBack(
                consumeWebHistoryOnBack = spec.consumeWebHistoryOnBack,
                canGoBack = navigationState.canGoBack,
            )
        ) {
            webViewController.goBack()
        } else {
            closePage()
        }
    }

    SlockBackHandler(
        enabled = spec.consumeWebHistoryOnBack,
        label = "standalone_settings_${spec.surface.diagnosticsName}",
        onBack = ::goBackOrClose,
    )

    Column(modifier = Modifier.fillMaxSize().background(spec.backgroundColor)) {
        SlockTopPageHeader(
            title = title,
            iconName = iconName,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = ::goBackOrClose,
                    size = 30.dp,
                    shadowOffset = 2.dp,
                )
            },
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(spec.backgroundColor),
        ) {
            if (bodyReady) {
                StandaloneSettingsWebViewBody(
                    pager = pager,
                    spec = spec,
                    controller = webViewController,
                    onNavigationStateChange = { navigationState = it },
                    loadingContent = loadingContent,
                )
            } else {
                loadingContent()
            }
        }
    }
}

@Composable
private fun StandaloneSettingsWebViewBody(
    pager: Pager,
    spec: StandaloneSettingsWebViewSpec,
    controller: SlockWebViewController,
    onNavigationStateChange: (SlockWebViewNavigationState) -> Unit,
    loadingContent: @Composable () -> Unit,
) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val sharedStores = remember(bridge) { build.raft.app.data.SlockSharedStoreRegistry.get(bridge) }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val authStore =
        remember(sharedStores, environment.storeScope) {
            sharedStores.authStore(environment.storeScope)
        }
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(authStore, sharedStores.authStore)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-${spec.surface.diagnosticsName}") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    LaunchedEffect(spec.surface, spec.destination) {
        bridge.log("settings_${spec.surface.diagnosticsName}_open")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenDestination(spec.destination))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    StandaloneSettingsWebViewContent(
        authSnapshot = authSnapshot,
        webBaseUrl = environment.webBaseUrl,
        spec = spec,
        controller = controller,
        accessTokenSyncLogger = bridge::log,
        onUrlChange = { url ->
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_url_changed url=$url")
        },
        onLoadFailure = { reason ->
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_load_failure reason=$reason")
        },
        onNavigationStateChange = onNavigationStateChange,
        loadingContent = loadingContent,
    )
}

internal fun standaloneSettingsShouldConsumeWebHistoryBack(
    consumeWebHistoryOnBack: Boolean,
    canGoBack: Boolean,
): Boolean = consumeWebHistoryOnBack && canGoBack

@Composable
internal fun StandaloneSettingsWebViewContent(
    authSnapshot: SettingsWebViewAuthSnapshot,
    webBaseUrl: String?,
    spec: StandaloneSettingsWebViewSpec,
    modifier: Modifier = Modifier,
    controller: SlockWebViewController = rememberSlockWebViewController(),
    onUrlChange: (String) -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationStateChange: (SlockWebViewNavigationState) -> Unit = {},
    accessTokenSyncLogger: (String) -> Unit = {},
    loadingContent: @Composable () -> Unit,
) {
    val session = authSnapshot.session
    val localeTag = rememberStandaloneSettingsLocaleTag(spec.includeLocaleQuery)
    val webUrl =
        remember(spec, session, webBaseUrl, localeTag) {
            spec.buildWebUrl(webBaseUrl, session, localeTag)
        }
    val hostAuthBinding = rememberSettingsWebViewHostAuthBinding(authSnapshot, controller)
    val injectedJavaScript =
        remember(spec.surface, session, hostAuthBinding) {
            standaloneSettingsWebViewInjectedJavaScript(session, hostAuthBinding, spec.surface)
        }
    var contentVisible by remember(webUrl, hostAuthBinding?.webViewGeneration) { mutableStateOf(false) }
    var navigationState by
        remember(webUrl, hostAuthBinding?.webViewGeneration) {
            mutableStateOf(SlockWebViewNavigationState())
        }
    val documentReady = contentVisible && !navigationState.isLoading && navigationState.progress >= 100

    if (session == null) {
        loadingContent()
        return
    }

    Box(modifier = modifier.fillMaxSize().background(spec.backgroundColor)) {
        key(hostAuthBinding?.webViewGeneration) {
            SlockWebView(
                url = webUrl,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(spec.backgroundColor)
                        .padding(bottom = spec.bottomContentPadding)
                        .alpha(if (!spec.coverUntilContentCommit || contentVisible) 1f else 0f),
                allowNavigation = true,
                allowedNavigationSchemes = spec.allowedNavigationSchemes,
                domStorageEnabled = true,
                cacheEnabled = true,
                ephemeral = false,
                injectedJavaScript = injectedJavaScript,
                controller = controller,
                onLoadSuccess = { contentVisible = true },
                onUrlChange = onUrlChange,
                onLoadFailure = onLoadFailure,
                onNavigationStateChange = { state ->
                    navigationState = state
                    onNavigationStateChange(state)
                },
            )
        }
        if (spec.coverUntilContentCommit && !contentVisible) loadingContent()
    }

    SettingsWebViewAccessTokenSyncEffect(
        snapshot = authSnapshot,
        documentReady = documentReady,
        controller = controller,
        binding = hostAuthBinding,
        surface = spec.surface,
        logger = accessTokenSyncLogger,
    )
}

@Composable
private fun rememberStandaloneSettingsLocaleTag(enabled: Boolean): String =
    remember(enabled) {
        if (!enabled) {
            AppLocale.Default.languageTag
        } else {
            try {
                KeyValueLocalePreferenceStore(slockSharedPreferenceStore()).readLocaleSync().languageTag
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                AppLocale.Default.languageTag
            }
        }
    }

internal fun standaloneSettingsServerSlug(session: StoredAuthSession?): String? =
    session?.selectedServerSlug
        ?: session?.servers?.firstOrNull { it.id == session.currentServerId }?.slug
        ?: session?.servers?.firstOrNull()?.slug

internal fun standaloneSettingsWebViewInjectedJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding?,
    surface: SettingsWebViewSurface,
): String {
    val writes = settingsWebViewAuthStorageJavaScript(session, hostBinding)
    return """
        (function() {
          try {
            $writes;
          } catch (error) {
            console.error("slock_mobile_${surface.diagnosticsName}_injected_js_error", error && (error.stack || error.message || error));
          }
        })();
    """.trimIndent()
}
