package build.raft.app

import build.raft.designsystem.theme.RaftTheme
import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalUriHandler
import build.raft.app.base.Utils
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftPageLoadState
import build.raft.designsystem.components.RaftPageLoadingBar
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftStickerTile
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.raftSharedPreferenceStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import build.raft.app.nav.RAFT_REPLACE_CURRENT_PAGE

const val SETTINGS_WEB_VIEW_PAGE = "compose_settings_webview"
internal const val SETTINGS_WEB_VIEW_SURFACE = "settingsWebViewSurface"
internal const val SETTINGS_WEB_VIEW_VERSION_CODE = "versionCode"
internal const val SETTINGS_WEB_VIEW_INSTALLED_APP_ID = "installedAppId"

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
    /** An App bot's owning App row id (`?installed_app=<id>`, botiverse/slock #14286). */
    installedAppId: String? = null,
    replaceCurrentPage: Boolean = false,
): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_WEB_VIEW_PAGE)
        putSettingsSearchAnchor(anchor)
        put(SETTINGS_WEB_VIEW_SURFACE, surface.diagnosticsName)
        put(RAFT_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(RAFT_SHELL_SERVER_ID, it) }
        if (replaceCurrentPage) put(RAFT_REPLACE_CURRENT_PAGE, true)
        if (surface == SettingsWebViewSurface.ReleaseNotes) {
            releaseNotesSanitizeVersionCode(versionCode).takeIf { it.isNotBlank() }?.let {
                put(SETTINGS_WEB_VIEW_VERSION_CODE, it)
            }
        }
        if (surface == SettingsWebViewSurface.Marketplace) {
            installedAppId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                put(SETTINGS_WEB_VIEW_INSTALLED_APP_ID, it)
            }
        }
    }

internal fun settingsInstalledAppIdFromPageData(params: JSONObject): String? =
    params.optString(SETTINGS_WEB_VIEW_INSTALLED_APP_ID).trim().takeIf { it.isNotEmpty() }

internal fun settingsWebViewSurfaceFromPageData(params: JSONObject): SettingsWebViewSurface? {
    val surfaceName = params.optString(SETTINGS_WEB_VIEW_SURFACE).trim()
    return SettingsWebViewSurface.entries.firstOrNull { it.diagnosticsName == surfaceName }
}

internal fun Pager.openSettingsWebViewPage(
    surface: SettingsWebViewSurface,
    storeScope: build.raft.app.data.RaftShellStoreScope,
    versionCode: String = "",
    anchor: SettingsSearchAnchorRequest? = null,
    installedAppId: String? = null,
    replaceCurrentPage: Boolean = false,
) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_WEB_VIEW_PAGE,
        settingsWebViewPageData(surface, storeScope, versionCode, anchor, installedAppId, replaceCurrentPage = replaceCurrentPage),
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
 * load line on top while the document is loading. Only a failed, stalled or crashed load
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
        SettingsWebViewSurface.Marketplace -> {
            val installedAppId =
                remember(pager.pageData.params) { settingsInstalledAppIdFromPageData(pager.pageData.params) }
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(Res.string.settings_connected_apps_title),
                iconName = RaftLucideIconName.Link2,
                spec = remember(installedAppId) { settingsMarketplaceWebViewSpec(installedAppId) },
            )
        }
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
        SettingsWebViewSurface.WebPage -> SettingsOwnWebViewPage(pager)
        null -> SettingsWebViewInvalidRoute(pager)
    }
}

