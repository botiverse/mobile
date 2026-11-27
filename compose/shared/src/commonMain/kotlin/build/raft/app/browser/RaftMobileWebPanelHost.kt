package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.raftPlatformName
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.resources.*
import build.raft.app.settings.RaftWebPageLoadingBar
import build.raft.app.settings.SettingsBillingOpenTarget
import build.raft.app.settings.openRaftOwnWebPage
import build.raft.app.settings.openSettingsBillingPage
import build.raft.app.settings.raftUrlIsOwnBillingPath
import build.raft.app.settings.raftWebPageLoadState
import build.raft.app.settings.rememberRaftPagerAppeared
import build.raft.app.settings.rememberSettingsWebViewAuthSnapshot
import build.raft.app.settings.settingsBillingOpenTarget
import build.raft.app.settings.settingsWebViewAuthEventSource
import build.raft.app.shell.RaftInAppBrowserRouteScope
import build.raft.app.shell.RaftLinkOpenDecision
import build.raft.app.shell.RaftLinkOpenIntent
import build.raft.app.shell.RaftLinkOpenTarget
import build.raft.app.shell.appShellRailPlacesEnabled
import build.raft.app.shell.raftOwnLinkIsWebPage
import build.raft.app.shell.raftOwnLinkShellOpener
import build.raft.app.shell.rememberRaftLinkNavigator
import build.raft.app.thread.icon
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.gesture.RaftBlockBackSwipe
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.launch

internal const val RAFT_MOBILE_WEB_PANEL_LOADING_PLACEHOLDER_TAG = "raft_mobile_web_panel_loading_placeholder"

private class RaftBooleanLatch(var value: Boolean = false)

/*
 * Shared in-app browser body used by ComposeRaftInAppBrowserPage (both full-screen right-to-left
 * stack pages and wide-layout side column presentation, as well as cross-origin unauthenticated
 * child pages):
 * - Top bar (App identity + [N] switcher + ⋮ menu, OR domain-only on cross-origin child page).
 * - WebView preserving all Phase 1 capabilities (JS alert/confirm/prompt foreground-only with
 *   auto-dismiss, manual Refresh, Forward to Raft, fullscreen video, non-http scheme safety,
 *   #356 native sign-in sheet, App Store 3.1.1 guards, media capture denial, 2-live LRU warm pool).
 * - Cross-origin outbound link routing inside installed Raft Apps (raft-design #1417 Row 12):
 *   same-origin stays in the App; another installed Raft App switches/opens its App tab; any other
 *   cross-origin http(s) link pushes a clean unauthenticated domain-only child page.
 * - Compact bottom nav strip (Back, Forward, Reload, explicit Close ×).
 */

