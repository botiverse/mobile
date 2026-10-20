package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import build.raft.app.base.Utils
import build.raft.app.base.BridgeModule
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Alignment
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kmm.network.export.NetworkUserAgent

const val SETTINGS_WEB_VIEW_PAGE = "compose_settings_webview"
internal const val SETTINGS_WEB_VIEW_SURFACE = "settingsWebViewSurface"
internal const val SETTINGS_WEB_VIEW_VERSION_CODE = "versionCode"

@Page(SETTINGS_WEB_VIEW_PAGE, supportInLocal = true)
internal class ComposeSettingsWebViewPage : AuthenticatedProductPager(AuthenticatedProductRoute.SettingsWebView) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { SettingsWebViewScreen() }
    }
}

internal fun settingsWebViewPageData(
    surface: SettingsWebViewSurface,
    storeScope: build.raft.app.data.RaftShellStoreScope,
    versionCode: String = "",
): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_WEB_VIEW_PAGE)
        put(SETTINGS_WEB_VIEW_SURFACE, surface.diagnosticsName)
        put(RAFT_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
        if (surface == SettingsWebViewSurface.ReleaseNotes) {
            releaseNotesSanitizeVersionCode(versionCode).takeIf { it.isNotBlank() }?.let {
                put(SETTINGS_WEB_VIEW_VERSION_CODE, it)
            }
        }
    }

internal fun settingsWebViewSurfaceFromPageData(params: JSONObject): SettingsWebViewSurface? {
    val surfaceName = params.optString(SETTINGS_WEB_VIEW_SURFACE).trim()
    return SettingsWebViewSurface.entries.firstOrNull { it.diagnosticsName == surfaceName }
}

internal fun Pager.openSettingsWebViewPage(
    surface: SettingsWebViewSurface,
    storeScope: build.raft.app.data.RaftShellStoreScope,
    versionCode: String = "",
) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_WEB_VIEW_PAGE,
        settingsWebViewPageData(surface, storeScope, versionCode),
    )
}

internal enum class SettingsWebViewBackAction {
    NavigateWebHistory,
    ClosePage,
}

internal fun settingsWebViewBackAction(canGoBack: Boolean): SettingsWebViewBackAction =
    if (canGoBack) SettingsWebViewBackAction.NavigateWebHistory else SettingsWebViewBackAction.ClosePage

internal fun performSettingsWebViewBack(
    canGoBack: Boolean,
    goBack: () -> Unit,
    closePage: () -> Unit,
): SettingsWebViewBackAction =
    settingsWebViewBackAction(canGoBack).also { action ->
        when (action) {
            SettingsWebViewBackAction.NavigateWebHistory -> goBack()
            SettingsWebViewBackAction.ClosePage -> closePage()
        }
    }

internal enum class SettingsWebViewPresentation {
    LoadingCover,
    Error,
    Content,
}

internal fun settingsWebViewPresentation(
    contentVisible: Boolean,
    hasLoadError: Boolean,
): SettingsWebViewPresentation =
    when {
        hasLoadError -> SettingsWebViewPresentation.Error
        contentVisible -> SettingsWebViewPresentation.Content
        else -> SettingsWebViewPresentation.LoadingCover
    }

@Composable
private fun SettingsWebViewScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val surface =
        remember(pager.pageData.params) {
            settingsWebViewSurfaceFromPageData(pager.pageData.params)
        }
    when (surface) {
        SettingsWebViewSurface.Onboarding -> SettingsWebViewInvalidRoute(pager)
        // Computers is a native page now; keep old settings-webview route payloads
        // (list, or one machine via settingsComputerId) compatible by rendering it here.
        SettingsWebViewSurface.Computers -> RaftSettingsComputersRoute()
        SettingsWebViewSurface.Billing ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(MR.strings.settings_billing_title),
                iconName = RaftLucideIconName.CreditCard,
                spec = settingsBillingWebViewSpec,
            )
        SettingsWebViewSurface.Marketplace ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(MR.strings.settings_connected_apps_title),
                iconName = RaftLucideIconName.Link2,
                spec = settingsMarketplaceWebViewSpec,
            )
        SettingsWebViewSurface.Feedback ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(MR.strings.settings_feedback_title),
                iconName = RaftLucideIconName.MessageSquarePlus,
                spec = settingsFeedbackWebViewSpec,
                onHostMessage = { kind, _ ->
                    when (kind) {
                        "close" -> pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                        "auth-required" -> Unit // keep the page and draft; auth/session reconciliation supplies the next token
                        "integration-unavailable" -> {
                            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                            pager.openSettingsHandsFeedbackPage(
                                pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
                            )
                        }
                    }
                },
            )
        SettingsWebViewSurface.Administration ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(MR.strings.settings_server_administration_title),
                iconName = RaftLucideIconName.Shield,
                spec = settingsAdministrationWebViewSpec,
            )
        // Keep old settings-webview route payloads compatible while rendering
        // the Hands-hosted Release Notes WebView.
        SettingsWebViewSurface.ReleaseNotes -> ReleaseNotesScreen()
        null -> SettingsWebViewInvalidRoute(pager)
    }
}

