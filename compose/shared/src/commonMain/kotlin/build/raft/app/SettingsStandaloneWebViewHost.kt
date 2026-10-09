package build.raft.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueLocalePreferenceStore
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxWithConstraints
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.positionInRoot
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.zIndex
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

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
    storeScope: build.raft.app.data.SlockShellStoreScope,
    versionCode: String = "",
): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_WEB_VIEW_PAGE)
        put(SETTINGS_WEB_VIEW_SURFACE, surface.diagnosticsName)
        put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
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
    storeScope: build.raft.app.data.SlockShellStoreScope,
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
    val computerTarget =
        remember(pager.pageData.params.toString(), surface) {
            if (surface == SettingsWebViewSurface.Computers) {
                settingsComputerRouteIdentityFromPageData(pager.pageData.params)
            } else {
                null
            }
        }
    val computerSpec = remember(computerTarget) { computerTarget?.let(::settingsComputerWebViewSpec) }
    when (surface) {
        SettingsWebViewSurface.Onboarding -> SettingsWebViewInvalidRoute(pager)
        SettingsWebViewSurface.Computers ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(MR.strings.settings_computers_title),
                iconName = SlockLucideIconName.Monitor,
                spec = computerSpec ?: settingsComputersWebViewSpec,
            )
        SettingsWebViewSurface.Billing ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(MR.strings.settings_billing_title),
                iconName = SlockLucideIconName.CreditCard,
                spec = settingsBillingWebViewSpec,
            )
        SettingsWebViewSurface.Marketplace ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(MR.strings.settings_connected_apps_title),
                iconName = SlockLucideIconName.Link2,
                spec = settingsMarketplaceWebViewSpec,
            )
        SettingsWebViewSurface.Feedback ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(MR.strings.settings_feedback_title),
                iconName = SlockLucideIconName.MessageSquarePlus,
                spec = settingsFeedbackWebViewSpec,
                onHostMessage = { kind, _ ->
                    when (kind) {
                        "close" -> pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                        "auth-required" -> Unit // keep the page and draft; auth/session reconciliation supplies the next token
                        "integration-unavailable" -> {
                            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                            pager.openSettingsHandsFeedbackPage(
                                pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
                            )
                        }
                    }
                },
            )
        SettingsWebViewSurface.Administration ->
            StandaloneSettingsWebViewPage(
                pager = pager,
                title = stringResource(MR.strings.settings_server_administration_title),
                iconName = SlockLucideIconName.Shield,
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
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream)) {
        SlockTopPageHeader(
            title = stringResource(MR.strings.settings_title),
            iconName = SlockLucideIconName.Settings,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
                    size = 30.dp,
                    shadowOffset = 2.dp,
                )
            },
        )
        SettingsWebViewErrorContent(
            actionLabel = stringResource(MR.strings.common_close),
            actionIcon = SlockLucideIconName.X,
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
    iconName: SlockLucideIconName,
    spec: StandaloneSettingsWebViewSpec,
    isComputerDetail: Boolean = false,
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
            isComputerDetail = isComputerDetail,
            onHostMessage = onHostMessage,
        )
    }
}

