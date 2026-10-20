package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import build.raft.app.base.Utils
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

internal const val RAFT_IN_APP_BROWSER_PAGE = "compose_in_app_browser"
internal const val RAFT_IN_APP_BROWSER_URL_KEY = "url"

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
    val controller = rememberRaftWebViewController()
    val externalNavigator =
        rememberRaftLinkNavigator(
            pager = pager,
            bridge = bridge,
            routeScope = RaftInAppBrowserRouteScope.Unscoped,
        )
    var navigationState by
        remember(launchUrl) {
            mutableStateOf(
                RaftWebViewNavigationState(
                    url = launchUrl.orEmpty(),
                    isLoading = launchUrl != null,
                )
            )
        }
    var loadError by remember(launchUrl) { mutableStateOf<String?>(null) }
    var webViewAttachReady by remember(launchUrl) { mutableStateOf(false) }
    LaunchedEffect(launchUrl) {
        if (launchUrl == null) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        webViewAttachReady = true
    }

    fun closePage() {
        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    fun goBackOrClose() {
        if (navigationState.canGoBack) controller.goBack() else closePage()
    }

    fun openInSystemBrowser() {
        val target = navigationState.url.takeIf { it.isNotBlank() } ?: launchUrl ?: return
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
            title = navigationState.title.ifBlank { stringResource(MR.strings.in_app_browser_title) },
            subtitle =
                (navigationState.url.takeIf { it.isNotBlank() } ?: launchUrl)
                    ?.let(::raftInAppBrowserDisplayUrl),
            iconName = RaftLucideIconName.Globe,
            backgroundColor = RaftColor.SurfaceWhite,
            leadingContent = {
                RaftTopPageHeaderAction(
                    icon = RaftLucideIconName.ArrowLeft,
                    onClick = ::goBackOrClose,
                )
            },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftBrutalIconButton(
                        iconName = RaftLucideIconName.ChevronRight,
                        onClick = controller::goForward,
                        contentDescription = stringResource(MR.strings.in_app_browser_forward),
                        enabled = navigationState.canGoForward,
                        size = 26.dp,
                        shadowOffset = 1.dp,
                    )
                    RaftBrutalIconButton(
                        iconName = if (navigationState.isLoading) RaftLucideIconName.X else RaftLucideIconName.RefreshCw,
                        onClick = {
                            if (navigationState.isLoading) controller.stop() else controller.reload()
                        },
                        contentDescription =
                            if (navigationState.isLoading) {
                                stringResource(MR.strings.common_stop)
                            } else {
                                stringResource(MR.strings.in_app_browser_reload)
                            },
                        size = 26.dp,
                        shadowOffset = 1.dp,
                    )
                    RaftBrutalIconButton(
                        iconName = RaftLucideIconName.ExternalLink,
                        onClick = ::openInSystemBrowser,
                        contentDescription = stringResource(MR.strings.in_app_browser_open_system),
                        enabled = launchUrl != null,
                        size = 26.dp,
                        shadowOffset = 1.dp,
                    )
                }
            },
        )
        if (navigationState.isLoading) {
            RaftInAppBrowserProgress(navigationState.progress)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(RaftColor.SurfaceWhite)) {
            when {
                launchUrl == null -> {
                    RaftInAppBrowserError(
                        message = stringResource(MR.strings.in_app_browser_invalid_link),
                        onRetry = null,
                        onOpenSystem = null,
                        onClose = ::closePage,
                    )
                }
                webViewAttachReady -> {
                    RaftWebView(
                        url = launchUrl,
                        modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite),
                        controller = controller,
                        allowScripts = true,
                        allowNavigation = true,
                        allowedNavigationSchemes = setOf("http", "https"),
                        domStorageEnabled = true,
                        cacheEnabled = true,
                        ephemeral = false,
                        injectedJavaScript = "",
                        onLoadSuccess = { loadError = null },
                        onLoadFailure = { reason ->
                            loadError = reason.ifBlank { "webview_load_failed" }
                            bridge.logWarn("in_app_browser_load_failed")
                        },
                        onNavigationBlocked = { blockedUrl ->
                            externalNavigator.open(blockedUrl, RaftLinkOpenIntent.ForceExternal)
                        },
                        onNavigationStateChange = { nextState ->
                            navigationState = nextState
                            if (nextState.isLoading) loadError = null
                        },
                    )
                    if (loadError != null) {
                        RaftInAppBrowserError(
                            message = stringResource(MR.strings.in_app_browser_load_error),
                            onRetry = {
                                loadError = null
                                controller.reload()
                            },
                            onOpenSystem = ::openInSystemBrowser,
                            onClose = null,
                        )
                    }
                }
                else -> {
                    RaftCenteredLoading(stringResource(MR.strings.common_loading))
                }
            }
        }
    }
}

@Composable
private fun RaftInAppBrowserProgress(progress: Int) {
    val percent = progress.coerceIn(0, 100)
    if (percent <= 0) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = RaftColor.AccentCyan,
            trackColor = RaftColor.SurfaceCream,
        )
        return
    }
    Row(modifier = Modifier.fillMaxWidth().height(4.dp).background(RaftColor.SurfaceCream)) {
        Box(
            modifier =
                Modifier
                    .weight(percent.toFloat())
                    .fillMaxSize()
                    .background(RaftColor.AccentCyan)
        )
        if (percent < 100) {
            Box(modifier = Modifier.weight((100 - percent).toFloat()).fillMaxSize())
        }
    }
}

@Composable
private fun RaftInAppBrowserError(
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
            RaftBrutalActionButton(
                label = stringResource(MR.strings.common_retry),
                onClick = it,
                leadingIcon = RaftLucideIconName.RefreshCw,
                backgroundColor = RaftColor.BrandYellow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        onOpenSystem?.let {
            RaftBrutalActionButton(
                label = stringResource(MR.strings.in_app_browser_open_system),
                onClick = it,
                leadingIcon = RaftLucideIconName.ExternalLink,
                backgroundColor = RaftColor.SurfaceWhite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        onClose?.let {
            RaftBrutalActionButton(
                label = stringResource(MR.strings.common_close),
                onClick = it,
                leadingIcon = RaftLucideIconName.X,
                backgroundColor = RaftColor.SurfaceWhite,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