@Composable
private fun SettingsWebViewInvalidRoute(pager: Pager) {
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceCream)) {
        RaftTopPageHeader(
            title = stringResource(MR.strings.settings_title),
            iconName = RaftLucideIconName.Settings,
            backgroundColor = RaftColor.SurfaceWhite,
            leadingContent = {
                RaftBrutalIconButton(
                    iconName = RaftLucideIconName.ArrowLeft,
                    onClick = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
                    size = 30.dp,
                    shadowOffset = 2.dp,
                )
            },
        )
        SettingsWebViewErrorContent(
            actionLabel = stringResource(MR.strings.common_close),
            actionIcon = RaftLucideIconName.X,
            onAction = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
        )
    }
}

/**
 * Product-specific parameters for a standalone Settings Web surface. Route/pageData continues to
 * carry only shell identity; canonical environment and auth bootstrap are resolved by the shared
 * host from the shell stores, while product state remains owned by the embedded Web app. There is
 * deliberately no [build.raft.app.data.SharedSettingsDestination] here: a Web surface must not
 * start a second native Settings store load for content that the SPA is already fetching.
 */
internal data class StandaloneSettingsWebViewSpec(
    val surface: SettingsWebViewSurface,
    val includeLocaleQuery: Boolean = false,
    val headerFollowsDocumentTitle: Boolean = false,
    /**
     * When false, the native host admits only the original document URL (same
     * scheme/host/port/path/query). Feedback keeps auth-bearing scripts fenced
     * to that trusted document; in-page history remains available.
     */
    val allowNavigation: Boolean = true,
    val allowedNavigationSchemes: Set<String> = setOf("http", "https"),
    val bottomContentPadding: Dp = 0.dp,
    val hostEventsEnabled: Boolean = false,
    val buildWebUrl: (webBaseUrl: String?, session: StoredAuthSession?, localeTag: String) -> String,
)

/**
 * Shared native page shell for every Settings Web surface, including Release Notes.
 *
 * The header is committed on the first frame and the heavier store/WebView body is attached on the
 * next frame. This keeps the native header stable while the native WebView is created.
 */
@Composable
internal fun StandaloneSettingsWebViewPage(
    pager: Pager,
    title: String,
    iconName: RaftLucideIconName,
    spec: StandaloneSettingsWebViewSpec,
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> },
) {
    SettingsWebViewPageScaffold(
        pager = pager,
        surface = spec.surface,
        title = title,
        iconName = iconName,
        headerFollowsDocumentTitle = spec.headerFollowsDocumentTitle,
    ) { controller, onNavigationStateChange ->
        StandaloneSettingsWebViewBody(
            pager = pager,
            spec = spec,
            controller = controller,
            onNavigationStateChange = onNavigationStateChange,
            onHostMessage = onHostMessage,
        )
    }
}

