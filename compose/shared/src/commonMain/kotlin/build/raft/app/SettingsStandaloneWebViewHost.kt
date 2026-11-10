package build.raft.app

import build.raft.designsystem.theme.RaftTheme
import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import build.raft.app.base.Utils
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftProgressBar
import build.raft.designsystem.components.RaftProgressBarSize
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftStickerTile
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.raftSharedPreferenceStore
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
import androidx.compose.ui.platform.testTag
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
    /** A search result's setting: the web page opens at `#<id>` (Billing, Administration). */
    anchor: SettingsSearchAnchorRequest? = null,
): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_WEB_VIEW_PAGE)
        putSettingsSearchAnchor(anchor)
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
    anchor: SettingsSearchAnchorRequest? = null,
) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_WEB_VIEW_PAGE,
        settingsWebViewPageData(surface, storeScope, versionCode, anchor),
    )
}

internal enum class SettingsWebViewBackAction {
    NavigateWebHistory,
    ClosePage,
}

internal fun settingsWebViewBackAction(canGoBack: Boolean): SettingsWebViewBackAction =
    if (canGoBack) SettingsWebViewBackAction.NavigateWebHistory else SettingsWebViewBackAction.ClosePage

/**
 * Whether a Settings web page handles Back itself (header arrow and system Back): always as a
 * pushed page; in the wide detail pane only to step back through the web page's own history (the
 * list beside it, and the pane's Back to the default section, cover the rest).
 */
internal fun settingsWebViewHandlesBack(wideDetail: Boolean, canGoBack: Boolean): Boolean = !wideDetail || canGoBack

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

/**
 * What a Settings web page shows over its WebView. There is no cover: the WebView is shown as soon
 * as it is attached (it paints the page as it arrives, like the system browser), with a thin
 * progress bar on top while the document is loading. Only a failed, stalled or crashed load
 * replaces it, with the error state.
 */
internal enum class SettingsWebViewPresentation {
    /** The WebView, with the top progress bar. */
    Loading,
    Error,
    Content,
}

internal fun settingsWebViewPresentation(
    committed: Boolean,
    isLoading: Boolean,
    hasLoadError: Boolean,
): SettingsWebViewPresentation =
    when {
        hasLoadError -> SettingsWebViewPresentation.Error
        !committed || isLoading -> SettingsWebViewPresentation.Loading
        else -> SettingsWebViewPresentation.Content
    }

@Composable
private fun SettingsWebViewScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val surface =
        remember(pager.pageData.params) {
            settingsWebViewSurfaceFromPageData(pager.pageData.params)
        }
    val wideDetailClose = LocalRaftSettingsWideDetailClose.current
    // A Settings search result opens the web page at its setting (`#<id>`, web useScrollToSettingsSection).
    val searchAnchor = remember(pager.pageData.params) { settingsSearchAnchorFromPageData(pager.pageData.params) }
    fun closeSection() =
        settingsPageClose(wideDetailClose) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    when (surface) {
        SettingsWebViewSurface.Onboarding,
        // The Artifact viewer is its own page (RAFT_ARTIFACT_VIEWER_PAGE), never a Settings route.
        SettingsWebViewSurface.ArtifactViewer -> SettingsWebViewInvalidRoute(pager)
        // Computers is a native page now; keep old settings-webview route payloads
        // (list, or one machine via settingsComputerId) compatible by rendering it here.
        SettingsWebViewSurface.Computers -> RaftSettingsComputersRoute()
        SettingsWebViewSurface.Billing ->
            // iOS: never the in-app WebView (its page offers Stripe payment; artin 11-02).
            when (settingsBillingOpenTarget()) {
                SettingsBillingOpenTarget.ExternalBrowser -> SettingsBillingExternalPage(pager)
                SettingsBillingOpenTarget.InAppWebView ->
                    StandaloneSettingsWebViewPage(
                        pager = pager,
                        title = stringResource(Res.string.settings_billing_title),
                        iconName = RaftLucideIconName.CreditCard,
                        spec = remember(searchAnchor) { settingsBillingWebViewSpec.withSettingsSearchAnchor(searchAnchor) },
                    )
                SettingsBillingOpenTarget.ViewOnly -> SettingsBillingViewOnlyPage(pager)
            }
        SettingsWebViewSurface.Marketplace ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(Res.string.settings_connected_apps_title),
                iconName = RaftLucideIconName.Link2,
                spec = settingsMarketplaceWebViewSpec,
            )
        SettingsWebViewSurface.Administration ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(Res.string.settings_server_administration_title),
                iconName = RaftLucideIconName.Shield,
                spec = remember(searchAnchor) { settingsAdministrationWebViewSpec.withSettingsSearchAnchor(searchAnchor) },
            )
        SettingsWebViewSurface.ImBridges ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(Res.string.settings_im_bridges_title),
                iconName = RaftLucideIconName.Network,
                spec = settingsImBridgesWebViewSpec,
            )
        SettingsWebViewSurface.AccountDeletion -> SettingsAccountDeletionWebViewPage(pager)
        // Keep old settings-webview route payloads compatible while rendering
        // the Hands-hosted Release Notes WebView.
        SettingsWebViewSurface.ReleaseNotes -> ReleaseNotesScreen()
        null -> SettingsWebViewInvalidRoute(pager)
    }
}

