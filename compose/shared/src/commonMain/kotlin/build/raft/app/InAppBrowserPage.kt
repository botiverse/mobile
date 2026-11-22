package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import build.raft.app.base.Utils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize

internal const val RAFT_IN_APP_BROWSER_PAGE = "compose_in_app_browser"
internal const val RAFT_IN_APP_BROWSER_URL_KEY = "url"

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
 * Whether an in-page navigation inside [RaftInAppBrowserScreen] must be intercepted by
 * `routesMainFrameNavigation` on iOS:
 * - Always intercepts restricted purchase/billing/Stripe URLs.
 * - When this WebView is backed by persistent storage (`useEphemeralStore == false`, opened from a
 *   third-party link), also intercepts any navigation or redirect onto a Raft own host so the
 *   persistent `WKWebsiteDataStore.defaultDataStore()` can never load a Raft page with a token.
 */
internal fun raftInAppBrowserShouldRouteNavigation(
    candidateUrl: String,
    activeWebBaseUrl: String?,
    appStoreBillingRules: Boolean = raftPlatformFollowsAppStoreBillingRules(),
    useEphemeralStore: Boolean = false,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): Boolean {
    if (!appStoreBillingRules) return false
    if (raftInAppBrowserShouldBlockUrl(candidateUrl, activeWebBaseUrl, appStoreBillingRules = true, appHosts = appHosts)) {
        return true
    }
    return !useEphemeralStore && raftUrlIsOnOwnRaftHost(candidateUrl.trim(), activeWebBaseUrl, appHosts)
}

@Page(RAFT_IN_APP_BROWSER_PAGE, supportInLocal = true)
internal class ComposeRaftInAppBrowserPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftInAppBrowserScreen() }
    }
}