@Composable
internal fun SettingsWebViewPageScaffold(
    pager: Pager,
    surface: SettingsWebViewSurface,
    title: String,
    iconName: RaftLucideIconName,
    headerFollowsDocumentTitle: Boolean = false,
    content: @Composable (
        controller: RaftWebViewController,
        onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit,
    ) -> Unit,
) {
    var bodyReady by remember { mutableStateOf(false) }
    val webViewController = rememberRaftWebViewController()
    var navigationState by remember { mutableStateOf(RaftWebViewNavigationState()) }
    var headerTitleState by remember(surface) { mutableStateOf(SettingsWebViewHeaderTitleState()) }
    LaunchedEffect(Unit) {
        // LaunchedEffect itself does not establish a rendered-frame boundary. Without awaiting a
        // frame, the heavy environment/WebView body can join the page's initial render transaction
        // and briefly obscure the header while Android attaches the native view (task #88).
        repeat(SETTINGS_WEB_VIEW_HEADER_SETTLE_FRAMES) { withFrameNanos { } }
        bodyReady = true
    }

    fun closePage() {
        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    fun goBackOrClose() {
        performSettingsWebViewBack(
            canGoBack = navigationState.canGoBack,
            goBack = webViewController::goBack,
            closePage = ::closePage,
        )
    }

    RaftBackHandler(
        enabled = true,
        label = "settings_webview_${surface.diagnosticsName}",
        blocksBackSwipe = false,
        onBack = ::goBackOrClose,
    )

    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceCream)) {
        // This wrapper, rather than RaftTopPageHeader's inner content Box, is the sibling of the
        // WebView body. The layer must live here so it can order those two layout siblings.
        Box(modifier = Modifier.fillMaxWidth().zIndex(SETTINGS_WEB_VIEW_HEADER_Z_INDEX)) {
            RaftTopPageHeader(
                title =
                    if (headerFollowsDocumentTitle) {
                        headerTitleState.displayTitle(title)
                    } else {
                        title
                    },
                iconName = iconName,
                backgroundColor = RaftColor.SurfaceWhite,
                leadingContent = {
                    RaftBrutalIconButton(
                        iconName = RaftLucideIconName.ArrowLeft,
                        onClick = ::goBackOrClose,
                        size = 30.dp,
                        shadowOffset = 2.dp,
                    )
                },
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .zIndex(SETTINGS_WEB_VIEW_BODY_Z_INDEX)
                    .background(RaftColor.SurfaceCream),
        ) {
            if (bodyReady) {
                content(webViewController) { update ->
                    val nextState = update.state
                    navigationState = nextState
                    if (headerFollowsDocumentTitle) {
                        headerTitleState =
                            headerTitleState.accept(
                                owner = update.owner,
                                candidate = nextState.title,
                                navigationIsLoading = nextState.isLoading,
                            )
                    }
                }
            } else {
                SettingsWebViewLoadingCover()
            }
        }
    }
}

/**
 * Commits only a nonblank title from a completed navigation and keeps the last committed value
 * while a subsequent navigation is loading or temporarily reports an empty title. This prevents
 * initial HTML metadata and SPA/full-document transitions from flashing through the native header.
 */
internal data class SettingsWebViewHeaderTitleState(
    val documentTitle: String? = null,
    val pendingTitle: String? = null,
    val activeOwner: Long? = null,
) {
    fun accept(
        candidate: String,
        navigationIsLoading: Boolean = false,
        owner: Long = 0L,
    ): SettingsWebViewHeaderTitleState {
        val ownedState =
            if (activeOwner == owner) this else copy(pendingTitle = null, activeOwner = owner)
        val normalized = candidate.trim().takeIf { it.isNotEmpty() }
        if (navigationIsLoading) {
            return normalized?.let { ownedState.copy(pendingTitle = it) } ?: ownedState
        }
        val committedTitle = normalized ?: ownedState.pendingTitle
        return ownedState.copy(
            documentTitle = committedTitle ?: ownedState.documentTitle,
            pendingTitle = null,
        )
    }

    fun displayTitle(fallbackTitle: String): String =
        documentTitle ?: fallbackTitle.trim()
}

internal data class SettingsWebViewNavigationUpdate(
    val owner: Long,
    val state: RaftWebViewNavigationState,
)

/** Invalidates callbacks retained by a detached native WebView generation. */
internal class SettingsWebViewNavigationAuthority {
    private var activeOwner: Long = 0L

    fun acquire(): Long {
        activeOwner = if (activeOwner == Long.MAX_VALUE) 1L else activeOwner + 1L
        return activeOwner
    }

    fun accepts(owner: Long): Boolean = owner == activeOwner

    fun release(owner: Long) {
        if (accepts(owner)) acquire()
    }
}

@Composable
private fun StandaloneSettingsWebViewBody(
    pager: Pager,
    spec: StandaloneSettingsWebViewSpec,
    controller: RaftWebViewController,
    onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit,
    onHostMessage: (String, JSONObject) -> Unit,
) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val sharedStores = remember(bridge) { build.raft.app.data.RaftSharedStoreRegistry.get(bridge) }
    // The Web settings surfaces own their product data in the embedded SPA. Keep this host path
    // limited to shell identity, canonical environment, and auth bootstrap; constructing a full
    // native SharedSettingsDataSource here would only prepare repositories that this page cannot
    // render or mutate.
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val authStore =
        remember(sharedStores, storeScope) {
            sharedStores.authStore(storeScope)
        }
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(authStore, sharedStores.authStore)
    val webBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
    LaunchedEffect(spec.surface, spec) {
        bridge.log("settings_${spec.surface.diagnosticsName}_open")
    }
    StandaloneSettingsWebViewContent(
        authSnapshot = authSnapshot,
        webBaseUrl = webBaseUrl,
        apiOrigin = sharedStores.apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/').removeSuffix("/api"),
        spec = spec,
        controller = controller,
        bridge = bridge,
        accessTokenSyncLogger = bridge::log,
        onUrlChange = { url ->
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_url_changed url=$url")
        },
        onLoadCommit = {
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_load_commit")
        },
        onLoadFailure = { reason ->
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_load_failure reason=$reason")
        },
        onNavigationStateChange = onNavigationStateChange,
        onHostMessage = onHostMessage,
    )
}

