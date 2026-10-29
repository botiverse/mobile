package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.artifactIdOrNull
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.answersFor
import build.raft.app.markdown.lucideIcon
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.resources.*
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val RAFT_ARTIFACT_VIEWER_PAGE = "compose_artifact_viewer"
private const val VIEWER_ARTIFACT_ID = "artifactId"
private const val VIEWER_VERSION = "artifactVersion"

@Page(RAFT_ARTIFACT_VIEWER_PAGE, supportInLocal = true)
internal class ComposeRaftArtifactViewerPage : AuthenticatedProductPager(AuthenticatedProductRoute.ArtifactViewer) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { RaftArtifactViewerScreen() }
    }
}

/**
 * Identity only (the Route / PageData rule): the Artifact and its pinned version. The header the
 * page paints at once comes from [RaftArtifactViewerMemory], where the opener put its card, read
 * only in the same scope.
 */
internal fun raftArtifactViewerPageData(target: ArtifactLinkTarget): JSONObject =
    JSONObject().apply {
        put("pageName", RAFT_ARTIFACT_VIEWER_PAGE)
        put(VIEWER_ARTIFACT_ID, target.id)
        target.version?.let { put(VIEWER_VERSION, it) }
    }

internal fun JSONObject.raftArtifactViewerTargetOrNull(): ArtifactLinkTarget? {
    // `pt_…` or a short id, as the link wrote it.
    val id = artifactIdOrNull(optString(VIEWER_ARTIFACT_ID).trim()) ?: return null
    val version = optInt(VIEWER_VERSION, 0).takeIf { it > 0 }
    return ArtifactLinkTarget(id, version)
}

/** What a viewer page's (or a prewarm's) WebView is configured with. */
internal fun raftArtifactViewerWebViewConfig(
    url: String,
    scope: RaftArtifactViewerScope,
    injectedJavaScript: String,
): RaftWebViewConfig =
    RaftWebViewConfig(
        url = url,
        allowScripts = true,
        allowNavigation = true,
        domStorageEnabled = true,
        useWideViewPort = false,
        cacheEnabled = true,
        ephemeral = false,
        injectedJavaScript = injectedJavaScript,
        allowedNavigationSchemes = setOf("http", "https"),
        // Rule 3: no bridge into the app, for the page or the content frame.
        hostEventsEnabled = false,
        // Rule 2: top-level documents stay on the web origin.
        mainFrameOrigin = scope.webOrigin,
        keepAliveKey = scope.keepAliveKey,
    )

/** The session writes (rule 1: guarded to a top-level document of the web origin). */
internal fun raftArtifactViewerSessionScript(
    scope: RaftArtifactViewerScope,
    session: StoredAuthSession?,
    binding: SettingsWebViewHostAuthBinding?,
): String = raftArtifactViewerGuardedScript(scope.webOrigin, settingsWebViewAuthStorageJavaScript(session, binding))

/** The viewer's shell context from any page: stores, scope, environment. */
private class RaftArtifactViewerHost(
    val pager: Pager,
    val bridge: BridgeModule,
    val sharedStores: RaftSharedStoreProvider,
    val storeScope: RaftShellStoreScope,
) {
    val webBaseUrl: String get() = build.raft.app.sharedSettingsResolvedWebBaseUrl(standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore))

    fun scope(): RaftArtifactViewerScope? =
        RaftArtifactViewerScope.of(webBaseUrl, sharedStores.currentAuthenticatedRouteOwner(storeScope))
}

@Composable
private fun rememberRaftArtifactViewerHost(): RaftArtifactViewerHost? {
    val pager = LocalActivity.current.getPager() as? Pager ?: return null
    // Null where there is no live page (fixtures, screenshots): no viewer, no prewarm.
    return remember(pager.pagerId, pager.pageData.params.toString()) {
        runCatching {
            val bridge = Utils.bridgeModule(pager.pagerId)
            RaftArtifactViewerHost(
                pager = pager,
                bridge = bridge,
                sharedStores = RaftSharedStoreRegistry.get(bridge),
                storeScope = pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId),
            )
        }.getOrNull()
    }
}

/** Opens Artifacts in the in-app viewer (the chat card's Open, an Activity row). */
internal class RaftArtifactOpener(
    private val openTarget: (target: ArtifactLinkTarget, header: RaftArtifactViewerHeader?, source: String) -> Unit,
) {
    fun open(target: ArtifactLinkTarget, header: RaftArtifactViewerHeader?, source: String) = openTarget(target, header, source)
}