@Composable
private fun RaftInAppBrowserScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val launchUrl =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.raftInAppBrowserUrlOrNull()
        }
    val appStoreBillingRules = raftPlatformFollowsAppStoreBillingRules()
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val activeEnvironment by sharedStores.apiEnvironmentStore.state.collectAsState()
    val activeWebBaseUrl = activeEnvironment.webUrl
    val storeScope = remember(pager.pageData.params.toString()) { pager.pageData.params.raftShellStoreScopeOrNull() }
    val launchBlocked =
        remember(launchUrl, activeWebBaseUrl, appStoreBillingRules) {
            launchUrl != null && raftInAppBrowserShouldBlockUrl(launchUrl, activeWebBaseUrl, appStoreBillingRules)
        }
    val effectiveLaunchUrl = launchUrl?.takeUnless { launchBlocked }
    val useEphemeralStore =
        remember(effectiveLaunchUrl, activeWebBaseUrl, appStoreBillingRules) {
            raftInAppBrowserUsesEphemeralStore(effectiveLaunchUrl, activeWebBaseUrl, appStoreBillingRules)
        }
    val controller = rememberRaftWebViewController()
    val externalNavigator =
        rememberRaftLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = RaftInAppBrowserRouteScope.Unscoped,
        )
    var navigationState by
        remember(effectiveLaunchUrl) {
            mutableStateOf(
                RaftWebViewNavigationState(
                    url = effectiveLaunchUrl.orEmpty(),
                    isLoading = effectiveLaunchUrl != null,
                )
            )
        }
    var loadError by remember(effectiveLaunchUrl) { mutableStateOf<String?>(null) }
    var webViewAttachReady by remember(effectiveLaunchUrl) { mutableStateOf(false) }
    var deflectedPurchase by remember(effectiveLaunchUrl) { mutableStateOf(false) }
    LaunchedEffect(effectiveLaunchUrl) {
        if (effectiveLaunchUrl == null) return@LaunchedEffect
        // One rendered frame first: a native view attached in the page's first frame draws over the
        // header until layout settles (task #227, as SettingsWebViewPageScaffold). The load starts then.
        withFrameNanos { }
        webViewAttachReady = true
    }

    fun closePage() {
        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

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
            closePage()
        }
        return true
    }

    fun goBackOrClose() {
        if (navigationState.canGoBack) controller.goBack() else closePage()
    }

    fun openInSystemBrowser() {
        val target = navigationState.url.takeIf { it.isNotBlank() } ?: effectiveLaunchUrl ?: return
        if (handleAppStoreRestrictedPurchase(target, closeCurrentPage = false)) return
        externalNavigator.open(target, RaftLinkOpenIntent.ForceExternal)
    }

    RaftBackHandler(
        enabled = true,
        label = "in_app_browser",
        blocksBackSwipe = false,
        onBack = ::goBackOrClose,
    )

    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftTopPageHeader(
            title = navigationState.title.ifBlank { stringResource(Res.string.in_app_browser_title) },
            subtitle =
                (navigationState.url.takeIf { it.isNotBlank() } ?: effectiveLaunchUrl)
                    ?.let(::raftInAppBrowserDisplayUrl),
            iconName = RaftLucideIconName.Globe,
            backgroundColor = RaftColor.SurfaceWhite,
            onBack = ::goBackOrClose,
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftIconButton(
                        onClick = controller::goForward,
                        tooltip = stringResource(Res.string.in_app_browser_forward),
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Md,
                        enabled = navigationState.canGoForward,
                    ) {
                        RaftSlotIcon(RaftLucideIconName.ChevronRight)
                    }
                    RaftIconButton(
                        onClick = {
                            if (navigationState.isLoading) controller.stop() else controller.reload()
                        },
                        tooltip =
                            if (navigationState.isLoading) {
                                stringResource(Res.string.common_stop)
                            } else {
                                stringResource(Res.string.in_app_browser_reload)
                            },
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Md,
                    ) {
                        RaftSlotIcon(if (navigationState.isLoading) RaftLucideIconName.X else RaftLucideIconName.RefreshCw)
                    }
                    RaftIconButton(
                        onClick = ::openInSystemBrowser,
                        tooltip = stringResource(Res.string.in_app_browser_open_system),
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Md,
                        enabled = effectiveLaunchUrl != null,
                    ) {
                        // raft-ui 0.26 (#758): a control that leaves Raft is marked ↗.
                        RaftSlotIcon(RaftLucideIconName.ArrowUpRight)
                    }
                }
            },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite)) {
            when {
                effectiveLaunchUrl == null -> {
                    RaftInAppBrowserError(
                        message = stringResource(Res.string.in_app_browser_invalid_link),
                        onRetry = null,
                        onOpenSystem = null,
                        onClose = ::closePage,
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
                        injectedJavaScript = "",
                        diagnosticsSurface = "in_app_browser",
                        // Other sites' forms: keep WebKit's field-to-field bar, as Safari does.
                        showsFormAccessoryBar = true,
                        routesMainFrameNavigation =
                            if (appStoreBillingRules) {
                                { candidateUrl ->
                                    raftInAppBrowserShouldRouteNavigation(
                                        candidateUrl = candidateUrl,
                                        activeWebBaseUrl = activeWebBaseUrl,
                                        appStoreBillingRules = true,
                                        useEphemeralStore = useEphemeralStore,
                                    )
                                }
                            } else {
                                null
                            },
                        onLoadSuccess = { loadError = null },
                        onLoadFailure = { reason ->
                            loadError = reason.ifBlank { "webview_load_failed" }
                            bridge.logWarn("in_app_browser_load_failed")
                        },
                        onNavigationBlocked = { blockedUrl ->
                            if (handleAppStoreRestrictedPurchase(blockedUrl, closeCurrentPage = false)) {
                                return@RaftWebView
                            }
                            if (appStoreBillingRules && !useEphemeralStore && raftUrlIsOnOwnRaftHost(blockedUrl.trim(), activeWebBaseUrl)) {
                                // Third-party persistent InAppBrowser navigated/redirected to a Raft
                                // own host: hand off to a fresh ephemeral InAppBrowserPage (or external).
                                val replaced =
                                    if (storeScope != null) {
                                        pager.openRaftInAppBrowserPage(blockedUrl, storeScopeOverride = storeScope, replaceCurrentPage = true)
                                    } else {
                                        pager.openUnscopedRaftInAppBrowserPage(blockedUrl, replaceCurrentPage = true)
                                    }
                                if (!replaced) {
                                    externalNavigator.open(blockedUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
                                }
                                return@RaftWebView
                            }
                            externalNavigator.open(blockedUrl, RaftLinkOpenIntent.ForceExternal)
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
                                )
                            ) {
                                controller.stop()
                                if (!deflectedPurchase) {
                                    deflectedPurchase = true
                                    if (!handleAppStoreRestrictedPurchase(nextUrl, closeCurrentPage = true)) {
                                        val replaced =
                                            if (storeScope != null) {
                                                pager.openRaftInAppBrowserPage(nextUrl, storeScopeOverride = storeScope, replaceCurrentPage = true)
                                            } else {
                                                pager.openUnscopedRaftInAppBrowserPage(nextUrl, replaceCurrentPage = true)
                                            }
                                        if (!replaced) {
                                            closePage()
                                            externalNavigator.open(nextUrl, RaftLinkOpenIntent.RaftWebPageInBrowser)
                                        }
                                    }
                                }
                                return@RaftWebView
                            }
                            navigationState = nextState
                            if (nextState.isLoading) loadError = null
                        },
                    )
                    if (loadError != null) {
                        RaftInAppBrowserError(
                            message = stringResource(Res.string.in_app_browser_load_error),
                            onRetry = {
                                loadError = null
                                controller.reload()
                            },
                            onOpenSystem = ::openInSystemBrowser,
                            onClose = null,
                        )
                    }
                }
                // The frame before the WebView attaches: the progress bar below already shows.
                else -> Unit
            }
            // Over the page (not above it), so the page does not jump when the load ends. Always
            // composed: the line plays its own end when the load finishes, and goes at once when it
            // fails or is stopped.
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