@Composable
internal fun StandaloneSettingsWebViewContent(
    authSnapshot: SettingsWebViewAuthSnapshot,
    webBaseUrl: String?,
    apiOrigin: String,
    spec: StandaloneSettingsWebViewSpec,
    modifier: Modifier = Modifier,
    controller: RaftWebViewController = rememberRaftWebViewController(),
    bridge: BridgeModule? = null,
    onLoadCommit: () -> Unit = {},
    onUrlChange: (String) -> Unit = {},
    onLoadFailure: (String) -> Unit = {},
    onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit = {},
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> },
    accessTokenSyncLogger: (String) -> Unit = {},
) {
    val session = authSnapshot.session
    val localeTag = rememberStandaloneSettingsLocaleTag(spec.includeLocaleQuery)
    val webUrl =
        remember(spec, session, webBaseUrl, localeTag) {
            spec.buildWebUrl(webBaseUrl, session, localeTag)
        }
    val hostAuthBinding = rememberSettingsWebViewHostAuthBinding(authSnapshot, controller)
    val feedbackDiagnosticsHost =
        remember(bridge, controller, spec.surface) {
            bridge
                ?.takeIf { spec.surface == SettingsWebViewSurface.Feedback }
                ?.let { FeedbackDiagnosticsWebViewHost(it, controller) }
        }
    DisposableEffect(feedbackDiagnosticsHost, hostAuthBinding) {
        feedbackDiagnosticsHost?.bind(hostAuthBinding)
        onDispose { feedbackDiagnosticsHost?.unbind(hostAuthBinding) }
    }
    val injectedJavaScript =
        remember(spec.surface, session, hostAuthBinding, apiOrigin, webUrl) {
            standaloneSettingsWebViewInjectedJavaScript(
                session,
                hostAuthBinding,
                spec.surface,
                apiOrigin,
                trustedDocumentUrl = webUrl,
            )
        }
    var documentReady by remember(webUrl, hostAuthBinding?.webViewGeneration) { mutableStateOf(false) }

    if (session == null) {
        SettingsWebViewLoadingCover()
        return
    }

    SettingsWebViewContent(
        url = webUrl,
        modifier = modifier,
        allowedNavigationSchemes = spec.allowedNavigationSchemes,
        allowNavigation = spec.allowNavigation,
        hostEventsEnabled = spec.hostEventsEnabled,
        bottomContentPadding = spec.bottomContentPadding,
        injectedJavaScript = injectedJavaScript,
        controller = controller,
        generationKey = hostAuthBinding?.webViewGeneration,
        onLoadCommit = onLoadCommit,
        onUrlChange = onUrlChange,
        onLoadFailure = onLoadFailure,
        onNavigationStateChange = onNavigationStateChange,
        onHostMessage = { kind, payload ->
            val handled = feedbackDiagnosticsHost?.handle(kind, payload, hostAuthBinding) == true
            if (kind == "close" || kind == "integration-unavailable") feedbackDiagnosticsHost?.invalidate()
            if (!handled) onHostMessage(kind, payload)
        },
        onWebViewInvalidated = { feedbackDiagnosticsHost?.invalidate() },
        onDocumentReadyChange = { documentReady = it },
    )

    SettingsWebViewAccessTokenSyncEffect(
        snapshot = authSnapshot,
        documentReady = documentReady,
        controller = controller,
        binding = hostAuthBinding,
        surface = spec.surface,
        trustedDocumentUrl = webUrl,
        logger = accessTokenSyncLogger,
    )
}

/**
 * The only production WebView renderer used by standalone Settings surfaces.
 *
 * It owns the measured native-view attach gate, opaque cream cover, commit-ready transition,
 * failure/retry state, and navigation callbacks. Surface pages may provide identity/configuration,
 * but cannot change this first-frame sequence.
 */
