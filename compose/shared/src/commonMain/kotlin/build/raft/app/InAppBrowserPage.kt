package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.gesture.RaftBlockBackSwipe
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.base.Utils
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.data.RaftClientConfigAppHosts

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
        pageData.params.raftInAppBrowserAppEntryTokenOrNull()?.let { token ->
            RaftAppEntryRegistry.process.release(token, pagerId)
        }
        super.pageWillDestroy()
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
    val appEntryToken =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.raftInAppBrowserAppEntryTokenOrNull()
        }
    val storeScope = remember(pager.pageData.params.toString()) { pager.pageData.params.raftShellStoreScopeOrNull() }
    val density = LocalDensity.current
    val topSafeArea = raftPageWindowTopSafeAreaPadding()
    val bottomSafeArea = raftBottomSafeAreaPadding()
    val imeBottomDp = with(density) { raftImeBottomPx(density).toDp() }

    fun closePage() {
        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    // Block iOS full-page back-swipe so left-edge swipe right performs native WKWebView web back.
    RaftBlockBackSwipe(enabled = true)

    RaftDesignSystemTheme {
        Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
            if (topSafeArea > 0.dp) {
                Spacer(modifier = Modifier.fillMaxWidth().height(topSafeArea).background(RaftTheme.colors.layerCanvas))
            }
            RaftMobileWebPanelBrowserBody(
                pager = pager,
                bridge = bridge,
                launchUrl = launchUrl,
                appEntryToken = appEntryToken,
                fallbackAppName = null,
                fallbackAppLogoUrl = null,
                storeScope = storeScope,
                sessionKey = pager.pagerId,
                entryOwnerId = pager.pagerId,
                panelDetent = RaftMobileWebPanelDetent.Fullscreen,
                onCurrentUrlChanged = {},
                onCollapse = null,
                onClose = ::closePage,
                onDropCollapsedOnProcessGone = null,
                bottomInsetDp = maxOf(imeBottomDp, bottomSafeArea),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
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
