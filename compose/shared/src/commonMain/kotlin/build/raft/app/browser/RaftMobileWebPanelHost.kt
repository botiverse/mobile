package build.raft.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.base.BridgeModule
import build.raft.app.base.Utils
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.raftPlatformName
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftStatusBarBackdropClaim
import build.raft.designsystem.gesture.RaftBlockBackSwipe
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Unified bottom-rising mobile web panel host (#proj-workspace:#397, Option A per
 * kirby-link-sheet-v2.html) plus shared WebView + #356 native sign-in body reused by both the
 * overlay panel and standalone ComposeRaftInAppBrowserPage fallback.
 */

internal const val RAFT_WEB_PANEL_OVERLAY_TAG = "raft_web_panel_overlay"
internal const val RAFT_WEB_PANEL_BACKDROP_TAG = "raft_web_panel_backdrop"

/** Local message source set inside each ThreadMessageCard so link/App taps capture source context. */
internal val LocalRaftMobileWebPanelMessageSource = compositionLocalOf<RaftMobileWebPanelSource.Message?> { null }

/** Host controller installed at each top-level mobile BasePager (inherited by embedded child pages). */
internal val LocalRaftMobileWebPanelHost = staticCompositionLocalOf<RaftMobileWebPanelHostController?> { null }

/**
 * Per-window host bridge connecting [RaftLinkNavigator], [ThreadMessageCard], Marketplace, and the
 * [RaftMobileWebPanelOverlay] composable to the process-wide [RaftMobileWebPanelCoordinator].
 */
internal class RaftMobileWebPanelHostController(
    val hostId: String,
    val coordinator: RaftMobileWebPanelCoordinator = RaftMobileWebPanelCoordinator.process,
) {
    private var pendingSource: RaftMobileWebPanelSource? = null
    private var activeAccountId: String? = null
    private var activeEnvironmentKey: String? = null
    private val _inlineBadgeVisibleSessionId = MutableStateFlow<Long?>(null)
    val inlineBadgeVisibleSessionId: StateFlow<Long?> = _inlineBadgeVisibleSessionId.asStateFlow()

    fun updateAccountAndEnvironment(accountId: String?, environmentKey: String?) {
        activeAccountId = accountId
        activeEnvironmentKey = environmentKey
        coordinator.onAccountOrEnvironmentChanged(accountId, environmentKey)
    }

    fun setPendingSource(source: RaftMobileWebPanelSource?) {
        pendingSource = source
    }

    fun consumePendingSource(): RaftMobileWebPanelSource? {
        val current = pendingSource
        pendingSource = null
        return current
    }

    fun clearPendingSource() {
        pendingSource = null
    }

    fun reportInlineBadgeVisibility(sessionId: Long, visible: Boolean) {
        if (visible) {
            _inlineBadgeVisibleSessionId.value = sessionId
        } else if (_inlineBadgeVisibleSessionId.value == sessionId) {
            _inlineBadgeVisibleSessionId.value = null
        }
    }

    fun openLink(
        url: String,
        accountId: String? = null,
        environmentKey: String? = null,
        fallbackSurfaceLabel: String? = null,
    ): Boolean {
        val source = consumePendingSource() ?: fallbackSurfaceLabel?.let(RaftMobileWebPanelSource::Surface)
        coordinator.open(
            ownerHostId = hostId,
            launchUrl = url,
            appEntryToken = null,
            appName = null,
            appLogoUrl = null,
            isRaftApp = false,
            source = source,
            accountId = accountId ?: activeAccountId,
            environmentKey = environmentKey ?: activeEnvironmentKey,
        )
        return true
    }

    fun openAppEntry(
        url: String,
        appEntryToken: String,
        request: RaftAppEntryRequest,
        accountId: String? = null,
        environmentKey: String? = null,
        fallbackSurfaceLabel: String? = null,
    ): Boolean {
        val source = consumePendingSource() ?: fallbackSurfaceLabel?.let(RaftMobileWebPanelSource::Surface)
        coordinator.open(
            ownerHostId = hostId,
            launchUrl = url,
            appEntryToken = appEntryToken,
            appName = request.appName,
            appLogoUrl = request.appLogoUrl,
            isRaftApp = true,
            source = source,
            accountId = accountId ?: activeAccountId,
            environmentKey = environmentKey ?: activeEnvironmentKey,
        )
        return true
    }

    fun reopen(sessionId: Long) {
        coordinator.expand(sessionId, RaftMobileWebPanelDetent.Default88)
    }

    fun collapse(sessionId: Long) {
        coordinator.collapse(sessionId)
    }

    fun setDetent(sessionId: Long, detent: RaftMobileWebPanelDetent) {
        coordinator.setDetent(sessionId, detent)
    }

    fun close(sessionId: Long) {
        if (_inlineBadgeVisibleSessionId.value == sessionId) {
            _inlineBadgeVisibleSessionId.value = null
        }
        coordinator.close(sessionId)
    }

    fun updateCurrentUrl(sessionId: Long, url: String) {
        coordinator.updateCurrentUrl(sessionId, url)
    }

    fun onDispose() {
        pendingSource = null
        _inlineBadgeVisibleSessionId.value = null
        coordinator.onHostDisposed(hostId)
    }
}