@Composable
internal fun SettingsWebViewContent(
    url: String,
    modifier: Modifier = Modifier,
    hostEventsEnabled: Boolean = false,
    allowedNavigationSchemes: Set<String> = setOf("http", "https"),
    allowNavigation: Boolean = true,
    bottomContentPadding: Dp = 0.dp,
    injectedJavaScript: String = "",
    controller: RaftWebViewController = rememberRaftWebViewController(),
    generationKey: Any? = null,
    onLoadCommit: () -> Unit = {},
    onLoadSuccess: () -> Unit = {},
    onUrlChange: (String) -> Unit = {},
    filterStaleUrlCallbacks: Boolean = false,
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {},
    onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit = {},
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> },
    onWebViewInvalidated: () -> Unit = {},
    onDocumentReadyChange: (Boolean) -> Unit = {},
) {
    var reloadKey by remember(url, generationKey) { mutableStateOf(0) }
    var contentVisible by remember(url, generationKey, reloadKey) { mutableStateOf(false) }
    var loadError by remember(url, generationKey, reloadKey) { mutableStateOf<String?>(null) }
    var navigationState by
        remember(url, generationKey, reloadKey) {
            mutableStateOf(RaftWebViewNavigationState(url = url, isLoading = true))
        }
    var bodyFrameReady by remember(url, generationKey, reloadKey) { mutableStateOf(false) }
    var webViewAttachReady by remember(url, generationKey, reloadKey) { mutableStateOf(false) }
    val navigationAuthority = remember { SettingsWebViewNavigationAuthority() }
    val documentReady = contentVisible && !navigationState.isLoading && navigationState.progress >= 100

    LaunchedEffect(documentReady) { onDocumentReadyChange(documentReady) }
    LaunchedEffect(bodyFrameReady, url, generationKey, reloadKey) {
        if (!bodyFrameReady) {
            webViewAttachReady = false
            return@LaunchedEffect
        }
        repeat(SETTINGS_WEB_VIEW_ATTACH_DELAY_FRAMES) { withFrameNanos { } }
        webViewAttachReady = true
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceCream)
                .onGloballyPositioned { coordinates ->
                    bodyFrameReady =
                        settingsWebViewFrameIsReady(
                            positionY = coordinates.positionInRoot().y,
                            width = coordinates.size.width,
                            height = coordinates.size.height,
                        )
                },
    ) {
        // Native WebViews apply their own bottom safe-area inset. Extend the shared frame so that
        // inset falls outside the visible slot instead of exposing a second background strip.
        val webViewHeight = maxHeight + raftBottomSafeAreaPadding()
        if (webViewAttachReady) {
            key(generationKey, reloadKey, url) {
                val navigationOwner = remember { navigationAuthority.acquire() }
                DisposableEffect(navigationOwner) {
                    onDispose {
                        navigationAuthority.release(navigationOwner)
                        onWebViewInvalidated()
                    }
                }
                RaftWebView(
                    url = url,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(webViewHeight)
                            .background(RaftColor.SurfaceCream)
                            .padding(bottom = bottomContentPadding)
                            .alpha(if (contentVisible) 1f else 0f),
                    allowNavigation = allowNavigation,
                    allowedNavigationSchemes = allowedNavigationSchemes,
                    domStorageEnabled = true,
                    cacheEnabled = true,
                    ephemeral = false,
                    injectedJavaScript = injectedJavaScript,
                    hostEventsEnabled = hostEventsEnabled,
                    controller = controller,
                    onLoadCommit = {
                        loadError = null
                        contentVisible = true
                        onLoadCommit()
                    },
                    onLoadSuccess = {
                        loadError = null
                        contentVisible = true
                        onLoadSuccess()
                    },
                    onUrlChange = { changedUrl ->
                        if (!filterStaleUrlCallbacks || navigationAuthority.accepts(navigationOwner)) onUrlChange(changedUrl)
                    },
                    onLoadFailure = { reason ->
                        if (navigationAuthority.accepts(navigationOwner)) {
                            contentVisible = false
                            loadError = reason
                            onWebViewInvalidated()
                            onLoadFailure(reason)
                        }
                    },
                    onNavigationBlocked = onNavigationBlocked,
                    onHostMessage = { kind, payload ->
                        if (navigationAuthority.accepts(navigationOwner)) onHostMessage(kind, payload)
                    },
                    onNavigationStateChange = { state ->
                        if (navigationAuthority.accepts(navigationOwner)) {
                            if (state.isLoading && !navigationState.isLoading) onWebViewInvalidated()
                            navigationState = state
                            onNavigationStateChange(
                                SettingsWebViewNavigationUpdate(
                                    owner = navigationOwner,
                                    state = state,
                                ),
                            )
                        }
                    },
                )
            }
        }
        when (settingsWebViewPresentation(contentVisible, loadError != null)) {
            SettingsWebViewPresentation.Error ->
                SettingsWebViewErrorContent(
                    actionLabel = stringResource(MR.strings.common_retry),
                    actionIcon = RaftLucideIconName.RefreshCw,
                    onAction = { reloadKey += 1 },
                )
            SettingsWebViewPresentation.LoadingCover -> SettingsWebViewLoadingCover()
            SettingsWebViewPresentation.Content -> Unit
        }
    }
}