/**
 * The opener for the current page. [fallback] (the web URL in the browser, as before) is used only
 * when no page can be opened from here.
 */
@Composable
internal fun rememberRaftArtifactOpener(fallback: (ArtifactLinkTarget) -> Unit): RaftArtifactOpener {
    val host = rememberRaftArtifactViewerHost()
    val coroutineScope = rememberCoroutineScope()
    val currentFallback by rememberUpdatedState(fallback)
    return remember(host, coroutineScope) {
        RaftArtifactOpener { target, header, source ->
            RaftArtifactOpenTrace.tap(source, target.id)
            if (host == null) {
                currentFallback(target)
                return@RaftArtifactOpener
            }
            val scope = host.scope()
            header?.let { RaftArtifactViewerMemory.cache.remember(scope, it) }
            // Start the web viewer's reads now, in a kept (booted) document, while the page opens.
            if (scope != null && RaftArtifactViewerMemory.cache.keptDocumentUsable(scope) &&
                !RaftArtifactViewerMemory.cache.keptDocumentShows(scope, target)
            ) {
                coroutineScope.launch {
                    val session =
                        runCatchingPreservingCancellation { host.sharedStores.authStore(host.storeScope).readSession() }.getOrNull()
                    if (session == null || session.user.id.trim() != scope.accountId) return@launch
                    val script =
                        raftArtifactViewerNavigateScript(
                            scope.webOrigin,
                            settingsWebViewAuthStorageJavaScript(session, null),
                            target,
                        )
                    if (raftWebViewEvaluateInKept(scope.keepAliveKey, scope.webOrigin, script)) {
                        RaftArtifactViewerMemory.cache.rememberKeptDocument(scope, target, raftArtifactViewerEmbeddedPageUrl(host.webBaseUrl, target))
                        RaftArtifactOpenTrace.mark("viewer_nav_started", "where=tap")
                    }
                }
            }
            val opened =
                host.pager.openRaftPage(
                    pageName = RAFT_ARTIFACT_VIEWER_PAGE,
                    pageData = raftArtifactViewerPageData(target),
                )
            if (!opened) currentFallback(target)
        }
    }
}

/**
 * Prewarm (an Artifact card or Activity row on screen): after the page settles, a WebView boots
 * the web app on the viewer's route with no Artifact, signed in, and is kept for the viewer.
 * Nothing is read or marked read. No-op without keep-alive (iOS, desktop) or a known subject.
 */
@Composable
internal fun RaftArtifactViewerPrewarm(enabled: Boolean) {
    if (!enabled) return
    val host = rememberRaftArtifactViewerHost() ?: return
    val authStore = remember(host) { host.sharedStores.authStore(host.storeScope) }
    val snapshot = rememberSettingsWebViewAuthSnapshot(authStore, host.sharedStores.authStore)
    val controller = rememberRaftWebViewController()
    val binding = rememberSettingsWebViewHostAuthBinding(snapshot, controller)
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS)
        settled = true
    }
    val scope = host.scope()
    val config =
        remember(settled, scope, snapshot.session?.accessToken, binding?.webViewGeneration) {
            val session = snapshot.session
            if (!settled || scope == null || session == null || session.user.id.trim() != scope.accountId) {
                null
            } else if (raftWebViewKeptDocument(scope.keepAliveKey) != null) {
                null
            } else {
                RaftArtifactViewerMemory.cache.markKeptWarm(scope)
                raftArtifactViewerWebViewConfig(
                    url = raftArtifactViewerWarmUrl(host.webBaseUrl),
                    scope = scope,
                    injectedJavaScript = raftArtifactViewerSessionScript(scope, session, binding),
                )
            }
        }
    RaftWebViewPrewarmEffect(config)
}

private const val RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS = 1_200L

private sealed interface RaftArtifactViewerBodyState {
    data object Viewing : RaftArtifactViewerBodyState
    data object Unavailable : RaftArtifactViewerBodyState

    /** The page navigated off this Artifact ([RaftArtifactViewerNavigation]): the viewer closes. */
    data class Left(val navigation: RaftArtifactViewerNavigation) : RaftArtifactViewerBodyState
}

private fun RaftArtifactViewerNavigation.logToken(): String =
    when (this) {
        is RaftArtifactViewerNavigation.OpenMessage -> "open_message"
        else -> "close"
    }