@Composable
internal fun SettingsWebViewPageScaffold(
    pager: Pager,
    surface: SettingsWebViewSurface,
    title: String,
    iconName: SlockLucideIconName,
    headerFollowsDocumentTitle: Boolean = false,
    content: @Composable (
        controller: SlockWebViewController,
        onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit,
    ) -> Unit,
) {
    var bodyReady by remember { mutableStateOf(false) }
    val webViewController = rememberSlockWebViewController()
    var navigationState by remember { mutableStateOf(SlockWebViewNavigationState()) }
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

    SlockBackHandler(
        enabled = true,
        label = "settings_webview_${surface.diagnosticsName}",
        onBack = ::goBackOrClose,
    )

    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream)) {
        // This wrapper, rather than SlockTopPageHeader's inner content Box, is the sibling of the
        // WebView body. The layer must live here so it can order those two layout siblings.
        Box(modifier = Modifier.fillMaxWidth().zIndex(SETTINGS_WEB_VIEW_HEADER_Z_INDEX)) {
            SlockTopPageHeader(
                title =
                    if (headerFollowsDocumentTitle) {
                        headerTitleState.displayTitle(title)
                    } else {
                        title
                    },
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
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .zIndex(SETTINGS_WEB_VIEW_BODY_Z_INDEX)
                    .background(SlockColor.SurfaceCream),
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
    val state: SlockWebViewNavigationState,
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
    controller: SlockWebViewController,
    onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit,
    isComputerDetail: Boolean = false,
    onHostMessage: (String, JSONObject) -> Unit,
) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val sharedStores = remember(bridge) { build.raft.app.data.SlockSharedStoreRegistry.get(bridge) }
    // The Web settings surfaces own their product data in the embedded SPA. Keep this host path
    // limited to shell identity, canonical environment, and auth bootstrap; constructing a full
    // native SharedSettingsDataSource here would only prepare repositories that this page cannot
    // render or mutate.
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val authStore =
        remember(sharedStores, storeScope) {
            sharedStores.authStore(storeScope)
        }
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(authStore, sharedStores.authStore)
    val webBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
    val navigationScope = rememberCoroutineScope()
    val session = authSnapshot.session
    val selectedServerStore = remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val selectedServerId by selectedServerStore.selectedServerId.collectAsState()
    val serverId = session?.currentServerId
    val serverSlug = session?.servers?.firstOrNull { it.id == serverId }?.slug
    val computerNavigation = remember(webBaseUrl, serverId, serverSlug, authSnapshot.sessionGeneration, storeScope, selectedServerId) {
        if (spec.surface == SettingsWebViewSurface.Computers) {
            computerNavigationForSelection(webBaseUrl, serverSlug, serverId, selectedServerId, bridge::log)
        } else null
    }
    LaunchedEffect(computerNavigation, selectedServerId, serverId, serverSlug) {
        if (spec.surface == SettingsWebViewSurface.Computers) {
            val reason = when {
                serverId.isNullOrBlank() -> "no_session_server"
                selectedServerId != serverId -> "selection_mismatch"
                serverSlug.isNullOrBlank() -> "no_slug"
                webBaseUrl.isNullOrBlank() -> "no_origin"
                else -> "enabled"
            }
            bridge.log("computer_native_nav_state reason=$reason")
        }
    }
    LaunchedEffect(spec.surface, spec) {
        // Computers opens two distinct pages (list vs machine detail) that share one
        // surface name; tagging the target keeps the diagnostic unambiguous (#336).
        val targetTag = when {
            spec.surface != SettingsWebViewSurface.Computers -> null
            isComputerDetail -> "detail"
            else -> "list"
        }
        bridge.log(
            "settings_${spec.surface.diagnosticsName}_open" +
                (targetTag?.let { " target=$it" } ?: "")
        )
    }
    StandaloneSettingsWebViewContent(
        authSnapshot = authSnapshot,
        webBaseUrl = webBaseUrl,
        spec = spec,
        controller = controller,
        accessTokenSyncLogger = bridge::log,
        onUrlChange = { url ->
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_url_changed url=$url")
            when (val action = computerNavigation?.takeIf { selectedServerStore.isSelected(serverId) }?.accept(url)) {
                is ComputerNavigationAction.RestoreComputer -> {
                    bridge.log("computer_native_restore ticket=${action.ticket}")
                    controller.goBack()
                    navigationScope.launch {
                        delay(1000)
                        // replaceState and missing history need an explicit return location.
                        // Check session authority again after the asynchronous wait.
                        if (authStore.readSession() == session && selectedServerStore.isSelected(serverId)) {
                            computerNavigation?.fallbackUrl(action.ticket)?.let { returnUrl ->
                                controller.evaluateJavaScript(
                                    "window.location.replace(" + Json.encodeToString(returnUrl) + ");"
                                )
                            }
                        }
                    }
                }
                is ComputerNavigationAction.Open -> {
                    val target = action.target
                    bridge.log("computer_native_open kind=${target.kind}")
                    val pageData = if (target.kind == "agent") {
                        KuiklyMembersPayload(instanceId = "computer-agent").toMemberDetailPageData(
                            MemberDetailIdentity(
                                build.raft.app.model.MemberId(target.id),
                                build.raft.app.model.MemberKind.Agent,
                                serverId?.let { build.raft.app.model.ServerId(it) },
                            )
                        )
                    } else {
                        WireThreadLaunchPayload(
                            instanceId = "computer-dm-${target.id}",
                            surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                            streamChannelId = target.id,
                        ).toThreadPageData()
                    }
                    if (pageData != null) pager.openSlockPage(
                        pageName = if (target.kind == "agent") MEMBER_DETAIL_PAGE else THREAD_PAGE,
                        pageData = pageData,
                        storeScopeOverride = storeScope,
                        onMissingScope = { bridge.log("computer_native_open_skip reason=missing_shell_scope") },
                    )
                }
                null -> Unit
            }
        },
        onLoadCommit = {
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_load_commit")
        },
        onLoadFailure = { reason ->
            bridge.log("settings_${spec.surface.diagnosticsName}_webview_load_failure reason=$reason")
        },
        onNavigationStateChange = onNavigationStateChange,
    )
}

@Composable
internal fun StandaloneSettingsWebViewContent(
    authSnapshot: SettingsWebViewAuthSnapshot,
    webBaseUrl: String?,
    spec: StandaloneSettingsWebViewSpec,
    modifier: Modifier = Modifier,
    controller: SlockWebViewController = rememberSlockWebViewController(),
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
    val apiOrigin = sharedStores.apiEnvironmentStore.current().apiBaseUrl.trim().trimEnd('/')
    val injectedJavaScript =
        remember(spec.surface, session, hostAuthBinding, apiOrigin) {
            standaloneSettingsWebViewInjectedJavaScript(session, hostAuthBinding, spec.surface, apiOrigin)
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
        hostEventsEnabled = spec.hostEventsEnabled,
        bottomContentPadding = spec.bottomContentPadding,
        injectedJavaScript = injectedJavaScript,
        controller = controller,
        generationKey = hostAuthBinding?.webViewGeneration,
        onLoadCommit = onLoadCommit,
        onUrlChange = onUrlChange,
        filterStaleUrlCallbacks = spec.surface == SettingsWebViewSurface.Computers,
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
 * It owns the measured native-view attach gate, opaque cream cover, commit-ready transition,
 * failure/retry state, and navigation callbacks. Surface pages may provide identity/configuration,
 * but cannot change this first-frame sequence.
 */
@Composable
internal fun SettingsWebViewContent(
    url: String,
    modifier: Modifier = Modifier,
    allowedNavigationSchemes: Set<String> = setOf("http", "https"),
    allowNavigation: Boolean = true,
    bottomContentPadding: Dp = 0.dp,
    injectedJavaScript: String = "",
    controller: SlockWebViewController = rememberSlockWebViewController(),
    generationKey: Any? = null,
    onLoadCommit: () -> Unit = {},
    onLoadSuccess: () -> Unit = {},
    onUrlChange: (String) -> Unit = {},
    filterStaleUrlCallbacks: Boolean = false,
    onLoadFailure: (String) -> Unit = {},
    onNavigationBlocked: (String) -> Unit = {},
    onNavigationStateChange: (SettingsWebViewNavigationUpdate) -> Unit = {},
    onHostMessage: (String, JSONObject) -> Unit = { _, _ -> },
    onDocumentReadyChange: (Boolean) -> Unit = {},
) {
    var reloadKey by remember(url, generationKey) { mutableStateOf(0) }
    var contentVisible by remember(url, generationKey, reloadKey) { mutableStateOf(false) }
    var loadError by remember(url, generationKey, reloadKey) { mutableStateOf<String?>(null) }
    var navigationState by
        remember(url, generationKey, reloadKey) {
            mutableStateOf(SlockWebViewNavigationState(url = url, isLoading = true))
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
                .background(SlockColor.SurfaceCream)
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
        val webViewHeight = maxHeight + slockBottomSafeAreaPadding()
        if (webViewAttachReady) {
            key(generationKey, reloadKey, url) {
                val navigationOwner = remember { navigationAuthority.acquire() }
                DisposableEffect(navigationOwner) {
                    onDispose { navigationAuthority.release(navigationOwner) }
                }
                SlockWebView(
                    url = url,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(webViewHeight)
                            .background(SlockColor.SurfaceCream)
                            .padding(bottom = bottomContentPadding)
                            .alpha(if (contentVisible) 1f else 0f),
                    allowNavigation = allowNavigation,
                    allowedNavigationSchemes = allowedNavigationSchemes,
                    domStorageEnabled = true,
                    cacheEnabled = true,
                    ephemeral = false,
                    injectedJavaScript = injectedJavaScript,
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
                        contentVisible = false
                        loadError = reason
                        onLoadFailure(reason)
                    },
                    onNavigationBlocked = onNavigationBlocked,
                    onHostMessage = onHostMessage,
                    onNavigationStateChange = { state ->
                        if (navigationAuthority.accepts(navigationOwner)) {
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
                    actionIcon = SlockLucideIconName.RefreshCw,
                    onAction = { reloadKey += 1 },
                )
            SettingsWebViewPresentation.LoadingCover -> SettingsWebViewLoadingCover()
            SettingsWebViewPresentation.Content -> Unit
        }
    }
}

@Composable
internal fun SettingsWebViewLoadingCover() {
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream))
}

@Composable
internal fun SettingsWebViewErrorContent(
    actionLabel: String,
    actionIcon: SlockLucideIconName,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .background(SlockColor.BrandYellow)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center,
        ) {
            SlockLucideIcon(SlockLucideIconName.TriangleAlert, modifier = Modifier.size(26.dp))
        }
        Text(
            stringResource(MR.strings.settings_webview_error_title),
            style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
        )
        Text(
            stringResource(MR.strings.settings_webview_error_body),
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
        )
        SlockBrutalActionButton(
            label = actionLabel,
            onClick = onAction,
            leadingIcon = actionIcon,
            backgroundColor = SlockColor.SurfaceWhite,
        )
    }
}

internal fun settingsWebViewFrameIsReady(
    positionY: Float,
    width: Int,
    height: Int,
): Boolean = positionY.isFinite() && positionY >= 0f && width > 0 && height > 0

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
    apiOrigin: String = "",
): String {
    if (surface == SettingsWebViewSurface.Feedback) {
        return feedbackWebViewBootstrapJavaScript(session, hostBinding, apiOrigin)
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
): String {
    val json = kotlinx.serialization.json.Json
    val accountId = json.encodeToString(binding?.accountId.orEmpty())
    val sessionGeneration = binding?.sessionGeneration ?: 0L
    val webViewGeneration = binding?.webViewGeneration ?: 0L
    val accessToken = json.encodeToString(session?.accessToken.orEmpty())
    val api = json.encodeToString(apiOrigin)
    return """
        (function() { 'use strict';
          var port = window.raftHostPort;
          var binding = {accountId:$accountId, sessionGeneration:$sessionGeneration, webViewGeneration:$webViewGeneration};
          var session = {accountId:$accountId, sessionGeneration:$sessionGeneration, webViewGeneration:$webViewGeneration, accessToken:$accessToken, apiOrigin:$api, clientKind:'android', clientVersion:'mobile', locale:(navigator.language || 'en'), theme:'elegant', mode:((typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light')};
          Object.defineProperty(window, 'RaftFeedback', {value:{version:'raft-feedback-v1', session:session, emit:function(event, nextBinding){ if (!port) return; try { port.postMessage(JSON.stringify({kind:event,payload:nextBinding || binding})); } catch (_) {} }}, writable:false, configurable:false});
        })();
    """.trimIndent()
}

internal fun feedbackWebViewRuntimeSessionJavaScript(
    accessToken: String,
    accountId: String,
    sessionGeneration: Long,
    webViewGeneration: Long,
): String {
    val json = kotlinx.serialization.json.Json
    return """
        (function() { var host = window.RaftFeedback; if (!host || !host.session) return; host.session.accessToken = ${json.encodeToString(accessToken)}; host.session.accountId = ${json.encodeToString(accountId)}; host.session.sessionGeneration = $sessionGeneration; host.session.webViewGeneration = $webViewGeneration; window.dispatchEvent(new CustomEvent('raft:feedback-session-updated', {detail:host.session})); })();
    """.trimIndent()
}