@Composable
internal fun SettingsWebViewLoadingCover() {
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceCream))
}

@Composable
internal fun SettingsWebViewErrorContent(
    actionLabel: String,
    actionIcon: RaftLucideIconName,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceCream).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .background(RaftColor.BrandYellow)
                    .border(RaftAppStroke.Default, RaftColor.StickerInk),
            contentAlignment = Alignment.Center,
        ) {
            RaftLucideIcon(RaftLucideIconName.TriangleAlert, modifier = Modifier.size(26.dp), color = raftArgbHex(RaftColor.StickerInk))
        }
        Text(
            stringResource(MR.strings.settings_webview_error_title),
            style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
        )
        Text(
            stringResource(MR.strings.settings_webview_error_body),
            style = RaftType.Body.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
        )
        RaftBrutalActionButton(
            label = actionLabel,
            onClick = onAction,
            leadingIcon = actionIcon,
            backgroundColor = RaftColor.SurfaceWhite,
        )
    }
}

internal fun settingsWebViewFrameIsReady(
    positionY: Float,
    width: Int,
    height: Int,
): Boolean = positionY.isFinite() && positionY >= 0f && width > 0 && height > 0

/**
 * Owns the optional feedback diagnostics stream for one embedded WebView generation.
 *
 * The Web page supplies its binding on every command, but the host never trusts that value by
 * itself: it must exactly match the currently rendered authenticated WebView binding. Native
 * callbacks are fenced again before they are evaluated in the page, so a callback retained by a
 * replaced WebView cannot create a ticket in the replacement page.
 */
private class FeedbackDiagnosticsWebViewHost(
    private val bridge: BridgeModule,
    private val controller: RaftWebViewController,
) {
    private class ActiveRequest(
        val requestId: String,
        val binding: FeedbackDiagnosticsBinding,
        var removeStream: () -> Unit,
    )

    private var currentBinding: FeedbackDiagnosticsBinding? = null
    private var active: ActiveRequest? = null

    fun bind(binding: SettingsWebViewHostAuthBinding?) {
        val next = binding?.toDiagnosticsBinding()
        if (next == currentBinding) return
        invalidate()
        currentBinding = next
    }

    fun unbind(binding: SettingsWebViewHostAuthBinding?) {
        val expected = binding?.toDiagnosticsBinding()
        if (expected == null || expected == currentBinding) {
            currentBinding = null
            invalidate()
        }
    }

    fun invalidate() {
        val request = active ?: return
        active = null
        request.removeStream()
        val removeCancellationCallback = bridge.settingsCapabilityStream(
            feedbackDiagnosticsCommand(
                action = "feedbackDiagnosticsCancel",
                requestId = request.requestId,
                binding = request.binding,
            ),
        ) { }
        removeCancellationCallback()
    }

    /** Returns true when the host-owned diagnostics message was consumed. */
    fun handle(
        kind: String,
        payload: JSONObject,
        binding: SettingsWebViewHostAuthBinding?,
    ): Boolean {
        if (kind != FeedbackDiagnosticsBridge.START_KIND && kind != FeedbackDiagnosticsBridge.CANCEL_KIND) {
            return false
        }
        val expected = binding?.toDiagnosticsBinding() ?: return true
        if (expected != currentBinding) return true
        val requestId = payload.optString(FeedbackDiagnosticsBridge.REQUEST_ID).trim()
        val requested = payload.optJSONObject(FeedbackDiagnosticsBridge.BINDING)?.toDiagnosticsBinding() ?: return true
        if (requested != expected || !FeedbackDiagnosticsBridge.validRequestId(requestId)) return true
        return if (kind == FeedbackDiagnosticsBridge.START_KIND) {
            start(requestId, expected)
            true
        } else {
            cancel(requestId, expected)
            true
        }
    }

    private fun start(requestId: String, binding: FeedbackDiagnosticsBinding) {
        if (active != null) return
        val request = ActiveRequest(requestId, binding) {}
        active = request
        val cancelStream =
            bridge.settingsCapabilityStream(
                feedbackDiagnosticsCommand(
                    action = "feedbackDiagnosticsStart",
                    requestId = requestId,
                    binding = binding,
                ),
            ) { event ->
                onNativeEvent(request, event)
            }
        if (active === request) {
            request.removeStream = cancelStream
        } else {
            // A synchronous terminal callback can race callback registration on a host. Do not
            // retain a callback after that request has already reached a terminal state.
            cancelStream()
        }
    }

    private fun cancel(requestId: String, binding: FeedbackDiagnosticsBinding) {
        val request = active ?: return
        if (request.requestId != requestId || request.binding != binding) return
        active = null
        request.removeStream()
        val removeCancellationCallback = bridge.settingsCapabilityStream(
            feedbackDiagnosticsCommand(
                action = "feedbackDiagnosticsCancel",
                requestId = requestId,
                binding = binding,
            ),
        ) { }
        removeCancellationCallback()
    }

    private fun onNativeEvent(request: ActiveRequest, event: JSONObject?) {
        if (active !== request || currentBinding != request.binding || event == null) return
        val eventRequestId = event.optString(FeedbackDiagnosticsBridge.REQUEST_ID).trim()
        val eventBinding = event.optJSONObject(FeedbackDiagnosticsBridge.BINDING)?.toDiagnosticsBinding()
        if (eventRequestId != request.requestId || eventBinding != request.binding) return
        val type = event.optString(FeedbackDiagnosticsBridge.TYPE).trim()
        if (type != "progress" && type != "done" && type != "failed") return
        val terminal = event.optBoolean("terminal", false)
        if (type == "progress" && terminal) return

        if (terminal) {
            active = null
            request.removeStream()
        }
        controller.evaluateJavaScript(feedbackWebViewDiagnosticsEventJavaScript(event))
    }

    private fun JSONObject.toDiagnosticsBinding(): FeedbackDiagnosticsBinding? {
        val accountId = optString("accountId").trim()
        val sessionGeneration = optLong("sessionGeneration", -1L)
        val webViewGeneration = optLong("webViewGeneration", -1L)
        return FeedbackDiagnosticsBinding(accountId, sessionGeneration, webViewGeneration)
            .takeIf { it.isValid() }
    }

    private fun SettingsWebViewHostAuthBinding.toDiagnosticsBinding(): FeedbackDiagnosticsBinding =
        FeedbackDiagnosticsBinding(
            accountId = accountId,
            sessionGeneration = sessionGeneration,
            webViewGeneration = webViewGeneration,
        )

    private fun feedbackDiagnosticsCommand(
        action: String,
        requestId: String,
        binding: FeedbackDiagnosticsBinding,
    ): JSONObject =
        JSONObject().apply {
            put("action", action)
            put(FeedbackDiagnosticsBridge.REQUEST_ID, requestId)
            put("accountId", binding.accountId)
            put("sessionGeneration", binding.sessionGeneration)
            put("webViewGeneration", binding.webViewGeneration)
        }
}

