package build.raft.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.theme.RaftTheme

internal const val RAFT_IN_APP_BROWSER_PAGE = "compose_in_app_browser"
internal const val RAFT_IN_APP_BROWSER_URL_KEY = "url"
internal const val RAFT_IN_APP_BROWSER_TAB_ID_KEY = "tabId"
internal const val RAFT_IN_APP_BROWSER_OUTBOUND_KEY = "outbound"

/**
 * Whether [RaftInAppBrowserScreen] must block loading or navigating to [url] (iOS App Store
 * Guideline 3.1.1: billing, `/servers/<id>/upgrade`, own-host `?upgrade=...`, and Stripe hosts).
 */
internal fun raftInAppBrowserShouldBlockUrl(
    url: String,
    activeWebBaseUrl: String?,
    appStoreBillingRules: Boolean = raftPlatformFollowsAppStoreBillingRules(),
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): Boolean =
    appStoreBillingRules &&
        raftUrlIsAppStoreRestrictedPurchaseUrl(
            rawUrl = url,
            activeWebBaseUrl = activeWebBaseUrl,
            appHosts = appHosts,
        )

/**
 * On iOS (App Store Guideline 3.1.1), opening any Raft own-host URL in the general in-app browser
 * must use a non-persistent [WKWebsiteDataStore] (`ephemeral = true`) so it can never inherit
 * `slock_access_token` from persistent Settings WebView storage and show authenticated paywall or
 * upgrade UI inside the app.
 */
internal fun raftInAppBrowserUsesEphemeralStore(
    url: String?,
    activeWebBaseUrl: String?,
    appStoreBillingRules: Boolean = raftPlatformFollowsAppStoreBillingRules(),
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): Boolean =
    appStoreBillingRules &&
        !url.isNullOrBlank() &&
        raftUrlIsOnOwnRaftHost(url.trim(), activeWebBaseUrl, appHosts)

/**
 * Whether [candidateUrl] is on any of Raft's own hosts (active environment web or API host,
 * production Raft hosts, or client-config app hosts; #proj-workspace:#356 Decision #8).
 */
internal fun raftInAppBrowserIsRaftHost(
    candidateUrl: String,
    activeWebBaseUrl: String?,
    activeApiBaseUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): Boolean {
    val trimmed = candidateUrl.trim()
    if (trimmed.isEmpty()) return false
    if (raftUrlIsOnOwnRaftHost(trimmed, activeWebBaseUrl, appHosts)) return true
    val host = raftBrowserUrlOf(trimmed)?.host ?: return false
    return raftBrowserIsRaftHost(host, raftBrowserRaftHosts(activeWebBaseUrl, activeApiBaseUrl, appHosts))
}

/**
 * Whether an in-page navigation inside [RaftInAppBrowserScreen] must be intercepted by
 * `routesMainFrameNavigation`:
 * - On iOS (App Store Guideline 3.1.1), always intercepts restricted purchase/billing/Stripe URLs.
 * - On both Android and iOS (#proj-workspace:#356 Decision #8), when not in an ephemeral own-host
 *   view (`useEphemeralStore == false`), intercepts every Raft host navigation so Raft pages never
 *   load inside the general in-app browser (caught OAuth authorize endpoints are handled first by
 *   `catchesMainFrameNavigation`).
 */
internal fun raftInAppBrowserShouldRouteNavigation(
    candidateUrl: String,
    activeWebBaseUrl: String?,
    appStoreBillingRules: Boolean = raftPlatformFollowsAppStoreBillingRules(),
    useEphemeralStore: Boolean = false,
    activeApiBaseUrl: String? = null,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): Boolean {
    if (raftInAppBrowserShouldBlockUrl(candidateUrl, activeWebBaseUrl, appStoreBillingRules = appStoreBillingRules, appHosts = appHosts)) {
        return true
    }
    return !useEphemeralStore && raftInAppBrowserIsRaftHost(candidateUrl, activeWebBaseUrl, activeApiBaseUrl, appHosts)
}

@Page(RAFT_IN_APP_BROWSER_PAGE, supportInLocal = true)
internal class ComposeRaftInAppBrowserPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftInAppBrowserScreen() }
    }

    override fun pageWillDestroy() {
        val managedTabId = pageData.params.raftInAppBrowserTabIdOrNull()
        if (managedTabId == null) {
            pageData.params.raftInAppBrowserAppEntryTokenOrNull()?.let { token ->
                RaftAppEntryRegistry.process.release(token, pagerId)
            }
        }
        super.pageWillDestroy()
    }
}