@Composable
private fun SettingsWebViewInvalidRoute(pager: Pager) {
    Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        RaftTopPageHeader(
            title = stringResource(Res.string.settings_title),
            iconName = RaftLucideIconName.Settings,
            backgroundColor = RaftColor.SurfaceWhite,
            onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
        )
        SettingsWebViewErrorContent(
            actionLabel = stringResource(Res.string.common_close),
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
/** [this] spec, its page opened at [anchor]'s setting (`#<id>`); unchanged without one. */
internal fun StandaloneSettingsWebViewSpec.withSettingsSearchAnchor(anchor: SettingsSearchAnchorRequest?): StandaloneSettingsWebViewSpec {
    if (anchor == null) return this
    val build = buildWebUrl
    return copy(buildWebUrl = { webBaseUrl, session, localeTag -> settingsSearchWebUrlWithAnchor(build(webBaseUrl, session, localeTag), anchor) })
}

internal data class StandaloneSettingsWebViewSpec(
    val surface: SettingsWebViewSurface,
    val includeLocaleQuery: Boolean = false,
    val headerFollowsDocumentTitle: Boolean = false,
    /**
     * When false, the native host admits only the original document URL (same
     * scheme/host/port/path/query); in-page history remains available.
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

    // In the wide Settings detail pane the page is the pane's content: no Back of its own unless the
    // web page has history, closing returns to the default section (SettingsWideSplit.kt).
    val wideDetail = LocalRaftSettingsWideDetail.current
    val wideDetailClose = LocalRaftSettingsWideDetailClose.current

    fun closePage() {
        settingsPageClose(wideDetailClose) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    }

    fun goBackOrClose() {
        performSettingsWebViewBack(
            canGoBack = navigationState.canGoBack,
            goBack = webViewController::goBack,
            closePage = ::closePage,
        )
    }

    RaftBackHandler(
        enabled = settingsWebViewHandlesBack(wideDetail, navigationState.canGoBack),
        label = "settings_webview_${surface.diagnosticsName}",
        blocksBackSwipe = false,
        onBack = ::goBackOrClose,
    )

    Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
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
                applyTopSafeArea = !wideDetail,
                onBack = if (settingsWebViewHandlesBack(wideDetail, navigationState.canGoBack)) ::goBackOrClose else null,
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .zIndex(SETTINGS_WEB_VIEW_BODY_Z_INDEX)
                    .background(RaftTheme.colors.layerCanvas),
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
                SettingsWebViewLoadingPlaceholder()
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
        spec = spec,
        controller = controller,
        accessTokenSyncLogger = bridge::log,
        onUrlChange = { url ->
            // Never the query: a Settings URL can carry tokens.
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_url_changed url=${raftWebViewUrlForLog(url)}")
        },
        onLoadCommit = {
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_load_commit")
        },
        onLoadSuccess = {
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_load_success")
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
    spec: StandaloneSettingsWebViewSpec,
    modifier: Modifier = Modifier,
    controller: RaftWebViewController = rememberRaftWebViewController(),
    onLoadCommit: () -> Unit = {},
    onLoadSuccess: () -> Unit = {},
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
    val injectedJavaScript =
        remember(spec.surface, session, hostAuthBinding) {
            standaloneSettingsWebViewInjectedJavaScript(session, hostAuthBinding, spec.surface)
        }
    var documentReady by remember(webUrl, hostAuthBinding?.webViewGeneration) { mutableStateOf(false) }

    // The page's session goes in at document start (injectedJavaScript), so it cannot load before
    // the session is known (it would open signed out). The load starts as soon as it is.
    if (session == null) {
        SettingsWebViewLoadingPlaceholder()
        return
    }

    SettingsWebViewContent(
        url = webUrl,
        modifier = modifier,
        diagnosticsName = "settings_${spec.surface.diagnosticsName}",
        allowedNavigationSchemes = spec.allowedNavigationSchemes,
        allowNavigation = spec.allowNavigation,
        hostEventsEnabled = spec.hostEventsEnabled,
        bottomContentPadding = spec.bottomContentPadding,
        injectedJavaScript = injectedJavaScript,
        controller = controller,
        generationKey = hostAuthBinding?.webViewGeneration,
        onLoadCommit = onLoadCommit,
        onLoadSuccess = onLoadSuccess,
        onUrlChange = onUrlChange,
        onLoadFailure = onLoadFailure,
        onNavigationStateChange = onNavigationStateChange,
        onHostMessage = onHostMessage,
        onDocumentReadyChange = { documentReady = it },
    )

    SettingsWebViewAccessTokenSyncEffect(
        snapshot = authSnapshot,
        documentReady = documentReady,
        controller = controller,
        binding = hostAuthBinding,
        surface = spec.surface,
        logger = accessTokenSyncLogger,
    )
}

/**
 * The only production WebView renderer used by standalone Settings surfaces.
 *
 * It owns the measured native-view attach gate, the top progress bar, the commit-ready
 * transition, failure/retry state, and navigation callbacks. Surface pages may provide
 * identity/configuration, but cannot change this first-frame sequence.
 *
 * The WebView is shown as soon as it is attached (no cover until the page commits): a page paints
 * progressively, as in the system browser, under a progress bar while it loads.
 *
 * Every attempt is guarded by a [RaftWebViewLoadWatchdog] (dm #102): a page that is not ready
 * [stallTimeoutMs] after it started, or whose web content process died before it was, is replaced
 * by the error state; its WebView is dropped, and Retry builds a new one (new timer).
 */
@Composable
internal fun SettingsWebViewContent(
    url: String,
    modifier: Modifier = Modifier,
    /** Fixed page name for the load diagnostics (`settings_billing`, `release_notes`, ...). */
    diagnosticsName: String = "settings_webview",
    stallTimeoutMs: Long = RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS,
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
    /** The document committed (first content on its way); gates [documentReady], not visibility. */
    var committed by remember(url, generationKey, reloadKey) { mutableStateOf(false) }
    var loadError by remember(url, generationKey, reloadKey) { mutableStateOf<String?>(null) }
    var navigationState by
        remember(url, generationKey, reloadKey) {
            mutableStateOf(RaftWebViewNavigationState(url = url, isLoading = true))
        }
    // The WebView attaches once this frame has been measured at a real place: a native view
    // attached before layout would draw over the page header for a frame (task #227). That
    // measurement is already one rendered frame after the body entered; no further frame delay.
    var webViewAttachReady by remember(url, generationKey, reloadKey) { mutableStateOf(false) }
    val navigationAuthority = remember { SettingsWebViewNavigationAuthority() }
    val loadWatchdog =
        remember(url, generationKey, reloadKey) {
            RaftWebViewLoadWatchdog(page = diagnosticsName, url = url, retry = reloadKey, stallTimeoutMs = stallTimeoutMs)
        }
    val documentReady = committed && !navigationState.isLoading && navigationState.progress >= 100

    LaunchedEffect(documentReady) { onDocumentReadyChange(documentReady) }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(RaftTheme.colors.layerCanvas)
                .onGloballyPositioned { coordinates ->
                    // Once attached, the WebView stays (a later re-layout must not drop it).
                    if (!webViewAttachReady &&
                        settingsWebViewFrameIsReady(
                            positionY = coordinates.positionInRoot().y,
                            width = coordinates.size.width,
                            height = coordinates.size.height,
                        )
                    ) {
                        webViewAttachReady = true
                    }
                },
    ) {
        // Native WebViews apply their own bottom safe-area inset. Extend the shared frame so that
        // inset falls outside the visible slot instead of exposing a second background strip.
        val webViewHeight = maxHeight + raftBottomSafeAreaPadding()
        // A stalled or crashed attempt's WebView goes (its timer and probe with it): Retry builds a new one.
        if (webViewAttachReady && !loadWatchdog.showsError) {
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
                            .background(RaftTheme.colors.layerCanvas)
                            .padding(bottom = bottomContentPadding),
                    allowNavigation = allowNavigation,
                    allowedNavigationSchemes = allowedNavigationSchemes,
                    domStorageEnabled = true,
                    cacheEnabled = true,
                    ephemeral = false,
                    injectedJavaScript = injectedJavaScript,
                    hostEventsEnabled = hostEventsEnabled,
                    controller = controller,
                    loadWatchdog = loadWatchdog,
                    onLoadCommit = {
                        loadError = null
                        committed = true
                        onLoadCommit()
                    },
                    onLoadSuccess = {
                        loadError = null
                        committed = true
                        onLoadSuccess()
                    },
                    onUrlChange = { changedUrl ->
                        if (!filterStaleUrlCallbacks || navigationAuthority.accepts(navigationOwner)) onUrlChange(changedUrl)
                    },
                    onLoadFailure = { reason ->
                        if (navigationAuthority.accepts(navigationOwner)) {
                            committed = false
                            loadError = reason
                            onWebViewInvalidated()
                            onLoadFailure(reason)
                        }
                    },
                    onNavigationBlocked = onNavigationBlocked,
                    onHostMessage = { kind, payload ->
                        if (navigationAuthority.accepts(navigationOwner)) {
                            // The page's own bridge `ready` is a ready signal too.
                            if (kind == "ready") loadWatchdog.onPageReadyMessage()
                            onHostMessage(kind, payload)
                        }
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
        when (
            settingsWebViewPresentation(
                committed = committed,
                isLoading = navigationState.isLoading,
                hasLoadError = loadError != null || loadWatchdog.showsError,
            )
        ) {
            SettingsWebViewPresentation.Error ->
                SettingsWebViewErrorContent(
                    actionLabel = stringResource(Res.string.common_retry),
                    actionIcon = RaftLucideIconName.RefreshCw,
                    body = settingsWebViewErrorBody(loadWatchdog.state.phase),
                    onAction = { reloadKey += 1 },
                )
            SettingsWebViewPresentation.Loading -> RaftWebPageLoadingBar(navigationState.progress)
            SettingsWebViewPresentation.Content -> Unit
        }
    }
}

/** A plain canvas, for a non-web wait (server setup admission). Web pages use [SettingsWebViewLoadingPlaceholder]. */
@Composable
internal fun SettingsWebViewLoadingCover() {
    Box(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas))
}

/** A web page that cannot start its load yet (its session or URL is not known): canvas and the progress bar. */
@Composable
internal fun SettingsWebViewLoadingPlaceholder() {
    Box(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        RaftWebPageLoadingBar(progress = 0)
    }
}

/**
 * The thin progress bar on top of a loading in-app web page (raft-ui Progress, small): the
 * page's load progress, or the indeterminate sweep before the engine reports any. It stays short
 * of full so it never turns the "complete" colour; it goes away when the load ends.
 */
@Composable
internal fun RaftWebPageLoadingBar(progress: Int, modifier: Modifier = Modifier) {
    RaftProgressBar(
        value = raftWebPageLoadingBarValue(progress),
        modifier = modifier.fillMaxWidth().testTag(RAFT_WEB_PAGE_LOADING_BAR_TAG),
        size = RaftProgressBarSize.Sm,
        contentDescription = stringResource(Res.string.common_loading),
    )
}

/** The bar's fraction for an engine [progress] (0..100): null (indeterminate) until it moves. */
internal fun raftWebPageLoadingBarValue(progress: Int): Float? =
    progress.takeIf { it > 0 }?.let { (it.coerceAtMost(100) / 100f).coerceAtMost(RAFT_WEB_PAGE_LOADING_BAR_MAX) }

internal const val RAFT_WEB_PAGE_LOADING_BAR_TAG = "raft_web_page_loading_bar"
private const val RAFT_WEB_PAGE_LOADING_BAR_MAX = 0.95f

@Composable
internal fun SettingsWebViewErrorContent(
    actionLabel: String,
    actionIcon: RaftLucideIconName,
    onAction: () -> Unit,
    body: String = stringResource(Res.string.settings_webview_error_body),
) {
    Column(
        modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RaftStickerTile(fill = RaftColor.Brand, size = RaftIconTileSize.Lg) {
            RaftSlotIcon(RaftLucideIconName.TriangleAlert, size = RaftIconTileSize.Lg.icon)
        }
        Text(
            stringResource(Res.string.settings_webview_error_title),
            style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
        )
        Text(
            body,
            style = RaftType.Body.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
        )
        RaftButton(
            text = actionLabel,
            onClick = onAction,
            size = RaftButtonSize.Lg,
            leadingIcon = { RaftSlotIcon(actionIcon) },
        )
    }
}

/** The error state's line: a crashed page says so; a stalled or failed load keeps the connection hint. */
@Composable
internal fun settingsWebViewErrorBody(phase: RaftWebViewLoadPhase): String =
    if (phase == RaftWebViewLoadPhase.ProcessGone) {
        stringResource(Res.string.settings_webview_crash_body)
    } else {
        stringResource(Res.string.settings_webview_error_body)
    }

internal fun settingsWebViewFrameIsReady(
    positionY: Float,
    width: Int,
    height: Int,
): Boolean = positionY.isFinite() && positionY >= 0f && width > 0 && height > 0

private const val SETTINGS_WEB_VIEW_HEADER_SETTLE_FRAMES = 1
internal const val SETTINGS_WEB_VIEW_HEADER_Z_INDEX = 1f
internal const val SETTINGS_WEB_VIEW_BODY_Z_INDEX = 0f

@Composable
private fun rememberStandaloneSettingsLocaleTag(enabled: Boolean): String =
    remember(enabled) {
        if (!enabled) AppLocale.Default.languageTag else readStandaloneSettingsLocaleTag()
    }

/** The in-app language's tag for a Settings web page's `lang` query (the default when unreadable). */
internal fun readStandaloneSettingsLocaleTag(): String =
    try {
        KeyValueLocalePreferenceStore(raftSharedPreferenceStore()).readLocaleSync().languageTag
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        AppLocale.Default.languageTag
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