private fun feedbackWebViewDiagnosticsEventJavaScript(event: JSONObject): String {
    val encoded = Json.encodeToString(event.toString())
    val eventName = Json.encodeToString(FeedbackDiagnosticsBridge.EVENT)
    return """
        (function() {
          try {
            var detail = JSON.parse($encoded);
            window.dispatchEvent(new CustomEvent($eventName, {detail: detail}));
          } catch (_) {}
        })();
    """.trimIndent()
}

private const val SETTINGS_WEB_VIEW_HEADER_SETTLE_FRAMES = 1
internal const val SETTINGS_WEB_VIEW_HEADER_Z_INDEX = 1f
internal const val SETTINGS_WEB_VIEW_BODY_Z_INDEX = 0f
private const val SETTINGS_WEB_VIEW_ATTACH_DELAY_FRAMES = 2

@Composable
private fun rememberStandaloneSettingsLocaleTag(enabled: Boolean): String =
    remember(enabled) {
        if (!enabled) {
            AppLocale.Default.languageTag
        } else {
            try {
                KeyValueLocalePreferenceStore(raftSharedPreferenceStore()).readLocaleSync().languageTag
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
    apiOrigin: String = "",
    trustedDocumentUrl: String = "",
): String {
    if (surface == SettingsWebViewSurface.Feedback) {
        return feedbackWebViewBootstrapJavaScript(session, hostBinding, apiOrigin, trustedDocumentUrl)
    }
    val writes = settingsWebViewAuthStorageJavaScript(session, hostBinding)
    val clientKind = ""
    return """
        (function() {
          try {
            $writes;
            $clientKind
          } catch (error) {
            console.error("slock_mobile_${surface.diagnosticsName}_injected_js_error", error && (error.stack || error.message || error));
          }
        })();
    """.trimIndent()
}

internal fun feedbackWebViewBootstrapJavaScript(
    session: StoredAuthSession?,
    binding: SettingsWebViewHostAuthBinding?,
    apiOrigin: String,
    trustedDocumentUrl: String = "",
): String {
    val json = kotlinx.serialization.json.Json
    val accountId = json.encodeToString(binding?.accountId.orEmpty())
    val sessionGeneration = binding?.sessionGeneration ?: 0L
    val webViewGeneration = binding?.webViewGeneration ?: 0L
    val accessToken = json.encodeToString(session?.accessToken.orEmpty())
    val api = json.encodeToString(apiOrigin)
    val clientVersion = json.encodeToString(feedbackWebViewClientVersion())
    val clientKind = json.encodeToString(raftFeedbackWebViewClientKind())
    val trustedUrl = json.encodeToString(trustedDocumentUrl)
    return """
        (function() { 'use strict';
          if (window.top !== window) return;
          if ($trustedUrl) { try { var trusted = new URL($trustedUrl); var bootstrapPath = '/__raft_mobile_bootstrap__'; if (location.origin !== trusted.origin || (location.pathname !== trusted.pathname && location.pathname !== bootstrapPath) || (location.pathname !== bootstrapPath && location.search !== trusted.search)) return; } catch (_) { return; } }
          var port = window.raftHostPort;
          var binding = {accountId:$accountId, sessionGeneration:$sessionGeneration, webViewGeneration:$webViewGeneration};
          var session = {accountId:$accountId, sessionGeneration:$sessionGeneration, webViewGeneration:$webViewGeneration, accessToken:$accessToken, apiOrigin:$api, clientKind:$clientKind, clientVersion:$clientVersion, locale:((navigator.language || '').toLowerCase().indexOf('zh') === 0 ? 'zh-CN' : 'en'), theme:'elegant', mode:((typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light')};
          function post(kind, payload) {
            if (!port) return;
            try { port.postMessage(JSON.stringify({kind:kind, payload:payload || binding})); } catch (_) {}
          }
          function diagnosticsCommand(kind, request, nextBinding) {
            var requestId = request && typeof request === 'object' ? request.requestId : request;
            var requestedBinding = request && typeof request === 'object' && request.binding ? request.binding : (nextBinding || binding);
            post(kind, {requestId:requestId, binding:requestedBinding});
          }
          var feedback = window.RaftFeedback;
          if (!feedback || feedback.version !== 'raft-feedback-v1') {
            feedback = {version:'raft-feedback-v1', session:session, capabilities:['diagnostics-upload-v1'], diagnostics:{start:function(request, nextBinding){ diagnosticsCommand('diagnostics:start', request, nextBinding); }, cancel:function(request, nextBinding){ diagnosticsCommand('diagnostics:cancel', request, nextBinding); }}, emit:function(event, nextBinding){ post(event, nextBinding || binding); }};
            try { Object.defineProperty(window, 'RaftFeedback', {value:feedback, writable:false, configurable:false}); } catch (_) { feedback = window.RaftFeedback; }
          }
          if (feedback && feedback.session) {
            try {
              feedback.session.accountId = session.accountId;
              feedback.session.sessionGeneration = session.sessionGeneration;
              feedback.session.webViewGeneration = session.webViewGeneration;
              feedback.session.accessToken = session.accessToken;
              feedback.session.apiOrigin = session.apiOrigin;
              feedback.session.clientKind = session.clientKind;
              feedback.session.clientVersion = session.clientVersion;
            } catch (_) {}
          }
          try { if (feedback && !feedback.capabilities) feedback.capabilities = ['diagnostics-upload-v1']; } catch (_) {}
          try { if (feedback && !feedback.diagnostics) feedback.diagnostics = {start:function(request, nextBinding){ diagnosticsCommand('diagnostics:start', request, nextBinding); }, cancel:function(request, nextBinding){ diagnosticsCommand('diagnostics:cancel', request, nextBinding); }}; } catch (_) {}
        })();
    """.trimIndent()
}

/** The host's installed app identity is the source of truth for Web feedback metadata. */
private fun feedbackWebViewClientVersion(): String =
    NetworkUserAgent.appIdentity?.version?.trim().orEmpty()

internal fun feedbackWebViewRuntimeSessionJavaScript(
    accessToken: String,
    accountId: String,
    sessionGeneration: Long,
    webViewGeneration: Long,
    trustedDocumentUrl: String = "",
): String {
    val json = kotlinx.serialization.json.Json
    val trustedUrl = json.encodeToString(trustedDocumentUrl)
    return """
        (function() { if (window.top !== window) return; if ($trustedUrl) { try { var trusted = new URL($trustedUrl); if (location.origin !== trusted.origin || location.pathname !== trusted.pathname || location.search !== trusted.search) return; } catch (_) { return; } } var host = window.RaftFeedback; if (!host || !host.session) return; host.session.accessToken = ${json.encodeToString(accessToken)}; host.session.accountId = ${json.encodeToString(accountId)}; host.session.sessionGeneration = $sessionGeneration; host.session.webViewGeneration = $webViewGeneration; window.dispatchEvent(new CustomEvent('raft:feedback-session-updated', {detail:host.session})); })();
    """.trimIndent()
}