/**
 * Installs the bottom-rising mobile web panel overlay above the current [BasePager]'s content.
 * Strictly 1 WebView is kept alive while collapsed; tapping `×` or replacing disposes it cleanly.
 */
@Composable
internal fun RaftMobileWebPanelOverlay(
    pager: Pager,
    bridge: BridgeModule,
    host: RaftMobileWebPanelHostController,
) {
    val activeSession by host.coordinator.session.collectAsState()
    val inlineBadgeVisibleSessionId by host.inlineBadgeVisibleSessionId.collectAsState()
    val ownedSession = activeSession?.takeIf { it.ownerHostId == host.hostId }

    DisposableEffect(host) {
        onDispose { host.onDispose() }
    }

    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val activeEnvironment by sharedStores.apiEnvironmentStore.state.collectAsState()
    val storeScope =
        LocalRaftArtifactOpenStoreScope.current
            ?: remember(pager.pageData.params.toString()) { pager.pageData.params.raftShellStoreScopeOrNull() }
    val authStore =
        remember(sharedStores, storeScope) {
            if (storeScope != null) sharedStores.authStore(storeScope) else sharedStores.authStore
        }
    val authEvents =
        remember(authStore, sharedStores.authStore) {
            settingsWebViewAuthEventSource(authStore, sharedStores.authStore)
        }
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(authStore, authEvents)
    val environmentKey = remember(activeEnvironment.webUrl, activeEnvironment.apiBaseUrl) {
        "${activeEnvironment.webUrl}|${activeEnvironment.apiBaseUrl}"
    }
    LaunchedEffect(host, sharedStores, authSnapshot.session?.user?.id, environmentKey) {
        val accountId = authSnapshot.session?.user?.id
        host.updateAccountAndEnvironment(accountId, environmentKey)
        sharedStores.onInstalledAppAccountOrEnvironmentChanged(accountId, environmentKey)
    }

    if (ownedSession == null) return

    RaftDesignSystemTheme {
        key(ownedSession.sessionId) {
            RaftMobileWebPanelOverlaySession(
                pager = pager,
                bridge = bridge,
                host = host,
                session = ownedSession,
                storeScope = storeScope,
                inlineBadgeVisible = inlineBadgeVisibleSessionId == ownedSession.sessionId,
            )
        }
    }
}

/**
 * Opaque canvas backdrop + dimmed 1-line source message strip shown above the 88% web panel.
 * Uses an opaque [RaftTheme.colors.layerCanvas] fill so the underlying conversation header cannot
 * bleed through behind the source strip or top safe area while expanded.
 */