@Composable
internal fun SettingsWebViewInvalidRoute(pager: Pager) {
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
    val headerFollowsDocumentTitle: Boolean = false,
    /**
     * When false, the native host admits only the original document URL (same
     * scheme/host/port/path/query); in-page history remains available.
     */
    val allowNavigation: Boolean = true,
    val allowedNavigationSchemes: Set<String> = setOf("http", "https"),
    /**
     * When true, locks main-frame navigation to the origin of [buildWebUrl] (`mainFrameOrigin`),
     * routing off-origin navigations through `onNavigationBlocked` instead of loading third-party
     * sites inside the authenticated Settings WebView.
     */
    val lockToWebOrigin: Boolean = false,
    /** [RaftWebViewConfig.frameHosts] for embedded sub-frames (e.g. Artifact content fallback on Desktop). */
    val frameHosts: Set<String> = emptySet(),
    val bottomContentPadding: Dp = 0.dp,
    val hostEventsEnabled: Boolean = false,
    /** Uses non-persistent/incognito WebView storage (e.g. Desktop Wry or iOS own-web fallback). */
    val ephemeral: Boolean = raftPlatformUsesEphemeralAuthWebView(),
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
    controller: RaftWebViewController = rememberRaftWebViewController(),
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> },
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    nativeActions: List<RaftNativeNavBarAction> = emptyList(),
    onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit = {},
    onOpenExternalLink: ((String) -> Unit)? = null,
    onOpenBlockedNavigation: ((String) -> Unit)? = null,
    routesMainFrameNavigation: ((String) -> Boolean)? = null,
) {
    SettingsWebViewPageScaffold(
        pager = pager,
        surface = spec.surface,
        title = title,
        iconName = iconName,
        headerFollowsDocumentTitle = spec.headerFollowsDocumentTitle,
        webViewController = controller,
        trailingContent = trailingContent,
        nativeActions = nativeActions,
    ) { scaffoldController, onScaffoldNavigationStateChange ->
        StandaloneSettingsWebViewBody(
            pager = pager,
            spec = spec,
            controller = scaffoldController,
            onNavigationStateChange = { update ->
                onScaffoldNavigationStateChange(update)
                onNavigationStateChange(update)
            },
            onHostMessage = onHostMessage,
            onOpenExternalLink = onOpenExternalLink,
            onOpenBlockedNavigation = onOpenBlockedNavigation,
            routesMainFrameNavigation = routesMainFrameNavigation,
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
    webViewController: RaftWebViewController = rememberRaftWebViewController(),
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    nativeActions: List<RaftNativeNavBarAction> = emptyList(),
    content: @Composable (
        controller: RaftWebViewController,
        onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit,
    ) -> Unit,
) {
    var bodyReady by remember { mutableStateOf(false) }
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
                trailingContent = trailingContent,
                nativeActions = nativeActions,
            )
        }
        // One load line for the body: it stays composed while the placeholder gives way to the page.
        RaftWebPageLoadLineHost(
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
    onOpenExternalLink: ((String) -> Unit)? = null,
    onOpenBlockedNavigation: ((String) -> Unit)? = null,
    routesMainFrameNavigation: ((String) -> Boolean)? = null,
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
    // Subscribe to the shell-scoped auth store's own event stream when available (which forwards
    // all process-level auth events AND emits BindingMetadataChanged after every shell-local
    // server switch, even when the process store already selected the target server; #247).
    val authEvents =
        remember(authStore, sharedStores.authStore) {
            settingsWebViewAuthEventSource(authStore, sharedStores.authStore)
        }
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(authStore, authEvents)
    val webBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
    // #300: the page holds only the access token; it is refreshed ahead of its expiry while shown.
    val accessTokenRefresher =
        remember(sharedStores, storeScope, authStore) {
            SettingsWebViewAccessTokenRefresher(authStore, sharedStores.createAuthDataSourceForCurrentEnvironment(storeScope))
        }
    LaunchedEffect(spec.surface, spec) {
        bridge.log("settings_${spec.surface.diagnosticsName}_open")
    }
    StandaloneSettingsWebViewContent(
        authSnapshot = authSnapshot,
        webBaseUrl = webBaseUrl,
        spec = spec,
        controller = controller,
        accessTokenSyncLogger = bridge::log,
        accessTokenRefresher = accessTokenRefresher,
        accessTokenRefreshVisible = rememberRaftPagerAppeared(pager),
        onUrlChange = { url ->
            // Never the query; WebPage also drops path segments past the first (invite/share tokens).
            val loggedUrl =
                if (spec.surface == SettingsWebViewSurface.WebPage) raftOwnWebUrlForLog(url) else raftWebViewUrlForLog(url)
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_url_changed url=$loggedUrl")
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
        onOpenExternalLink = onOpenExternalLink,
        onOpenBlockedNavigation = onOpenBlockedNavigation,
        routesMainFrameNavigation = routesMainFrameNavigation,
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
    accessTokenRefresher: SettingsWebViewAccessTokenRefresher? = null,
    accessTokenRefreshVisible: Boolean = true,
    onOpenExternalLink: ((String) -> Unit)? = null,
    onOpenBlockedNavigation: ((String) -> Unit)? = null,
    routesMainFrameNavigation: ((String) -> Boolean)? = null,
) {
    val linkNavigator = LocalRaftLinkNavigator.current
    val uriHandler = LocalUriHandler.current
    val defaultOpenExternalLink: (String) -> Unit =
        remember(linkNavigator, uriHandler) {
            { targetUrl ->
                if (linkNavigator != null) linkNavigator.open(targetUrl) else uriHandler.openUri(targetUrl)
            }
        }
    val openExternalLink = onOpenExternalLink ?: defaultOpenExternalLink
    val currentOpenExternalLink by rememberUpdatedState(openExternalLink)
    val currentOpenBlockedNavigation by rememberUpdatedState(onOpenBlockedNavigation ?: openExternalLink)
    val currentLogger by rememberUpdatedState(accessTokenSyncLogger)
    val session = authSnapshot.session
    // Every Raft web page the App embeds opens in the App's UI language (#proj-workspace:#388):
    // `lang=` is added here, after the page's own builder, so no page can leave it out.
    val localeTag = remember { raftEmbeddedWebLangTag() }
    val webUrl =
        remember(spec, session, webBaseUrl, localeTag) {
            raftWebUrlWithLang(spec.buildWebUrl(webBaseUrl, session, localeTag), localeTag)
        }
    val mainFrameOrigin =
        remember(spec.lockToWebOrigin, webUrl) {
            if (spec.lockToWebOrigin) raftWebOriginOf(webUrl) else null
        }
    val hostAuthBinding = rememberSettingsWebViewHostAuthBinding(authSnapshot, controller)
    val hostOpenExternalDeduper =
        remember(webUrl, hostAuthBinding?.webViewGeneration) {
            SettingsWebViewHostOpenExternalDeduper()
        }
    val hostOpenExternalClock =
        remember(webUrl, hostAuthBinding?.webViewGeneration) {
            kotlin.time.TimeSource.Monotonic.markNow()
        }
    // Deferred out of synchronous onUrlChange (iOS decidePolicyForNavigationAction runs before
    // decisionHandler(Allow), so running replaceState synchronously would test the old hash).
    var pendingHostOpenExternal by
        remember(webUrl, hostAuthBinding?.webViewGeneration) {
            mutableStateOf<SettingsWebViewHostOpenExternalDecision?>(null)
        }
    val injectedJavaScript =
        remember(spec.surface, session, hostAuthBinding, webUrl) {
            standaloneSettingsWebViewInjectedJavaScript(session, hostAuthBinding, spec.surface, webOrigin = webUrl)
        }
    var documentReady by remember(webUrl, hostAuthBinding?.webViewGeneration) { mutableStateOf(false) }

    LaunchedEffect(pendingHostOpenExternal) {
        val decision = pendingHostOpenExternal ?: return@LaunchedEffect
        val clearScript = settingsWebViewHostOpenExternalTakenScript(webUrl, decision.previousHash)
        if (clearScript.isNotEmpty()) {
            controller.evaluateJavaScript(clearScript)
        }
        val target = decision.targetUrl
        if (target != null) {
            val loggedUrl =
                if (spec.surface == SettingsWebViewSurface.WebPage) raftOwnWebUrlForLog(target) else raftWebViewUrlForLog(target)
            currentLogger(
                "settings_${spec.surface.diagnosticsName}_host_open_external " +
                    "result=open url=$loggedUrl"
            )
            currentOpenExternalLink(target)
        } else {
            currentLogger(
                "settings_${spec.surface.diagnosticsName}_host_open_external " +
                    "result=dropped reason=not_http_url"
            )
        }
        pendingHostOpenExternal = null
    }

    fun handleHostOpenExternal(candidateUrl: String, retryClearWhenSettled: Boolean = false) {
        val decision =
            hostOpenExternalDeduper.observe(
                pageUrl = candidateUrl,
                webOrigin = webUrl,
                nowMs = hostOpenExternalClock.elapsedNow().inWholeMilliseconds,
            )
        if (decision != null) {
            pendingHostOpenExternal = decision
        } else if (
            retryClearWhenSettled &&
            pendingHostOpenExternal == null &&
            hostOpenExternalDeduper.awaitingHashClear &&
            settingsWebViewAsksHostOpenExternal(candidateUrl, webUrl)
        ) {
            // If an earlier replaceState was queued or overwritten while the document was still loading,
            // retry clearing the stuck hash once loading settles without re-opening the external link.
            val clearScript = settingsWebViewHostOpenExternalTakenScript(webUrl, hostOpenExternalDeduper.activePreviousHash)
            if (clearScript.isNotEmpty()) controller.evaluateJavaScript(clearScript)
        }
    }

    // The page's session goes in at document start (injectedJavaScript), so it cannot load before
    // the session is known (it would open signed out). The load starts as soon as it is. One load
    // line for both (the page scaffold's, when there is one): it stays up across the swap.
    RaftWebPageLoadLineHost(Modifier.fillMaxSize()) {
        if (session == null) {
            SettingsWebViewLoadingPlaceholder()
        } else {
            SettingsWebViewContent(
                url = webUrl,
                modifier = modifier,
                diagnosticsName = "settings_${spec.surface.diagnosticsName}",
                allowedNavigationSchemes = spec.allowedNavigationSchemes,
                mainFrameOrigin = mainFrameOrigin,
                frameHosts = spec.frameHosts,
                allowNavigation = spec.allowNavigation,
                hostEventsEnabled = spec.hostEventsEnabled,
                bottomContentPadding = spec.bottomContentPadding,
                ephemeral = spec.ephemeral,
                injectedJavaScript = injectedJavaScript,
                controller = controller,
                // Key the WebView on both auth binding generation and selected server id so a page
                // kept on screen across a server switch reloads cleanly even if its URL has no
                // server slug or hostAuthBinding was temporarily null (#247).
                generationKey = hostAuthBinding?.webViewGeneration to session.currentServerId,
                onLoadCommit = onLoadCommit,
                onLoadSuccess = onLoadSuccess,
                onUrlChange = { changedUrl ->
                    handleHostOpenExternal(changedUrl)
                    onUrlChange(changedUrl)
                },
                filterStaleUrlCallbacks = true,
                onLoadFailure = onLoadFailure,
                onNavigationBlocked = { blockedUrl ->
                    val target = settingsWebViewBlockedNavigationUrlOrNull(blockedUrl)
                    if (target != null) {
                        val loggedUrl =
                            if (spec.surface == SettingsWebViewSurface.WebPage) raftOwnWebUrlForLog(target) else raftWebViewUrlForLog(target)
                        currentLogger(
                            "settings_${spec.surface.diagnosticsName}_navigation_blocked " +
                                "action=open url=$loggedUrl"
                        )
                        currentOpenBlockedNavigation(target)
                    } else {
                        currentLogger(
                            "settings_${spec.surface.diagnosticsName}_navigation_blocked " +
                                "action=ignored scheme=${settingsWebViewSchemeForLog(blockedUrl)}"
                        )
                    }
                },
                onNavigationStateChange = { update ->
                    if (update.state.url.isNotBlank()) {
                        handleHostOpenExternal(
                            candidateUrl = update.state.url,
                            retryClearWhenSettled = !update.state.isLoading,
                        )
                    }
                    onNavigationStateChange(update)
                },
                onHostMessage = onHostMessage,
                onDocumentReadyChange = { documentReady = it },
                routesMainFrameNavigation = routesMainFrameNavigation,
            )
        }
    }
    if (session == null) return

    SettingsWebViewAccessTokenSyncEffect(
        snapshot = authSnapshot,
        documentReady = documentReady,
        controller = controller,
        binding = hostAuthBinding,
        surface = spec.surface,
        logger = accessTokenSyncLogger,
        refresher = accessTokenRefresher,
        visible = accessTokenRefreshVisible,
        webOrigin = webUrl,
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
    mainFrameOrigin: String? = null,
    frameHosts: Set<String> = emptySet(),
    allowNavigation: Boolean = true,
    bottomContentPadding: Dp = 0.dp,
    ephemeral: Boolean = raftPlatformUsesEphemeralAuthWebView(),
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
    routesMainFrameNavigation: ((String) -> Boolean)? = null,
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
    // attached before layout would draw over the page header for a frame (task #227). Once
    // measured, keep the attach gate open across URL / generation / retry swaps at identical
    // bounds so switching servers never drops the WebView waiting for a relayout (#247).
    var webViewAttachReady by remember { mutableStateOf(false) }
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
                    mainFrameOrigin = mainFrameOrigin,
                    frameHosts = frameHosts,
                    domStorageEnabled = true,
                    cacheEnabled = true,
                    ephemeral = ephemeral,
                    injectedJavaScript = injectedJavaScript,
                    hostEventsEnabled = hostEventsEnabled,
                    controller = controller,
                    loadWatchdog = loadWatchdog,
                    diagnosticsSurface =
                        if (diagnosticsName == SETTINGS_WEB_PAGE_DIAGNOSTICS_NAME) {
                            SETTINGS_WEB_PAGE_DIAGNOSTICS_NAME
                        } else {
                            "settings_web_view"
                        },
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
                    onNavigationBlocked = { blockedUrl ->
                        if (navigationAuthority.accepts(navigationOwner)) onNavigationBlocked(blockedUrl)
                    },
                    onHostMessage = { kind, payload ->
                        if (navigationAuthority.accepts(navigationOwner)) {
                            // The page's own bridge `ready` is a ready signal too.
                            if (kind == "ready") loadWatchdog.onPageReadyMessage()
                            onHostMessage(kind, payload)
                        }
                    },
                    routesMainFrameNavigation = routesMainFrameNavigation,
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
        val presentation =
            settingsWebViewPresentation(
                committed = committed,
                isLoading = navigationState.isLoading,
                hasLoadError = loadError != null || loadWatchdog.showsError,
            )
        if (presentation == SettingsWebViewPresentation.Error) {
            SettingsWebViewErrorContent(
                actionLabel = stringResource(Res.string.common_retry),
                actionIcon = RaftLucideIconName.RefreshCw,
                body = settingsWebViewErrorBody(loadWatchdog.state.phase),
                onAction = { reloadKey += 1 },
            )
        }
        // Always composed: the line plays its own end when the load finishes, and goes at once
        // when it fails. Drawn by the host around this page when there is one, else here.
        RaftWebPageLoadLine(
            raftWebPageLoadState(
                isLoading = presentation == SettingsWebViewPresentation.Loading,
                progress = navigationState.progress,
                failed = presentation == SettingsWebViewPresentation.Error,
                loadGeneration = navigationState.loadGeneration,
            ),
        )
    }
}

/** A plain canvas, for a non-web wait (server setup admission). Web pages use [SettingsWebViewLoadingPlaceholder]. */
@Composable
internal fun SettingsWebViewLoadingCover() {
    Box(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas))
}

/**
 * A web page that cannot start its load yet (its session or URL is not known): canvas, and the load
 * line resting with no progress. Inside a [RaftWebPageLoadLineHost] the line is the host's, so it
 * stays up when the page takes this placeholder's place.
 */
@Composable
internal fun SettingsWebViewLoadingPlaceholder() {
    Box(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        RaftWebPageLoadLine(RaftPageLoadState.Loading())
    }
}

/**
 * The one load line of a web page host: what the pieces under a [RaftWebPageLoadLineHost] report
 * with [RaftWebPageLoadLine]. The piece that entered last speaks (a page that took its
 * placeholder's place); with none, there is no load.
 */
@Stable
internal class RaftWebPageLoadLineHostState {
    private val reports = mutableStateListOf<RaftWebPageLoadLineReport>()

    val state: RaftPageLoadState
        get() = reports.lastOrNull()?.state ?: RaftPageLoadState.Idle

    fun add(report: RaftWebPageLoadLineReport) {
        if (report !in reports) reports += report
    }

    fun remove(report: RaftWebPageLoadLineReport) {
        reports -= report
    }
}

/** One piece's word on the load, under a [RaftWebPageLoadLineHost]. */
@Stable
internal class RaftWebPageLoadLineReport(state: RaftPageLoadState) {
    var state: RaftPageLoadState by mutableStateOf(state)
}

internal val LocalRaftWebPageLoadLineHost = compositionLocalOf<RaftWebPageLoadLineHostState?> { null }

/**
 * The container in which a web page's placeholder gives way to the page: a Box with one load line
 * over its [content], on its top edge. The line belongs to the container, not to the placeholder
 * or the page, so it stays composed while one replaces the other and goes on from where it was.
 * Inside another host this is a plain Box: the outer host keeps the line.
 */
@Composable
internal fun RaftWebPageLoadLineHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    if (LocalRaftWebPageLoadLineHost.current != null) {
        Box(modifier, content = content)
        return
    }
    val host = remember { RaftWebPageLoadLineHostState() }
    Box(modifier) {
        CompositionLocalProvider(LocalRaftWebPageLoadLineHost provides host) { content() }
        RaftWebPageLoadingBar(host.state)
    }
}

/**
 * Reports [state] as the load of the in-app web page this is part of. Under a
 * [RaftWebPageLoadLineHost] the host draws the line (and this takes no room); with no host the
 * line is drawn here, on the top edge of the parent.
 */
@Composable
internal fun RaftWebPageLoadLine(state: RaftPageLoadState) {
    val host = LocalRaftWebPageLoadLineHost.current
    if (host == null) {
        RaftWebPageLoadingBar(state)
        return
    }
    val report = remember(host) { RaftWebPageLoadLineReport(state) }
    SideEffect { if (report.state != state) report.state = state }
    DisposableEffect(host, report) {
        host.add(report)
        onDispose { host.remove(report) }
    }
}

/**
 * The load line on the top edge of an in-app web page ([RaftPageLoadingBar]: flat, square ends,
 * from the start edge). Keep it composed for as long as the page is and report the load with
 * [raftWebPageLoadState]: the line waits before it first shows, follows the page's progress, runs
 * to the end and fades out when the load finishes, and goes at once when it fails. A page whose
 * placeholder and content swap uses [RaftWebPageLoadLineHost] and [RaftWebPageLoadLine] instead.
 */
@Composable
internal fun RaftWebPageLoadingBar(state: RaftPageLoadState, modifier: Modifier = Modifier) {
    RaftPageLoadingBar(
        state = state,
        modifier = modifier.testTag(RAFT_WEB_PAGE_LOADING_BAR_TAG),
        contentDescription = stringResource(Res.string.common_loading),
    )
}

/**
 * What a web view's navigation state means for the load line. [progress] is the engine's 0..100,
 * [loadGeneration] the web view's count of main-frame loads started
 * ([RaftWebViewNavigationState.loadGeneration]).
 *
 * - [failed] (the page shows its error state): [RaftPageLoadState.Failed].
 * - [isLoading]: [RaftPageLoadState.Loading] with the progress (none while the engine reports 0)
 *   and [loadGeneration] as the load's name: when the web view starts another load, the line
 *   starts again. That is the host's own signal, the same on every platform; nothing is read
 *   into the progress going down.
 * - Not loading at 100: [RaftPageLoadState.Finished]. Android and iOS set 100 only when the
 *   load finished (`onPageFinished`, `didFinish`).
 * - Not loading short of 100: the load was stopped, or it failed and the failure callback has
 *   not arrived yet (the engines report "not loading" first). These cannot be told apart from
 *   the state, so the line goes at once ([RaftPageLoadState.Idle]) instead of running to the end.
 */
internal fun raftWebPageLoadState(
    isLoading: Boolean,
    progress: Int,
    failed: Boolean,
    loadGeneration: Int = 0,
): RaftPageLoadState =
    when {
        failed -> RaftPageLoadState.Failed
        isLoading ->
            RaftPageLoadState.Loading(
                progress = progress.takeIf { it > 0 }?.let { it.coerceAtMost(100) / 100f },
                load = loadGeneration,
            )
        progress >= 100 -> RaftPageLoadState.Finished
        else -> RaftPageLoadState.Idle
    }

internal const val RAFT_WEB_PAGE_LOADING_BAR_TAG = "raft_web_page_loading_bar"

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

/** The in-app language's tag for a Settings web page's `lang` query (the default when unreadable). */
internal fun readStandaloneSettingsLocaleTag(): String =
    try {
        KeyValueLocalePreferenceStore(raftSharedPreferenceStore()).readLocaleSync().languageTag
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        AppLocale.Default.languageTag
    }

internal fun settingsWebViewAuthEventSource(
    shellAuthStore: RaftAuthSessionStore,
    fallbackProcessAuthEvents: RaftAuthSessionEventSource,
): RaftAuthSessionEventSource = (shellAuthStore as? RaftAuthSessionEventSource) ?: fallbackProcessAuthEvents

internal fun standaloneSettingsServerSlug(session: StoredAuthSession?): String? =
    session?.selectedServerSlug
        ?: session?.servers?.firstOrNull { it.id == session.currentServerId }?.slug
        ?: session?.servers?.firstOrNull()?.slug

internal fun standaloneSettingsWebViewInjectedJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding?,
    surface: SettingsWebViewSurface,
    webOrigin: String,
): String {
    val writes = settingsWebViewAuthStorageJavaScript(session, hostBinding)
    return settingsWebViewGuardedAuthJavaScript(
        webOrigin = webOrigin,
        body = "$writes;",
        errorTag = "slock_mobile_${surface.diagnosticsName}_injected_js_error",
    )
}