@Composable
private fun RaftArtifactViewerScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val host = rememberRaftArtifactViewerHost() ?: return
    val pager = host.pager
    val params = pager.pageData.params
    val target = remember(params.toString()) { params.raftArtifactViewerTargetOrNull() }
    LaunchedEffect(Unit) { RaftArtifactOpenTrace.mark("page_shown") }
    fun closePage() = pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    RaftBackHandler(enabled = true, label = "artifact_viewer", blocksBackSwipe = false, onBack = ::closePage)
    if (target == null) {
        RaftArtifactViewerFrame(title = stringResource(Res.string.artifacts_app_name), subtitle = null, kind = null, onBack = ::closePage, onOpenInBrowser = null) {
            RaftArtifactViewerUnavailable(onClose = ::closePage)
        }
        return
    }
    val scope = remember(params.toString()) { host.scope() }
    val webBaseUrl = remember(params.toString()) { host.webBaseUrl }
    // Open in browser: the page as the web shows it. The WebView: the same page under this title bar.
    val pageUrl = remember(webBaseUrl, target) { raftArtifactViewerPageUrl(webBaseUrl, target) }
    val embeddedPageUrl = remember(webBaseUrl, target) { raftArtifactViewerEmbeddedPageUrl(webBaseUrl, target) }
    // Paint the header at once: the opener's card (put in the cache on tap), else the last one seen,
    // in this scope only.
    var header by remember(params.toString()) { mutableStateOf(RaftArtifactViewerMemory.cache.header(scope, target.canonicalId)) }
    LaunchedEffect(Unit) { if (header != null) RaftArtifactOpenTrace.mark("header_painted", "from=card_or_cache") }
    var bodyState by remember(params.toString()) { mutableStateOf<RaftArtifactViewerBodyState>(RaftArtifactViewerBodyState.Viewing) }
    // The version the page shows, from its address (a version picked in the page's ⋯, Back to
    // Latest): the page draws no version of its own under the embed contract, this header does.
    var addressed by remember(params.toString()) { mutableStateOf<RaftArtifactViewerNavigation.Stay?>(null) }
    var latestVersion by remember(params.toString()) { mutableStateOf<Int?>(null) }

    // Revalidate (never trusting the cached header for access): a fresh summary; unavailable (403/404)
    // replaces the screen and drops the cached copy.
    LaunchedEffect(scope, target) {
        val serverId = scope?.serverId ?: return@LaunchedEffect
        val store = host.sharedStores.artifactSummaryStore
        store.refresh(serverId, target)
        val answer = store.state.first { store.isFresh(serverId, target) }.answersFor(serverId, listOf(target))[target.key]
        when (answer) {
            is ArtifactSummaryAnswer.Ok -> {
                val fresh = RaftArtifactViewerHeader.of(answer.summary, target)
                header = fresh
                latestVersion = answer.summary.latestVersion.takeIf { it > 0 }
                RaftArtifactViewerMemory.cache.remember(scope, fresh)
            }
            ArtifactSummaryAnswer.Unavailable -> bodyState = RaftArtifactViewerBodyState.Unavailable
            null -> Unit
        }
    }
    LaunchedEffect(bodyState) {
        // After the WebView left the screen (and was parked), so the eviction destroys it too.
        if (bodyState == RaftArtifactViewerBodyState.Unavailable) {
            RaftArtifactViewerMemory.cache.evict(scope, target.canonicalId)
            raftWebViewKeepAliveClear()
            host.bridge.log("artifact_viewer_unavailable action=replace_and_evict")
        }
        // The page went somewhere that is not this Artifact: the viewer closes (after the WebView
        // left the screen, so the document, which no longer shows an Artifact, is not kept), and its
        // original message opens natively, as a message link from outside the app does.
        val leaving = bodyState as? RaftArtifactViewerBodyState.Left ?: return@LaunchedEffect
        RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
        host.bridge.log("artifact_viewer_left action=${leaving.navigation.logToken()}")
        closePage()
        when (val navigation = leaving.navigation) {
            is RaftArtifactViewerNavigation.OpenMessage -> RaftMessageLinkIngress.accept(navigation.url, "artifact_viewer", webBaseUrl)
            else -> Unit
        }
    }

    val untitled = stringResource(Res.string.artifacts_activity_untitled)
    val shownVersion = addressed?.let { it.version ?: latestVersion } ?: header?.version
    val versionLabel = shownVersion?.let { stringResource(Res.string.artifacts_card_version, it) }
    RaftArtifactViewerFrame(
        title = header?.title?.ifBlank { untitled } ?: untitled,
        subtitle = versionLabel,
        kind = header?.kind,
        onBack = ::closePage,
        onOpenInBrowser = { host.bridge.openUrl(pageUrl, inBrowser = true) },
    ) {
        when (bodyState) {
            RaftArtifactViewerBodyState.Unavailable -> RaftArtifactViewerUnavailable(onClose = ::closePage)
            is RaftArtifactViewerBodyState.Left -> Unit
            RaftArtifactViewerBodyState.Viewing ->
                if (scope == null) {
                    // An unknown subject reuses nothing and keeps nothing: the page in the browser, as before.
                    LaunchedEffect(Unit) {
                        host.bridge.openUrl(pageUrl, inBrowser = true)
                        closePage()
                    }
                } else {
                    RaftArtifactViewerWebBody(
                        host = host,
                        scope = scope,
                        target = target,
                        pageUrl = embeddedPageUrl,
                        onStay = { addressed = it },
                        onLeave = { bodyState = RaftArtifactViewerBodyState.Left(it) },
                    )
                }
        }
    }
}