@Composable
internal fun RaftMobileWebPanelBackdrop(
    slideProgress: Float,
    fullscreen: Boolean,
    source: RaftMobileWebPanelSource?,
    topSafeArea: Dp,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val scrimAlpha = resolveRaftMobileWebPanelBackdropAlpha(slideProgress)
    RaftStatusBarBackdropClaim(colors.layerCanvas, enabled = topSafeArea > 0.dp && scrimAlpha > 0.5f)
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(RAFT_WEB_PANEL_BACKDROP_TAG)
                .graphicsLayer { alpha = scrimAlpha }
                .background(colors.layerCanvas)
                // raw-click: backdrop scrim, a tap collapses the panel; not a control
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCollapse,
                ),
    ) {
        if (!fullscreen) {
            RaftMobileWebPanelSourceStrip(
                source = source,
                topSafeArea = topSafeArea,
                onCollapse = onCollapse,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

@Composable
private fun RaftMobileWebPanelOverlaySession(
    pager: Pager,
    bridge: BridgeModule,
    host: RaftMobileWebPanelHostController,
    session: RaftMobileWebPanelSession,
    storeScope: RaftShellStoreScope?,
    inlineBadgeVisible: Boolean,
) {
    val reducedMotion = rememberRaftReducedMotion()
    val density = LocalDensity.current
    val topSafeArea = raftWindowTopSafeAreaPadding()
    val bottomSafeArea = raftBottomSafeAreaPadding()
    val imeBottomDp = with(density) { raftImeBottomPx(density).toDp() }
    val expanded = session.isExpanded
    val fullscreen = session.detent == RaftMobileWebPanelDetent.Fullscreen

    // While expanded, block iOS full-page back-swipe from popping the conversation underneath
    // so left-edge swipe right goes cleanly to WKWebView's allowsBackForwardNavigationGestures.
    RaftBlockBackSwipe(enabled = expanded)

    val slideProgress = remember(session.sessionId) { Animatable(if (reducedMotion) 1f else 0f) }
    val detentProgress = remember(session.sessionId) { Animatable(0f) }
    var dragOffsetPx by remember(session.sessionId) { mutableFloatStateOf(0f) }
    var settledCollapsed by remember(session.sessionId) { mutableStateOf(false) }

    LaunchedEffect(expanded, reducedMotion) {
        dragOffsetPx = 0f
        if (expanded) {
            settledCollapsed = false
            if (reducedMotion) {
                slideProgress.snapTo(1f)
            } else {
                withFrameNanos { }
                slideProgress.animateTo(1f, tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide))
            }
        } else {
            if (reducedMotion) {
                slideProgress.snapTo(0f)
            } else {
                slideProgress.animateTo(0f, tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide))
            }
            settledCollapsed = true
        }
    }

    LaunchedEffect(fullscreen, reducedMotion) {
        val target = if (fullscreen) 1f else 0f
        if (reducedMotion) {
            detentProgress.snapTo(target)
        } else {
            detentProgress.animateTo(target, tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide))
        }
    }

    val panelHiddenOffscreen = !expanded && settledCollapsed

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().zIndex(RaftGlobalModalHostZIndex),
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val defaultTopPx = (viewportHeightPx * (1f - RAFT_MOBILE_WEB_PANEL_DEFAULT_HEIGHT_FRACTION))
            .coerceAtLeast(with(density) { (topSafeArea + 48.dp).toPx() })
            .coerceAtMost(viewportHeightPx * 0.3f)
        val fullscreenTopPx = with(density) { topSafeArea.toPx() }
        val baseTopPx = defaultTopPx + (fullscreenTopPx - defaultTopPx) * detentProgress.value
        val activeTopPx = (baseTopPx + dragOffsetPx).coerceIn(fullscreenTopPx, viewportHeightPx)
        val slideOffsetPx = (viewportHeightPx - activeTopPx).coerceAtLeast(0f) * (1f - slideProgress.value)
        val cornerRadius = if (detentProgress.value > 0.85f) 0.dp else RaftRadius.Dialog
        val panelShape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
        val colors = RaftTheme.colors

        // Opaque canvas backdrop + dimmed 1-line source message strip visible above the 88% panel.
        if (!panelHiddenOffscreen) {
            RaftMobileWebPanelBackdrop(
                slideProgress = slideProgress.value,
                fullscreen = fullscreen,
                source = session.source,
                topSafeArea = topSafeArea,
                onCollapse = { host.collapse(session.sessionId) },
            )
        }

        // The live WebView panel. When fully collapsed, measured at full bounds but NOT placed so
        // the native WebView / WKWebView stays alive in memory with zero drawing and zero touch hit.
        val containerModifier =
            if (panelHiddenOffscreen) {
                Modifier
                    .fillMaxSize()
                    .semantics { hideFromAccessibility() }
                    .layout { measurable, constraints ->
                        measurable.measure(constraints)
                        layout(0, 0) {}
                    }
            } else {
                Modifier
                    .fillMaxSize()
                    .padding(top = with(density) { activeTopPx.roundToInt().toDp() })
                    .graphicsLayer { translationY = slideOffsetPx }
            }

        Column(
            modifier =
                containerModifier
                    .testTag(RAFT_WEB_PANEL_OVERLAY_TAG)
                    .clip(panelShape)
                    .background(colors.layerCanvas, panelShape)
                    .border(RaftStroke.Hairline, colors.lineSeam, panelShape),
        ) {
            RaftMobileWebPanelDragLip(
                onDragDelta = { delta ->
                    dragOffsetPx = (dragOffsetPx + delta).coerceIn(fullscreenTopPx - baseTopPx, viewportHeightPx - baseTopPx)
                },
                onDragEnd = { velocityY ->
                    val next =
                        resolveRaftMobileWebPanelDragTarget(
                            current = session.detent,
                            dragDeltaPx = dragOffsetPx,
                            velocityYPxPerSec = velocityY,
                            viewportHeightPx = viewportHeightPx,
                        )
                    dragOffsetPx = 0f
                    host.setDetent(session.sessionId, next)
                },
            )
            RaftMobileWebPanelBrowserBody(
                pager = pager,
                bridge = bridge,
                launchUrl = session.launchUrl,
                appEntryToken = session.appEntryToken,
                fallbackAppName = session.appName,
                fallbackAppLogoUrl = session.appLogoUrl,
                storeScope = storeScope,
                sessionKey = session.sessionId,
                entryOwnerId = host.hostId,
                panelDetent = session.detent,
                onCurrentUrlChanged = { url -> host.updateCurrentUrl(session.sessionId, url) },
                onCollapse = { host.collapse(session.sessionId) },
                onClose = { host.close(session.sessionId) },
                onDropCollapsedOnProcessGone = { host.coordinator.clearCollapsed() },
                bottomInsetDp = maxOf(imeBottomDp, bottomSafeArea),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        // Floating fallback keep-open chip shown when collapsed and the source message is scrolled
        // off-screen (or when opened from a non-message surface like Marketplace).
        if (panelHiddenOffscreen && !inlineBadgeVisible) {
            RaftMobileWebPanelKeepOpenChip(
                label = session.keepOpenLabel,
                onClick = { host.reopen(session.sessionId) },
                elevated = true,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = maxOf(imeBottomDp, bottomSafeArea) + 16.dp)
                        .testTag(RAFT_WEB_PANEL_FLOATING_KEEP_OPEN_CHIP_TAG),
            )
        }
    }
}