@Composable
internal fun RaftMobileWebPanelBrowserBody(
    pager: Pager,
    bridge: BridgeModule,
    launchUrl: String?,
    appEntryToken: String?,
    entryClaimUrl: String? = launchUrl,
    fallbackAppName: String?,
    fallbackAppLogoUrl: String?,
    storeScope: RaftShellStoreScope?,
    sessionKey: Any,
    entryOwnerId: String,
    isOutboundChild: Boolean,
    inColumn: Boolean,
    keepAliveKey: String?,
    openTabCount: Int,
    onOpenSwitcher: (() -> Unit)?,
    onPushOutboundChild: (String) -> Unit,
    onOpenInstalledApp: (url: String, match: RaftInstalledAppOriginMatch, serverId: String) -> Unit,
    onNavigationMetadataChanged: (url: String, title: String?) -> Unit,
    onSuspend: (() -> Unit)?,
    onClose: () -> Unit,
    onContentProcessGone: (() -> Unit)?,
    bottomInsetDp: Dp,
    modifier: Modifier = Modifier,
    closing: Boolean = false,
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
    val effectiveLaunchUrl = remember(sessionKey, launchBlocked) { launchUrl?.takeUnless { launchBlocked } }
    val claimUrl = entryClaimUrl?.trim()?.takeIf { it.isNotEmpty() } ?: effectiveLaunchUrl
    val entryRequest =
        remember(appEntryToken, claimUrl, entryOwnerId, isOutboundChild) {
            if (!isOutboundChild && appEntryToken != null && claimUrl != null) {
                RaftAppEntryRegistry.process.claim(appEntryToken, claimUrl, entryOwnerId)
            } else {
                null
            }
        }
    val entryContext =
        remember(entryRequest, effectiveLaunchUrl, activeWebBaseUrl, activeApiBaseUrl, isOutboundChild) {
            if (!isOutboundChild && entryRequest != null && effectiveLaunchUrl != null) {
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
    val authorizedAppEntryLatch = remember(sessionKey) { RaftBooleanLatch() }
    if (!isOutboundChild && entryContext != null) {
        authorizedAppEntryLatch.value = true
    }
    val wasAuthorizedAppEntry = authorizedAppEntryLatch.value
    val useEphemeralStore =
        remember(effectiveLaunchUrl, activeWebBaseUrl, appStoreBillingRules) {
            raftInAppBrowserUsesEphemeralStore(effectiveLaunchUrl, activeWebBaseUrl, appStoreBillingRules)
        }
    val storageResolution =
        remember(entryContext, useEphemeralStore, isOutboundChild) {
            resolveRaftMobileWebPanelStorage(
                isRaftAppEntry = !isOutboundChild && entryContext != null,
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
        )
    val raftHostNavigator =
        rememberRaftLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = if (storeScope != null) RaftInAppBrowserRouteScope.InheritShell else RaftInAppBrowserRouteScope.Unscoped,
        )

    // Defensive fail-closed guard: if an isolated outbound/third-party page reaches the WebView
    // without MULTI_PROFILE (pre-Chromium 119 Android), hand off to the system browser immediately.
    // Never hand off an already-closing panel or a session previously authorized as a Raft App.
    val failClosedAction =
        resolveRaftMobileWebPanelFailClosedAction(
            storageResolution = storageResolution,
            effectiveLaunchUrl = effectiveLaunchUrl,
            closing = closing,
            wasAuthorizedAppEntry = wasAuthorizedAppEntry,
        )
    LaunchedEffect(failClosedAction, effectiveLaunchUrl) {
        when (failClosedAction) {
            RaftMobileWebPanelFailClosedAction.None -> Unit
            RaftMobileWebPanelFailClosedAction.CloseSilently -> onClose()
            RaftMobileWebPanelFailClosedAction.HandoffToSystemBrowser -> {
                val url = effectiveLaunchUrl ?: return@LaunchedEffect
                bridge.logWarn("mobile_web_panel_isolated_profile_unavailable_handoff")
                externalNavigator.open(url, RaftLinkOpenIntent.ForceExternal)
                onClose()
            }
        }
    }
    if (closing || storageResolution !is RaftMobileWebPanelLaunchResolution.OpenInPanel) {
        return
    }

    val pageVisible = rememberRaftPagerAppeared(pager)
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
    val initialSession =
        remember(sharedStores.authStore, storeScope) {
            val committed = runCatching { sharedStores.authStore.readCommittedSessionSnapshot() }.getOrNull()
            val expectedServerId = storeScope?.initialServerId?.trim()?.takeIf { it.isNotEmpty() }
            if (committed != null && (expectedServerId == null || committed.currentServerId == expectedServerId)) {
                committed
            } else {
                null
            }
        }
    val authSnapshot = rememberSettingsWebViewAuthSnapshot(authStore, authEvents, initialSession = initialSession)
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
            panelVisible = pageVisible,
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
    LaunchedEffect(pageVisible) {
        if (!pageVisible) {
            pendingExternalAppLaunch = null
        }
    }
    val jsDialogsEnabled =
        pageVisible &&
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
    var hasVisualCommit by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf(false) }
    var deflectedPurchase by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf(false) }
    var lastDeflectedNavigationKey by remember(sessionKey, effectiveLaunchUrl) { mutableStateOf<String?>(null) }
    var lastCommittedGeneration by remember(sessionKey, effectiveLaunchUrl) { mutableIntStateOf(-1) }

    LaunchedEffect(sessionKey, effectiveLaunchUrl) {
        if (effectiveLaunchUrl == null) return@LaunchedEffect
        withFrameNanos { }
        webViewAttachReady = true
    }

    val currentUrl = navigationState.url.takeIf { it.isNotBlank() } ?: effectiveLaunchUrl.orEmpty()
    val latestPageVisible by rememberUpdatedState(pageVisible)
    val latestOnPushOutboundChild by rememberUpdatedState(onPushOutboundChild)
    val latestOnOpenInstalledApp by rememberUpdatedState(onOpenInstalledApp)
    val latestOnNavigationMetadataChanged by rememberUpdatedState(onNavigationMetadataChanged)

    // On iOS, block native stack swipe-back only while WebView has web history so left-edge swipe
    // navigates web history first and natively pops/suspends back to chat at the root entry.
    RaftBlockBackSwipe(enabled = !inColumn && navigationState.canGoBack)

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
        if (closeOnHandoff) onClose()
        externalNavigator.open(targetUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
    }

    fun lookupOtherInstalledAppSync(candidateOrigin: String): Pair<RaftInstalledAppOriginMatch, String>? {
        val serverId =
            entryContext?.preferredServerId?.trim()?.takeIf { it.isNotEmpty() }
                ?: authSnapshot.session?.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
        val scope = sharedStores.currentInstalledAppScopeSync(serverId)
        val cached = scope?.let { sharedStores.installedAppOriginsCache.lookupSync(it, candidateOrigin) }
        if (cached is RaftInstalledAppSyncLookup.Matched && cached.match.origin == candidateOrigin) {
            return cached.match to cached.serverId
        }
        if (appShellRailPlacesEnabled(sharedStores.serverFeatureFlags, serverId)) {
            val railApp = sharedStores.railPlacesStore.usableAppOn(serverId, candidateOrigin)
            if (railApp != null) {
                val logoUrl =
                    railApp.iconUrl?.let { icon ->
                        build.raft.app.data.resolveAppLinkIconUrl(icon, activeApiBaseUrl)
                    }
                return RaftInstalledAppOriginMatch(
                    origin = candidateOrigin,
                    appName = railApp.name,
                    logoUrl = logoUrl,
                ) to serverId
            }
        }
        return null
    }

    fun routeCrossOriginOutboundFromApp(targetUrl: String): Boolean {
        val scopeOrigin = entryContext?.scopeOrigin ?: return false
        if (isOutboundChild || useEphemeralStore) return false
        if (
            !raftAppShouldRouteCrossOriginOutbound(
                candidateUrl = targetUrl,
                scopeOrigin = scopeOrigin,
                activeWebBaseUrl = activeWebBaseUrl,
                activeApiBaseUrl = activeApiBaseUrl,
                appStoreBillingRules = appStoreBillingRules,
            )
        ) {
            return false
        }
        val candidateOrigin = raftAppEntryOriginOf(targetUrl) ?: return false
        val installedOther = lookupOtherInstalledAppSync(candidateOrigin)
        if (installedOther != null) {
            latestOnOpenInstalledApp(targetUrl, installedOther.first, installedOther.second)
        } else {
            latestOnPushOutboundChild(targetUrl)
        }
        return true
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

    fun handleExitToHost() {
        onClose()
    }

    fun handleNavigateBack() {
        when (resolveRaftAppBrowserBackAction(navigationState.canGoBack, isOutboundChild)) {
            RaftAppBrowserBackAction.WebGoBack -> {
                loadError = null
                controller.goBack()
            }
            RaftAppBrowserBackAction.PopOutboundChild,
            RaftAppBrowserBackAction.CloseAppPage -> onClose()
        }
    }

    // Android Back / gesture & top-bar ←: WebView back if history exists, else close cleanly.
    RaftBackHandler(
        enabled = !inColumn && pageVisible,
        label = if (isOutboundChild) "in_app_browser_outbound" else "in_app_browser_app",
        blocksBackSwipe = false,
        onBack = ::handleNavigateBack,
    )

    Column(modifier = modifier.background(RaftTheme.colors.layerCanvas)) {
        RaftAppBrowserTopBar(
            isRaftApp = storageResolution.isRaftApp,
            isOutboundChild = isOutboundChild,
            currentUrl = currentUrl,
            appName = if (isOutboundChild) null else entryContext?.appName ?: fallbackAppName,
            appLogoUrl = if (isOutboundChild) null else entryContext?.appLogoUrl ?: fallbackAppLogoUrl,
            signedInAsName = if (isOutboundChild) null else signInHost.signedInAsName,
            onBack = ::handleNavigateBack,
            onClosePage = onClose,
            onReload = {
                loadError = null
                controller.reload()
            },
            onShare = ::shareCurrentUrl,
            onCopyLink = ::copyCurrentUrl,
            onOpenInBrowser = ::openCurrentInSystemBrowser,
            inColumn = inColumn,
            canGoBack = navigationState.canGoBack,
            onExitToHost = ::handleExitToHost,
            onMinimizeToFloat = if (!isOutboundChild && !inColumn) onSuspend else null,
            openTabCount = openTabCount,
            onOpenSwitcher = onOpenSwitcher,
            onForwardToRaft =
                if (authSnapshot.session?.servers?.isNotEmpty() == true && canForwardWebPageUrl(currentUrl)) {
                    {
                        forwardUrlToSend = currentUrl
                        forwardTitleToSend = navigationState.title
                    }
                } else {
                    null
                },
        )
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
                        raftHost = if (!isOutboundChild && signInHost.flagEnabled) entryContext?.raftHost else null,
                        injectedJavaScript = "",
                        diagnosticsSurface = if (isOutboundChild) "in_app_browser_outbound" else "in_app_browser",
                        showsFormAccessoryBar = true,
                        allowJsDialogs = jsDialogsEnabled,
                        // Keep multi-window off on all platforms: Android WebView with
                        // setSupportMultipleWindows(false) routes target="_blank" and user-gesture
                        // window.open(url) straight through primary shouldOverrideUrlLoading
                        // (same unified navigation/routing/OAuth fence, no hidden popup WebView).
                        allowNewWindows = false,
                        keepAliveKey = if (storageResolution.isRaftApp && !isOutboundChild) keepAliveKey else null,
                        onJsDialog = { request -> jsDialogState.show(request) },
                        catchesMainFrameNavigation = { ask ->
                            if (!isOutboundChild) signInHost.session.onMainFrameNavigation(ask) else false
                        },
                        routesMainFrameNavigation = { candidateUrl ->
                            raftInAppBrowserShouldRouteNavigation(
                                candidateUrl = candidateUrl,
                                activeWebBaseUrl = activeWebBaseUrl,
                                appStoreBillingRules = appStoreBillingRules,
                                useEphemeralStore = useEphemeralStore,
                                activeApiBaseUrl = activeApiBaseUrl,
                            ) ||
                                (
                                    !isOutboundChild &&
                                        !useEphemeralStore &&
                                        entryContext != null &&
                                        raftAppShouldRouteCrossOriginOutbound(
                                            candidateUrl = candidateUrl,
                                            scopeOrigin = entryContext.scopeOrigin,
                                            activeWebBaseUrl = activeWebBaseUrl,
                                            activeApiBaseUrl = activeApiBaseUrl,
                                            appStoreBillingRules = appStoreBillingRules,
                                        )
                                    )
                        },
                        onContentProcessGone = { onContentProcessGone?.invoke() },
                        onLoadCommit = {
                            hasVisualCommit = true
                            lastCommittedGeneration = navigationState.loadGeneration
                            pendingExternalAppLaunch = null
                            if (!isOutboundChild) signInHost.session.onPageCommitted()
                        },
                        onLoadSuccess = {
                            hasVisualCommit = true
                            loadError = null
                            if (navigationState.loadGeneration != lastCommittedGeneration) {
                                lastCommittedGeneration = navigationState.loadGeneration
                                if (!isOutboundChild) signInHost.session.onPageCommitted()
                            }
                        },
                        onLoadFailure = { reason ->
                            if (reason == RAFT_WEBVIEW_ISOLATED_PROFILE_UNAVAILABLE_REASON) {
                                if (closing || wasAuthorizedAppEntry) {
                                    if (!closing) onClose()
                                    return@RaftWebView
                                }
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
                            if (routeCrossOriginOutboundFromApp(blockedUrl)) {
                                return@RaftWebView
                            }
                            when (
                                val decision =
                                    classifyRaftExternalAppLaunch(
                                        rawUrl = blockedUrl,
                                        isUserGesture = isUserGesture,
                                        panelVisible = latestPageVisible,
                                    )
                            ) {
                                is RaftExternalAppLaunchDecision.OpenExternalWeb -> {
                                    if (!routeCrossOriginOutboundFromApp(decision.url)) {
                                        externalNavigator.open(decision.url, RaftLinkOpenIntent.ForceExternal)
                                    }
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
                            if (nextState.progress >= 25 || !nextState.isLoading) {
                                hasVisualCommit = true
                            }
                            if (nextUrl.isNotEmpty()) {
                                latestOnNavigationMetadataChanged(nextUrl, nextState.title.takeIf { it.isNotBlank() })
                            }
                            if (nextState.isLoading) loadError = null
                        },
                    )
                    val declinedAuthorizeUrl = signInUiState.declinedAuthorizeUrl
                    if (!isOutboundChild && declinedAuthorizeUrl != null) {
                        RaftInAppBrowserError(
                            message = raftBrowserSignInDeclinedMessage(signInUiState.declinedReason),
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
                            onOpenSystem = ::openCurrentInSystemBrowser,
                            onClose = if (storageResolution.isRaftApp) onClose else null,
                        )
                    } else if (!isOutboundChild && signInUiState.resolving) {
                        RaftBrowserSignInResolvingOverlay()
                    }
                }
                else -> Unit
            }
            val showInitialLoadingPlaceholder =
                shouldShowRaftMobileWebPanelLoadingPlaceholder(
                    hasLaunchUrl = effectiveLaunchUrl != null,
                    hasVisualCommit = hasVisualCommit,
                    hasLoadError = loadError != null,
                    isSignInResolving = !isOutboundChild && signInUiState.resolving,
                    hasDeclinedSignIn = !isOutboundChild && signInUiState.declinedAuthorizeUrl != null,
                    isLoading = navigationState.isLoading,
                    progress = navigationState.progress,
                )
            if (showInitialLoadingPlaceholder) {
                RaftMobileWebPanelLoadingPlaceholder()
            }
            if (effectiveLaunchUrl != null) {
                RaftWebPageLoadingBar(
                    raftWebPageLoadState(
                        isLoading = navigationState.isLoading,
                        progress = navigationState.progress,
                        failed = loadError != null,
                        loadGeneration = navigationState.loadGeneration,
                    ),
                    appearDelayMillis = 0,
                )
            }
        }
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

    if (!isOutboundChild) {
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
}

/**
 * Lightweight centered spinner shown over the blank canvas on frame 0 until the initial document
 * visually commits (never covers warm keep-alive tabs, error screens, or sign-in overlays).
 */
@Composable
internal fun RaftMobileWebPanelLoadingPlaceholder(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(RaftTheme.colors.layerCanvas)
                .testTag(RAFT_MOBILE_WEB_PANEL_LOADING_PLACEHOLDER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        RaftSpinner(
            size = RaftSpinnerSize.Md,
            color = RaftTheme.colors.foregroundMuted,
        )
    }
}