@Composable
private fun RaftInAppBrowserScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val coordinator = RaftMobileWebPanelCoordinator.process
    val inColumn = LocalRaftArtifactViewerInColumn.current
    val initialUrl =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.raftInAppBrowserUrlOrNull()
        }
    val initialAppEntryToken =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.raftInAppBrowserAppEntryTokenOrNull()
        }
    val initialTabId =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.raftInAppBrowserTabIdOrNull()
        }
    val isOutboundChild =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.raftInAppBrowserIsOutbound()
        }
    val storeScope = remember(pager.pageData.params.toString()) { pager.pageData.params.raftShellStoreScopeOrNull() }
    val density = LocalDensity.current
    val topSafeArea = if (inColumn) 0.dp else raftPageWindowTopSafeAreaPadding()
    val bottomSafeArea = if (inColumn) 0.dp else raftBottomSafeAreaPadding()
    val imeBottomDp = with(density) { raftImeBottomPx(density).toDp() }

    val tabsState by coordinator.state.collectAsState()
    var currentTabId by remember(pager.pagerId, initialTabId) { mutableStateOf(initialTabId) }
    var explicitlyClosed by remember(pager.pagerId) { mutableStateOf(false) }
    var explicitlySuspended by remember(pager.pagerId) { mutableStateOf(false) }
    var switcherOpen by remember(pager.pagerId) { mutableStateOf(false) }
    var inColumnOutboundUrl by remember(pager.pagerId, currentTabId) { mutableStateOf<String?>(null) }

    val activeTab =
        if (!isOutboundChild && currentTabId != null) {
            tabsState.tabs.firstOrNull { it.tabId == currentTabId }
        } else {
            null
        }
    val missingManagedTab = !isOutboundChild && currentTabId != null && activeTab == null
    val isClosing = explicitlyClosed || missingManagedTab
    var lastKeepAliveKey by remember(pager.pagerId, currentTabId) { mutableStateOf(activeTab?.keepAliveKey) }
    if (activeTab?.keepAliveKey != null && lastKeepAliveKey != activeTab.keepAliveKey) {
        lastKeepAliveKey = activeTab.keepAliveKey
    }

    fun closePage() {
        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    // Keep coordinator activeTabId synced while this App stack page is mounted. Under default-close
    // semantics, popping without an explicit Float action closes the tab cleanly (iOS left-edge
    // swipe-back at root never auto-floats); explicit Float sets [explicitlySuspended] and parks the
    // warm WebView in the keep-alive pool. Declared before the WebView body so onDispose runs after
    // child onRelease and immediately evicts any container parked by WebContainerPlatformHost.
    val activeTabKeepAliveKey = activeTab?.keepAliveKey
    DisposableEffect(isOutboundChild, currentTabId) {
        val tabId = if (!isOutboundChild) currentTabId else null
        val keepAliveKey = if (!isOutboundChild) activeTabKeepAliveKey else null
        if (tabId != null) {
            coordinator.activateTab(tabId)
        }
        onDispose {
            if (tabId != null) {
                if (explicitlySuspended) {
                    coordinator.suspendTab(tabId)
                } else if (!explicitlyClosed && coordinator.state.value.activeTabId == tabId) {
                    coordinator.closeTab(tabId)
                }
                if (
                    keepAliveKey != null &&
                    !explicitlySuspended &&
                    (explicitlyClosed || !coordinator.isKeepAliveEligible(keepAliveKey))
                ) {
                    raftWebViewKeepAliveEvict(keepAliveKey)
                }
            }
        }
    }

    LaunchedEffect(isClosing, lastKeepAliveKey) {
        if (isClosing) {
            lastKeepAliveKey?.let(::raftWebViewKeepAliveEvict)
        }
    }

    // If the managed tab was removed externally (e.g. logout, server switch, dropped rail app), close page.
    LaunchedEffect(isOutboundChild, currentTabId, activeTab) {
        if (!isOutboundChild && currentTabId != null && activeTab == null) {
            explicitlyClosed = true
            closePage()
        }
    }

    val effectiveLaunchUrl =
        when {
            isOutboundChild -> initialUrl
            activeTab != null -> activeTab.loadedBaseUrl
            else -> initialUrl
        }
    val effectiveClaimUrl =
        when {
            isOutboundChild -> null
            activeTab != null -> activeTab.launchUrl
            else -> initialUrl
        }
    val effectiveEntryToken =
        when {
            isOutboundChild -> null
            activeTab != null -> activeTab.appEntryToken
            else -> initialAppEntryToken
        }
    val effectiveEntryOwnerId =
        when {
            isOutboundChild -> pager.pagerId
            activeTab != null -> activeTab.entryOwnerId
            else -> pager.pagerId
        }
    val effectiveSessionKey: Any =
        when {
            isOutboundChild -> "${pager.pagerId}:outbound"
            activeTab != null -> "app-tab:${activeTab.tabId}"
            else -> pager.pagerId
        }

    fun openOutboundChild(targetUrl: String) {
        if (!raftPlatformSupportsIsolatedWebViewProfile()) {
            bridge.openUrl(targetUrl, systemInAppBrowser = true)
            return
        }
        if (inColumn) {
            inColumnOutboundUrl = targetUrl
        } else {
            pager.openRaftInAppBrowserOutboundChild(
                rawUrl = targetUrl,
                storeScopeOverride = storeScope,
            )
        }
    }

    fun openAnotherInstalledApp(url: String, match: RaftInstalledAppOriginMatch, serverId: String) {
        val sharedStores = RaftSharedStoreRegistry.get(bridge)
        val env = sharedStores.apiEnvironmentStore.current()
        val envKey = "${env.webUrl}|${env.apiBaseUrl}"
        val accountId = runCatching { sharedStores.authStore.readCommittedSessionSnapshot()?.user?.id }.getOrNull()
        val request =
            RaftAppEntryRequest(
                scopeOrigin = match.origin,
                appName = match.appName,
                preferredServerId = serverId,
                appLogoUrl = match.logoUrl,
            )
        val token = RaftAppEntryRegistry.process.register(url, request)
        val result =
            coordinator.openOrActivateApp(
                launchUrl = url,
                appEntryToken = token,
                request = request,
                accountId = accountId,
                serverId = serverId,
                environmentKey = envKey,
            )
        inColumnOutboundUrl = null
        currentTabId = result.tab.tabId
    }

    RaftDesignSystemTheme {
        Box(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
            Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
                if (topSafeArea > 0.dp) {
                    Spacer(modifier = Modifier.fillMaxWidth().height(topSafeArea).background(RaftTheme.colors.layerCanvas))
                }
                if (isClosing) {
                    Spacer(modifier = Modifier.weight(1f).fillMaxWidth().background(RaftTheme.colors.layerCanvas))
                } else {
                    key(effectiveSessionKey) {
                        RaftMobileWebPanelBrowserBody(
                            pager = pager,
                            bridge = bridge,
                            launchUrl = effectiveLaunchUrl,
                            appEntryToken = effectiveEntryToken,
                            entryClaimUrl = effectiveClaimUrl,
                            fallbackAppName = activeTab?.appName,
                            fallbackAppLogoUrl = activeTab?.appLogoUrl,
                            storeScope = storeScope,
                            sessionKey = effectiveSessionKey,
                            entryOwnerId = effectiveEntryOwnerId,
                            isOutboundChild = isOutboundChild,
                            inColumn = inColumn,
                            closing = isClosing,
                            keepAliveKey = activeTab?.takeIf { !explicitlyClosed && it.isLive }?.keepAliveKey,
                            openTabCount = if (isOutboundChild) 1 else tabsState.tabs.size,
                            onOpenSwitcher =
                                if (!isOutboundChild && tabsState.tabs.size >= 2) {
                                    { switcherOpen = true }
                                } else {
                                    null
                                },
                            onPushOutboundChild = ::openOutboundChild,
                            onOpenInstalledApp = ::openAnotherInstalledApp,
                            onNavigationMetadataChanged = { url, title ->
                                activeTab?.let { tab ->
                                    coordinator.updateTabNavigation(tab.tabId, currentUrl = url, pageTitle = title)
                                }
                            },
                            onSuspend =
                                if (!isOutboundChild && !inColumn) {
                                    {
                                        explicitlySuspended = true
                                        activeTab?.let { coordinator.suspendTab(it.tabId) }
                                        closePage()
                                    }
                                } else {
                                    null
                                },
                            onClose = {
                                explicitlyClosed = true
                                activeTab?.let { coordinator.closeTab(it.tabId) }
                                closePage()
                            },
                            onContentProcessGone = {
                                activeTab?.let { coordinator.onTabContentProcessGone(it.tabId) }
                            },
                            bottomInsetDp = maxOf(imeBottomDp, bottomSafeArea),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }

            // Wide side-column outbound cross-origin child page: slides right-to-left over the live
            // parent App inside the side column so the parent App stays mounted underneath.
            val columnChildUrl = inColumnOutboundUrl
            AnimatedVisibility(
                visible = !isClosing && inColumn && !isOutboundChild && columnChildUrl != null,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (columnChildUrl != null) {
                    key(columnChildUrl) {
                        RaftMobileWebPanelBrowserBody(
                            pager = pager,
                            bridge = bridge,
                            launchUrl = columnChildUrl,
                            appEntryToken = null,
                            fallbackAppName = null,
                            fallbackAppLogoUrl = null,
                            storeScope = storeScope,
                            sessionKey = "${pager.pagerId}:column-outbound:$columnChildUrl",
                            entryOwnerId = "${pager.pagerId}:column-outbound",
                            isOutboundChild = true,
                            inColumn = true,
                            keepAliveKey = null,
                            openTabCount = 1,
                            onOpenSwitcher = null,
                            onPushOutboundChild = { nestedUrl -> inColumnOutboundUrl = nestedUrl },
                            onOpenInstalledApp = ::openAnotherInstalledApp,
                            onNavigationMetadataChanged = { _, _ -> },
                            onSuspend = null,
                            onClose = { inColumnOutboundUrl = null },
                            onContentProcessGone = null,
                            bottomInsetDp = 0.dp,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            if (!isClosing && switcherOpen && !isOutboundChild && tabsState.tabs.isNotEmpty()) {
                RaftAppBrowserSwitcherSheet(
                    tabs = tabsState.tabs,
                    activeTabId = currentTabId,
                    onSelectTab = { selected ->
                        switcherOpen = false
                        if (selected.tabId != currentTabId) {
                            inColumnOutboundUrl = null
                            coordinator.activateTab(selected.tabId)
                            currentTabId = selected.tabId
                        }
                    },
                    onCloseTab = { tabId ->
                        val closingCurrent = tabId == currentTabId
                        if (closingCurrent && tabsState.tabs.size <= 1) {
                            switcherOpen = false
                            explicitlyClosed = true
                        }
                        coordinator.closeTab(tabId)
                        if (closingCurrent) {
                            val nextTab = coordinator.state.value.tabs.firstOrNull()
                            if (nextTab != null) {
                                coordinator.activateTab(nextTab.tabId)
                                currentTabId = nextTab.tabId
                            } else {
                                switcherOpen = false
                                explicitlyClosed = true
                                closePage()
                            }
                        }
                    },
                    onCloseAll = {
                        switcherOpen = false
                        explicitlyClosed = true
                        coordinator.closeAll()
                        closePage()
                    },
                    onDismiss = { switcherOpen = false },
                )
            }
        }
    }
}

@Composable
internal fun RaftInAppBrowserError(
    message: String,
    onRetry: (() -> Unit)?,
    onOpenSystem: (() -> Unit)?,
    onClose: (() -> Unit)?,
) {
    // task #176: the action buttons sit at the bottom of a standalone page.
    val bottomSafeArea = raftBottomSafeAreaPadding()
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp + bottomSafeArea),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = message,
                style = RaftType.Body.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray,
            )
        }
        onRetry?.let {
            RaftButton(
                text = stringResource(Res.string.common_retry),
                onClick = it,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) },
                modifier = Modifier.fillMaxWidth(),
                variant = RaftButtonVariant.Primary,
                size = RaftButtonSize.Lg,
            )
        }
        onOpenSystem?.let {
            RaftButton(
                text = stringResource(Res.string.in_app_browser_open_system),
                onClick = it,
                // raft-ui 0.26 (#758): leaving Raft ends in ↗, in the trailing icon's place.
                trailingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowUpRight) },
                modifier = Modifier.fillMaxWidth(),
                size = RaftButtonSize.Lg,
            )
        }
        onClose?.let {
            RaftButton(
                text = stringResource(Res.string.common_close),
                onClick = it,
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.X) },
                modifier = Modifier.fillMaxWidth(),
                size = RaftButtonSize.Lg,
            )
        }
    }
}