/**
 * Shared browser body (WebView + loading bar + error state + minimal bottom bar + `#356` native
 * sign-in sheet host) used by BOTH [RaftMobileWebPanelOverlay] and standalone
 * [ComposeRaftInAppBrowserPage].
 */
@Composable
internal fun RaftMobileWebPanelBrowserBody(
    pager: Pager,
    bridge: BridgeModule,
    launchUrl: String?,
    appEntryToken: String?,
    fallbackAppName: String?,
    fallbackAppLogoUrl: String?,
    storeScope: RaftShellStoreScope?,
    sessionKey: Any,
    entryOwnerId: String,
    panelDetent: RaftMobileWebPanelDetent,
    onCurrentUrlChanged: (String) -> Unit,
    onCollapse: (() -> Unit)?,
    onClose: () -> Unit,
    onDropCollapsedOnProcessGone: (() -> Unit)?,
    bottomInsetDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val appStoreBillingRules = raftPlatformFollowsAppStoreBillingRules()
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val activeEnvironment by sharedStores.apiEnvironmentStore.state.collectAsState()
    val activeWebBaseUrl = activeEnvironment.webUrl
    val activeApiBaseUrl = activeEnvironment.apiBaseUrl

    val launchBlocked =
        remember(launchUrl, activeWebBaseUrl, appStoreBillingRules) {
            launchUrl != null && raftInAppBrowserShouldBlockUrl(launchUrl, activeWebBaseUrl, appStoreBillingRules)
        }
    val effectiveLaunchUrl = launchUrl?.takeUnless { launchBlocked }
    val entryRequest =
        remember(appEntryToken, effectiveLaunchUrl, entryOwnerId) {
            if (appEntryToken != null && effectiveLaunchUrl != null) {
                RaftAppEntryRegistry.process.claim(appEntryToken, effectiveLaunchUrl, entryOwnerId)
            } else {
                null
            }
        }
    val entryContext =
        remember(entryRequest, effectiveLaunchUrl, activeWebBaseUrl, activeApiBaseUrl) {
            if (entryRequest != null && effectiveLaunchUrl != null) {
                raftAppEntryContext(
                    request = entryRequest,
                    url = effectiveLaunchUrl,
                    raftHosts = raftBrowserRaftHosts(activeWebBaseUrl, activeApiBaseUrl),
                    debug = RaftCmpRouter.isDebugBuild,
                    platformName = raftPlatformName(),
                )
            } else {
                null
            }
        }
    val useEphemeralStore =
        remember(effectiveLaunchUrl, activeWebBaseUrl, appStoreBillingRules) {
            raftInAppBrowserUsesEphemeralStore(effectiveLaunchUrl, activeWebBaseUrl, appStoreBillingRules)
        }
    val storageResolution =
        remember(entryContext, useEphemeralStore) {
            resolveRaftMobileWebPanelStorage(
                isRaftAppEntry = entryContext != null,
                useEphemeralStore = useEphemeralStore,
                supportsIsolatedThirdPartyProfile = raftPlatformSupportsIsolatedWebViewProfile(),
            )
        }

    val controller = remember(sessionKey) { RaftWebViewController() }
    val externalNavigator =
        rememberRaftLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = RaftInAppBrowserRouteScope.Unscoped,
            useWebPanelHost = false,
        )
    val raftHostNavigator =
        rememberRaftLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = if (storeScope != null) RaftInAppBrowserRouteScope.InheritShell else RaftInAppBrowserRouteScope.Unscoped,
            useWebPanelHost = false,
        )

    // Defensive fail-closed guard: if an isolated third-party link somehow reaches the panel on a
    // device without MULTI_PROFILE, hand off to the system browser immediately and never load on Default.
    LaunchedEffect(storageResolution, effectiveLaunchUrl) {
        if (storageResolution == RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser && effectiveLaunchUrl != null) {
            bridge.logWarn("mobile_web_panel_isolated_profile_unavailable_handoff")
            externalNavigator.open(effectiveLaunchUrl, RaftLinkOpenIntent.ForceExternal)
            onClose()
        }
    }
    if (storageResolution !is RaftMobileWebPanelLaunchResolution.OpenInPanel) {
        return
    }

    val panelVisible = panelDetent != RaftMobileWebPanelDetent.Collapsed
    val authStore =
        remember(sharedStores, storeScope) {
            if (storeScope != null) sharedStores.authStore(storeScope) else sharedStores.authStore
        }
    val networkClient =
        remember(sharedStores, storeScope) {
            if (storeScope != null) sharedStores.networkClient(storeScope) else sharedStores.networkClient
        }
    val authEvents =
        remember(authStore, sharedStores.authStore) {
            settingsWebViewAuthEventSource(authStore, sharedStores.authStore)
        }
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(authStore, authEvents)
    val signInHost =
        rememberInAppBrowserSignInHost(
            pager = pager,
            bridge = bridge,
            sharedStores = sharedStores,
            storeScope = storeScope,
            entryContext = entryContext,
            activeWebBaseUrl = activeWebBaseUrl,
            activeApiBaseUrl = activeApiBaseUrl,
            controller = controller,
            sessionKey = sessionKey,
            panelVisible = panelVisible,
            onOpenInBrowser = { authorizeUrl ->
                externalNavigator.open(authorizeUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
            },
        )
    val signInUiState by signInHost.session.uiState.collectAsState()
    val stepUpState by signInHost.stepUp.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val copiedLabel = stringResource(Res.string.common_copied)
    val forwardFailedLabel = stringResource(Res.string.mobile_web_panel_forward_failed)
    val forwardedToTemplate = stringResource(Res.string.mobile_web_panel_forwarded_to, "__RAFT_FORWARD_TARGET__")
    var forwardUrlToSend by remember(sessionKey) { mutableStateOf<String?>(null) }
    var forwardTitleToSend by remember(sessionKey) { mutableStateOf<String?>(null) }
    var pendingExternalAppLaunch by remember(sessionKey) { mutableStateOf<RaftExternalAppLaunchDecision.ConfirmExternalApp?>(null) }
    LaunchedEffect(panelVisible) {
        if (!panelVisible) {
            pendingExternalAppLaunch = null
        }
    }
    val jsDialogsEnabled =
        panelVisible &&
            forwardUrlToSend == null &&
            signInUiState.sheet == null &&
            pendingExternalAppLaunch == null
    val jsDialogState = remember(sessionKey) { RaftWebJsDialogState(initialVisible = jsDialogsEnabled) }
    LaunchedEffect(jsDialogState, jsDialogsEnabled) {
        jsDialogState.onVisibilityChanged(jsDialogsEnabled)
    }

    var navigationState by
        remember(sessionKey, effectiveLaunchUrl) {
            mutableStateOf(
                RaftWebViewNavigationState(
                    url = effectiveLaunchUrl.orEmpty(),
                    isLoading = effectiveLaunchUrl != null,
                ),
            )
        }
    var loadError by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf<String?>(null) }
    var webViewAttachReady by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf(false) }
    var deflectedPurchase by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf(false) }
    var lastDeflectedNavigationKey by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf<String?>(null) }
    var lastCommittedGeneration by remember(sessionKey, effectiveLaunchUrl) { mutableIntStateOf(-1) }

    LaunchedEffect(sessionKey, effectiveLaunchUrl) {
        if (effectiveLaunchUrl == null) return@LaunchedEffect
        withFrameNanos { }
        webViewAttachReady = true
    }

    val currentUrl = navigationState.url.takeIf { it.isNotBlank() } ?: effectiveLaunchUrl.orEmpty()
    val latestDetent by rememberUpdatedState(panelDetent)

    fun handleAppStoreRestrictedPurchase(targetUrl: String, closeCurrentPage: Boolean): Boolean {
        if (!raftInAppBrowserShouldBlockUrl(targetUrl, activeWebBaseUrl, appStoreBillingRules)) return false
        bridge.logWarn("in_app_browser_appstore_purchase_blocked url=${raftOwnWebUrlForLog(targetUrl)}")
        if (
            raftUrlIsOwnBillingPath(targetUrl, activeWebBaseUrl) &&
            storeScope != null &&
            settingsBillingOpenTarget() != SettingsBillingOpenTarget.ExternalBrowser
        ) {
            pager.openSettingsBillingPage(storeScope, replaceCurrentPage = closeCurrentPage)
        } else if (closeCurrentPage) {
            onClose()
        }
        return true
    }

    fun routeRaftHostOut(targetUrl: String, closeOnHandoff: Boolean) {
        if (entryContext != null || !appStoreBillingRules) {
            val ownTarget =
                if (storeScope != null && raftOwnLinkIsWebPage(targetUrl, activeWebBaseUrl)) {
                    pager.openRaftOwnWebPage(targetUrl, storeScopeOverride = storeScope, activeWebBaseUrl = activeWebBaseUrl)
                } else {
                    false
                }
            if (!ownTarget) {
                val decision =
                    if (storeScope != null && raftOwnLinkShellOpener(webUrl = { activeWebBaseUrl })(targetUrl)) {
                        RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, targetUrl)
                    } else {
                        raftHostNavigator.open(targetUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
                    }
                if (decision.target == RaftLinkOpenTarget.Rejected) {
                    externalNavigator.open(targetUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
                }
            }
            return
        }
        val replaced =
            if (storeScope != null) {
                pager.openRaftInAppBrowserPage(targetUrl, storeScopeOverride = storeScope, replaceCurrentPage = true)
            } else {
                pager.openUnscopedRaftInAppBrowserPage(targetUrl, replaceCurrentPage = true)
            }
        if (!replaced) {
            if (closeOnHandoff) onClose()
            externalNavigator.open(targetUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
        }
    }

    fun openCurrentInSystemBrowser() {
        val target = currentUrl.takeIf { it.isNotBlank() } ?: return
        if (handleAppStoreRestrictedPurchase(target, closeCurrentPage = false)) return
        externalNavigator.open(target, RaftLinkOpenIntent.ForceExternal)
    }

    fun shareCurrentUrl() {
        val target = currentUrl.takeIf { it.isNotBlank() } ?: return
        coroutineScope.launch {
            val shared =
                bridge.settingsCapability(JSONObject().put("action", "shareText").put("text", target))
                    ?.optString("status") == "ok"
            if (!shared) {
                bridge.copyToPasteboard(target)
                bridge.briefToast(copiedLabel)
            }
        }
    }

    fun copyCurrentUrl() {
        val target = currentUrl.takeIf { it.isNotBlank() } ?: return
        bridge.copyToPasteboard(target)
        bridge.briefToast(copiedLabel)
    }

    // Android Back / edge swipe: goBack if history exists, else collapse keeping alive (or close in standalone page).
    RaftBackHandler(
        enabled = panelVisible,
        label = "mobile_web_panel",
        blocksBackSwipe = false,
        onBack = {
            when (resolveRaftMobileWebPanelBackAction(latestDetent, navigationState.canGoBack)) {
                RaftMobileWebPanelBackAction.WebGoBack -> {
                    loadError = null
                    controller.goBack()
                }
                RaftMobileWebPanelBackAction.CollapseKeepingAlive -> onCollapse?.invoke() ?: onClose()
                RaftMobileWebPanelBackAction.Ignore -> Unit
            }
        },
    )

    Column(modifier = modifier.background(RaftTheme.colors.layerCanvas)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite)) {
            when {
                effectiveLaunchUrl == null -> {
                    RaftInAppBrowserError(
                        message = stringResource(Res.string.in_app_browser_invalid_link),
                        onRetry = null,
                        onOpenSystem = null,
                        onClose = onClose,
                    )
                }
                webViewAttachReady -> {
                    RaftWebView(
                        url = effectiveLaunchUrl,
                        modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite),
                        controller = controller,
                        allowScripts = true,
                        allowNavigation = true,
                        allowedNavigationSchemes = setOf("http", "https"),
                        domStorageEnabled = true,
                        cacheEnabled = true,
                        ephemeral = useEphemeralStore,
                        storagePartition = storageResolution.storagePartition,
                        allowsBackForwardNavigationGestures = true,
                        raftHost = if (signInHost.flagEnabled) entryContext?.raftHost else null,
                        injectedJavaScript = "",
                        diagnosticsSurface = "in_app_browser",
                        showsFormAccessoryBar = true,
                        allowJsDialogs = jsDialogsEnabled,
                        allowNewWindows = raftPlatformName() == "android" && panelVisible,
                        onJsDialog = { request -> jsDialogState.show(request) },
                        catchesMainFrameNavigation = { ask -> signInHost.session.onMainFrameNavigation(ask) },
                        routesMainFrameNavigation = { candidateUrl ->
                            raftInAppBrowserShouldRouteNavigation(
                                candidateUrl = candidateUrl,
                                activeWebBaseUrl = activeWebBaseUrl,
                                appStoreBillingRules = appStoreBillingRules,
                                useEphemeralStore = useEphemeralStore,
                                activeApiBaseUrl = activeApiBaseUrl,
                            )
                        },
                        onContentProcessGone = {
                            if (latestDetent == RaftMobileWebPanelDetent.Collapsed) {
                                onDropCollapsedOnProcessGone?.invoke()
                            }
                        },
                        onLoadCommit = {
                            lastCommittedGeneration = navigationState.loadGeneration
                            pendingExternalAppLaunch = null
                            signInHost.session.onPageCommitted()
                        },
                        onLoadSuccess = {
                            loadError = null
                            if (navigationState.loadGeneration != lastCommittedGeneration) {
                                lastCommittedGeneration = navigationState.loadGeneration
                                signInHost.session.onPageCommitted()
                            }
                        },
                        onLoadFailure = { reason ->
                            if (reason == RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON) {
                                bridge.logWarn("mobile_web_panel_isolated_profile_runtime_fail_closed")
                                externalNavigator.open(effectiveLaunchUrl, RaftLinkOpenIntent.ForceExternal)
                                onClose()
                                return@RaftWebView
                            }
                            loadError = reason.ifBlank { "webview_load_failed" }
                            bridge.logWarn("in_app_browser_load_failed")
                        },
                        onNavigationBlockedWithGesture = { blockedUrl, isUserGesture ->
                            if (handleAppStoreRestrictedPurchase(blockedUrl, closeCurrentPage = false)) {
                                return@RaftWebView
                            }
                            if (!useEphemeralStore && raftInAppBrowserIsRaftHost(blockedUrl, activeWebBaseUrl, activeApiBaseUrl)) {
                                routeRaftHostOut(blockedUrl, closeOnHandoff = false)
                                return@RaftWebView
                            }
                            when (
                                val decision =
                                    classifyRaftExternalAppLaunch(
                                        rawUrl = blockedUrl,
                                        isUserGesture = isUserGesture,
                                        panelVisible = latestDetent != RaftMobileWebPanelDetent.Collapsed,
                                    )
                            ) {
                                is RaftExternalAppLaunchDecision.OpenExternalWeb -> {
                                    externalNavigator.open(decision.url, RaftLinkOpenIntent.ForceExternal)
                                }
                                is RaftExternalAppLaunchDecision.ConfirmExternalApp -> {
                                    pendingExternalAppLaunch = decision
                                }
                                is RaftExternalAppLaunchDecision.Drop -> {
                                    bridge.logWarn(
                                        "external_app_launch_blocked reason=${decision.reason} scheme=${decision.scheme.orEmpty()}",
                                    )
                                }
                            }
                        },
                        onNavigationStateChange = { nextState ->
                            val nextUrl = nextState.url.trim()
                            if (
                                nextUrl.isNotEmpty() &&
                                raftInAppBrowserShouldRouteNavigation(
                                    candidateUrl = nextUrl,
                                    activeWebBaseUrl = activeWebBaseUrl,
                                    appStoreBillingRules = appStoreBillingRules,
                                    useEphemeralStore = useEphemeralStore,
                                    activeApiBaseUrl = activeApiBaseUrl,
                                )
                            ) {
                                val navigationKey = "${nextState.loadGeneration}|$nextUrl"
                                if (lastDeflectedNavigationKey != navigationKey) {
                                    lastDeflectedNavigationKey = navigationKey
                                    if (nextState.isLoading) {
                                        controller.stop()
                                    }
                                    if (!deflectedPurchase) {
                                        val isRestrictedPurchase =
                                            handleAppStoreRestrictedPurchase(nextUrl, closeCurrentPage = true)
                                        if (isRestrictedPurchase) {
                                            deflectedPurchase = true
                                        } else {
                                            if (entryContext == null && appStoreBillingRules) {
                                                deflectedPurchase = true
                                            }
                                            routeRaftHostOut(nextUrl, closeOnHandoff = true)
                                        }
                                    }
                                }
                                return@RaftWebView
                            }
                            navigationState = nextState
                            if (nextUrl.isNotEmpty()) onCurrentUrlChanged(nextUrl)
                            if (nextState.isLoading) loadError = null
                        },
                    )
                    val declinedAuthorizeUrl = signInUiState.declinedAuthorizeUrl
                    if (declinedAuthorizeUrl != null) {
                        RaftInAppBrowserError(
                            message = stringResource(Res.string.in_app_browser_sign_in_declined),
                            onRetry = null,
                            onOpenSystem = {
                                signInHost.session.dismissDeclinedNotice()
                                externalNavigator.open(declinedAuthorizeUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
                            },
                            onClose = signInHost.session::dismissDeclinedNotice,
                        )
                    } else if (loadError != null) {
                        RaftInAppBrowserError(
                            message = stringResource(Res.string.in_app_browser_load_error),
                            onRetry = {
                                loadError = null
                                controller.reload()
                            },
                            onOpenSystem = if (storageResolution.isRaftApp) null else ::openCurrentInSystemBrowser,
                            onClose = if (storageResolution.isRaftApp) onClose else null,
                        )
                    }
                }
                else -> Unit
            }
            if (effectiveLaunchUrl != null) {
                RaftWebPageLoadingBar(
                    raftWebPageLoadState(
                        isLoading = navigationState.isLoading,
                        progress = navigationState.progress,
                        failed = loadError != null,
                        loadGeneration = navigationState.loadGeneration,
                    ),
                )
            }
        }
        RaftMobileWebPanelBottomBar(
            isRaftApp = storageResolution.isRaftApp,
            currentUrl = currentUrl,
            appName = entryContext?.appName ?: fallbackAppName,
            appLogoUrl = entryContext?.appLogoUrl ?: fallbackAppLogoUrl,
            signedInAsName = signInHost.signedInAsName,
            canGoBack = navigationState.canGoBack,
            canGoForward = navigationState.canGoForward,
            onGoBack = {
                if (navigationState.canGoBack) {
                    loadError = null
                    controller.goBack()
                }
            },
            onGoForward = {
                if (navigationState.canGoForward) {
                    loadError = null
                    controller.goForward()
                }
            },
            onReload = {
                loadError = null
                controller.reload()
            },
            onForwardToRaft =
                if (authSnapshot.session?.servers?.isNotEmpty() == true && canForwardWebPageUrl(currentUrl)) {
                    {
                        forwardUrlToSend = currentUrl
                        forwardTitleToSend = navigationState.title
                    }
                } else {
                    null
                },
            onClose = onClose,
            onShare = ::shareCurrentUrl,
            onCopyLink = ::copyCurrentUrl,
            onOpenInBrowser = if (storageResolution.isRaftApp) null else ::openCurrentInSystemBrowser,
        )
        if (bottomInsetDp > 0.dp) {
            Spacer(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(bottomInsetDp)
                        .background(RaftTheme.colors.layerCanvas),
            )
        }
    }

    RaftWebJsDialogHost(state = jsDialogState)

    RaftExternalAppLaunchDialogHost(
        request = pendingExternalAppLaunch,
        onConfirm = { confirmed ->
            pendingExternalAppLaunch = null
            bridge.openUrl(confirmed.url, inBrowser = false)
        },
        onDismiss = {
            pendingExternalAppLaunch = null
        },
    )

    val activeForwardUrl = forwardUrlToSend
    val activeAuthSession = authSnapshot.session
    if (activeForwardUrl != null && activeAuthSession != null) {
        RaftWebPageForwardHost(
            url = activeForwardUrl,
            pageTitle = forwardTitleToSend,
            session = activeAuthSession,
            authStore = authStore,
            networkClient = networkClient,
            bridge = bridge,
            onDismiss = {
                forwardUrlToSend = null
                forwardTitleToSend = null
            },
            onForwarded = { targetTitle ->
                forwardUrlToSend = null
                forwardTitleToSend = null
                bridge.briefToast(forwardedToTemplate.replace("__RAFT_FORWARD_TARGET__", targetTitle))
            },
            onForwardFailed = {
                bridge.briefToast(forwardFailedLabel)
            },
        )
    }

    RaftBrowserSignInSheetHost(
        sheet = signInUiState.sheet,
        stepUpState = stepUpState,
        onStepUpIntent = { intent -> coroutineScope.launch { signInHost.stepUp.accept(intent) } },
        appForeground = signInHost.appForeground,
        onSelectServer = signInHost.session::selectServer,
        onApprove = signInHost.session::approveCurrent,
        onCancel = signInHost.session::cancelSheet,
        onOpenInBrowser = signInHost.session::openOriginalAuthorizeInBrowser,
    )
}