@Composable
private fun RaftArtifactViewerWebBody(
    host: RaftArtifactViewerHost,
    scope: RaftArtifactViewerScope,
    target: ArtifactLinkTarget,
    pageUrl: String,
    onStay: (RaftArtifactViewerNavigation.Stay) -> Unit,
    onLeave: (RaftArtifactViewerNavigation) -> Unit,
) {
    val authStore = remember(host) { host.sharedStores.authStore(host.storeScope) }
    val snapshot = rememberSettingsWebViewAuthSnapshot(authStore, host.sharedStores.authStore)
    val controller = rememberRaftWebViewController()
    val binding = rememberSettingsWebViewHostAuthBinding(snapshot, controller)
    val session = snapshot.session?.takeIf { it.user.id.trim() == scope.accountId }
    // A kept document of this scope is shown as it is (the same Artifact: at once; another: the web
    // app routes to it); otherwise the viewer's page loads.
    val kept = remember(scope) { raftWebViewKeptDocument(scope.keepAliveKey)?.takeIf { RaftArtifactViewerMemory.cache.keptDocumentUsable(scope) } }
    // A new document needs the session before it loads; a kept one already has it, so it is
    // attached in the first frame and gets the session's script once the session is read.
    if (kept == null && session == null) {
        RaftCenteredLoading(stringResource(Res.string.common_loading))
        return
    }
    val src = kept?.src ?: pageUrl
    val showsTarget = remember(scope, target) { kept != null && RaftArtifactViewerMemory.cache.keptDocumentShows(scope, target) }
    var visible by remember { mutableStateOf(showsTarget) }
    var documentReady by remember { mutableStateOf(false) }
    var navigated by remember { mutableStateOf(kept == null || showsTarget) }
    // Every top-level address (a load, or the web app's own history change). Only once the page has
    // shown this Artifact: before that a kept document is still on the warm route or the last one.
    var armed by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(false) }
    val currentOnStay by rememberUpdatedState(onStay)
    val currentOnLeave by rememberUpdatedState(onLeave)
    fun onAddress(url: String) {
        if (left) return
        when (val navigation = raftArtifactViewerNavigation(url, scope.webOrigin, target.id)) {
            is RaftArtifactViewerNavigation.Stay -> {
                armed = true
                currentOnStay(navigation)
            }
            // Off the web origin never loads here: the origin lock blocks it (onNavigationBlocked).
            RaftArtifactViewerNavigation.Ignore, is RaftArtifactViewerNavigation.SystemBrowser -> Unit
            else ->
                if (armed) {
                    left = true
                    currentOnLeave(navigation)
                }
        }
    }
    LaunchedEffect(Unit) { kept?.currentUrl?.let(::onAddress) }
    LaunchedEffect(Unit) {
        RaftArtifactOpenTrace.mark("webview_attach", "kept=${kept != null} showsTarget=$showsTarget")
        if (kept == null) RaftArtifactViewerMemory.cache.rememberKeptDocument(scope, target, pageUrl)
    }
    val injected =
        remember(scope, session?.accessToken, binding?.webViewGeneration) {
            session?.let { raftArtifactViewerSessionScript(scope, it, binding) }.orEmpty()
        }
    LaunchedEffect(documentReady, navigated, session != null) {
        if (!documentReady || navigated || session == null) return@LaunchedEffect
        val script = raftArtifactViewerNavigateScript(scope.webOrigin, settingsWebViewAuthStorageJavaScript(session, binding), target)
        if (controller.evaluateJavaScript(script)) {
            navigated = true
            RaftArtifactViewerMemory.cache.rememberKeptDocument(scope, target, pageUrl)
            RaftArtifactOpenTrace.mark("viewer_nav_started", "where=page")
        }
    }
    LaunchedEffect(navigated) { if (navigated && kept != null) visible = true }
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftSandboxWebView(
            url = src,
            modifier = Modifier.fillMaxSize().alpha(if (visible) 1f else 0f),
            allowScripts = true,
            allowNavigation = true,
            domStorageEnabled = true,
            cacheEnabled = true,
            ephemeral = false,
            injectedJavaScript = injected,
            hostEventsEnabled = false,
            controller = controller,
            allowedNavigationSchemes = setOf("http", "https"),
            mainFrameOrigin = scope.webOrigin,
            keepAliveKey = scope.keepAliveKey,
            onLoadCommit = {
                visible = true
                RaftArtifactOpenTrace.mark("webview_commit")
            },
            onLoadSuccess = {
                visible = true
                documentReady = true
                RaftArtifactOpenTrace.mark("webview_page_finished")
            },
            onUrlChange = ::onAddress,
            onNavigationStateChange = { state ->
                if (state.isLoading && state.progress == 0) RaftArtifactOpenTrace.mark("webview_page_started")
                // iOS sees the web app's own history changes only here (its URL poll).
                if (state.url.isNotBlank()) onAddress(state.url)
            },
            onNavigationBlocked = { blocked ->
                // Rule 2: off the web origin never loads here; an http(s) page opens in the browser.
                if (raftArtifactViewerBlockedNavigationTarget(blocked, scope.webOrigin) == RaftArtifactViewerBlockedTarget.SystemBrowser) {
                    host.bridge.openUrl(blocked, inBrowser = true)
                }
            },
        )
        SettingsWebViewAccessTokenSyncEffect(
            snapshot = snapshot,
            documentReady = documentReady,
            controller = controller,
            binding = binding,
            surface = SettingsWebViewSurface.ArtifactViewer,
            logger = host.bridge::log,
        )
        if (!visible) RaftCenteredLoading(stringResource(Res.string.common_loading))
    }
}

@Composable
internal fun RaftArtifactViewerFrame(
    title: String,
    subtitle: String?,
    kind: ArtifactKind?,
    onBack: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
    /** Screenshot fixtures only (no host window there). */
    topSafeAreaOverride: androidx.compose.ui.unit.Dp? = null,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        RaftTopPageHeader(
            title = title,
            subtitle = subtitle,
            iconName = kind?.lucideIcon() ?: RaftLucideIconName.FileText,
            backgroundColor = RaftColor.SurfaceWhite,
            onBack = onBack,
            topSafeAreaOverride = topSafeAreaOverride,
            trailingContent = {
                if (onOpenInBrowser != null) {
                    Box {
                        RaftDesignSystemTheme {
                            RaftIconButton(
                                onClick = { menuOpen = true },
                                tooltip = stringResource(Res.string.artifacts_viewer_more),
                                variant = RaftButtonVariant.Ghost,
                            ) {
                                RaftSlotIcon(RaftLucideIconName.Ellipsis)
                            }
                            RaftDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, minWidth = 200.dp) {
                                RaftMenuItem(
                                    text = stringResource(Res.string.artifacts_viewer_open_in_browser),
                                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowUpRight) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenInBrowser()
                                    },
                                )
                            }
                        }
                    }
                }
            },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
internal fun RaftArtifactViewerUnavailable(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.artifacts_viewer_unavailable_title),
            style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
            color = RaftColor.TextBlack,
        )
        Text(
            stringResource(Res.string.artifacts_viewer_unavailable_body),
            style = RaftType.Body.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        RaftButton(
            text = stringResource(Res.string.common_close),
            onClick = onClose,
            size = RaftButtonSize.Lg,
            leadingIcon = { RaftSlotIcon(RaftLucideIconName.X) },
        )
    }
}
