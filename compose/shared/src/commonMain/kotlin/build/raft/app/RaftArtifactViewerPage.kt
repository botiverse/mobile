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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.ui.raftImeBottomPx
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ArtifactViewerDesktopModePreferenceRepository
import build.raft.app.data.ARTIFACT_CONTENT_DOMAIN
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.artifactIdOrNull
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.answersFor
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
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
    /** Desktop Mode: the page laid out at a desktop width and fitted ([raftArtifactViewerInjectedScript]). */
    desktopMode: Boolean = false,
): RaftWebViewConfig =
    RaftWebViewConfig(
        url = url,
        allowScripts = true,
        allowNavigation = true,
        domStorageEnabled = true,
        useWideViewPort = desktopMode,
        cacheEnabled = true,
        ephemeral = false,
        injectedJavaScript = injectedJavaScript,
        allowedNavigationSchemes = setOf("http", "https"),
        // Rule 3: no bridge into the app, for the page or the content frame.
        hostEventsEnabled = false,
        // Rule 2: top-level documents stay on the web origin.
        mainFrameOrigin = scope.webOrigin,
        frameHosts = setOf(ARTIFACT_CONTENT_DOMAIN),
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
    /** [LocalRaftArtifactOpenStoreScope]: the scope the viewer page is opened with, if given. */
    val openStoreScope: RaftShellStoreScope? = null,
) {
    val webBaseUrl: String get() = build.raft.app.sharedSettingsResolvedWebBaseUrl(standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore))

    fun scope(): RaftArtifactViewerScope? =
        RaftArtifactViewerScope.of(webBaseUrl, sharedStores.currentAuthenticatedRouteOwner(storeScope))
}

/**
 * The shell scope an opener here opens the viewer with, for a surface whose page data carries none.
 * An app-shell tab (Activity) is composed in a child page whose data is only its tab, and the
 * Compose 版 shell page has none either: [openRaftPage] rejected the viewer there
 * (`missing_source_scope`) and the Artifact opened in the browser instead.
 */
internal val LocalRaftArtifactOpenStoreScope = compositionLocalOf<RaftShellStoreScope?> { null }

/**
 * True for the viewer in the wide layout's Artifact column (#189 step 4, RaftWidePanes.kt `RaftArtifactColumnCell`):
 * the column owns Back (its own handler, on only while the column is on screen), the viewer keeps the App's bar so its
 * head has the column's Close, and an Artifact the page navigates to opens in the same column.
 */
internal val LocalRaftArtifactViewerInColumn = compositionLocalOf { false }

/**
 * Opens the viewer page for [target] from [this] page, with [storeScope] when given (else the
 * page's own scope). False when no page can be opened from here (no scope): the caller falls back.
 */
internal fun Pager.openRaftArtifactViewerPage(target: ArtifactLinkTarget, storeScope: RaftShellStoreScope?): Boolean =
    openRaftPage(
        pageName = RAFT_ARTIFACT_VIEWER_PAGE,
        pageData = raftArtifactViewerPageData(target),
        storeScopeOverride = storeScope,
    )

@Composable
private fun rememberRaftArtifactViewerHost(openStoreScope: RaftShellStoreScope? = null): RaftArtifactViewerHost? {
    val pager = LocalActivity.current.getPager() as? Pager ?: return null
    // Null where there is no live page (fixtures, screenshots): no viewer, no prewarm.
    return remember(pager.pagerId, pager.pageData.params.toString(), openStoreScope) {
        runCatching {
            val bridge = Utils.bridgeModule(pager.pagerId)
            RaftArtifactViewerHost(
                pager = pager,
                bridge = bridge,
                sharedStores = RaftSharedStoreRegistry.get(bridge),
                storeScope = openStoreScope ?: pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId),
                openStoreScope = openStoreScope,
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
    val host = rememberRaftArtifactViewerHost(LocalRaftArtifactOpenStoreScope.current)
    val coroutineScope = rememberCoroutineScope()
    val currentFallback by rememberUpdatedState(fallback)
    return remember(host, coroutineScope) {
        RaftArtifactOpener { target, header, source ->
            RaftArtifactOpenTrace.tap(source, target.id)
            if (host == null) {
                RaftArtifactOpenTrace.mark("browser_fallback", "reason=no_host")
                currentFallback(target)
                return@RaftArtifactOpener
            }
            val scope = host.scope()
            header?.let { RaftArtifactViewerMemory.cache.remember(scope, it) }
            // Start the web viewer's reads now, in a kept (booted) document, while the page opens.
            if (scope != null && !RaftArtifactViewerMemory.cache.keptDocumentShows(scope, target)) {
                coroutineScope.launch {
                    val session =
                        runCatchingPreservingCancellation { host.sharedStores.authStore(host.storeScope).readSession() }.getOrNull()
                    if (session == null || session.user.id.trim() != scope.accountId) return@launch
                    // Only with the session it was parked with (else the page loads a new one).
                    if (!RaftArtifactViewerMemory.cache.keptDocumentUsable(scope, session.accessToken)) return@launch
                    val script =
                        raftArtifactViewerNavigateScript(
                            scope.webOrigin,
                            settingsWebViewAuthStorageJavaScript(session, null),
                            target,
                        )
                    if (raftWebViewEvaluateInKept(scope.keepAliveKey, scope.webOrigin, script)) {
                        RaftArtifactViewerMemory.cache.rememberKeptDocument(
                            scope,
                            target,
                            raftArtifactViewerEmbeddedPageUrl(host.webBaseUrl, target),
                            session.accessToken,
                        )
                        RaftArtifactOpenTrace.mark("viewer_nav_started", "where=tap")
                    }
                }
            }
            val opened = host.pager.openRaftArtifactViewerPage(target, host.openStoreScope)
            if (!opened) {
                RaftArtifactOpenTrace.mark("browser_fallback", "reason=no_page_scope")
                currentFallback(target)
            }
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
    val host = rememberRaftArtifactViewerHost(LocalRaftArtifactOpenStoreScope.current) ?: return
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
    // Booted as the viewer will show it, so the kept document is not reloaded when it opens.
    val desktopMode = raftArtifactViewerOffersDesktopModeHere() && remember { raftArtifactViewerDesktopModePreference()?.read() == true }
    val config =
        remember(settled, scope, snapshot.session?.accessToken, binding?.webViewGeneration, desktopMode) {
            val session = snapshot.session
            if (!settled || scope == null || session == null || session.user.id.trim() != scope.accountId) {
                null
            } else if (raftWebViewKeptDocument(scope.keepAliveKey) != null) {
                null
            } else {
                RaftArtifactViewerMemory.cache.markKeptWarm(scope, session.accessToken)
                raftArtifactViewerWebViewConfig(
                    url = raftArtifactViewerWarmUrl(host.webBaseUrl),
                    scope = scope,
                    injectedJavaScript =
                        raftArtifactViewerInjectedScript(scope.webOrigin, raftArtifactViewerSessionScript(scope, session, binding), desktopMode),
                    desktopMode = desktopMode,
                )
            }
        }
    RaftWebViewPrewarmEffect(config)
}

private const val RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS = 1_200L

/** After this long without the document shown, the viewer logs where its load stands. */
internal const val RAFT_ARTIFACT_VIEWER_SLOW_LOAD_MS = 10_000L

/** The viewer's open line: the Artifact id (as the link wrote it), its pinned version, the web host only. */
internal fun raftArtifactViewerOpenLogLine(target: ArtifactLinkTarget, scopeKnown: Boolean, webBaseUrl: String): String {
    val id = target.id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(48)
    val host = raftWebOriginOf(webBaseUrl)?.substringAfter("://") ?: "<none>"
    return "artifact_viewer_open artifact=$id version=${target.version ?: "latest"} scope=${if (scopeKnown) "known" else "unknown"} webHost=$host"
}

internal fun raftArtifactViewerSummaryLogToken(answer: ArtifactSummaryAnswer?): String =
    when (answer) {
        is ArtifactSummaryAnswer.Ok -> "ok"
        ArtifactSummaryAnswer.Unavailable -> "unavailable"
        is ArtifactSummaryAnswer.Deleted -> "deleted"
        ArtifactSummaryAnswer.Failed -> "failed"
        null -> "none"
    }

/** The device's Desktop Mode choice; null where no app-scope store is installed (fixtures). */
private fun raftArtifactViewerDesktopModePreference(): ArtifactViewerDesktopModePreferenceRepository? =
    runCatching { ArtifactViewerDesktopModePreferenceRepository(build.raft.app.data.raftSharedPreferenceStore()) }.getOrNull()

/** [raftArtifactViewerOffersDesktopMode] for this window (touch, narrower than a desktop). */
@Composable
private fun raftArtifactViewerOffersDesktopModeHere(): Boolean {
    val windowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
    return raftArtifactViewerOffersDesktopMode(raftPlatformHasFinePointer(), windowWidthDp)
}

private sealed interface RaftArtifactViewerBodyState {
    data object Viewing : RaftArtifactViewerBodyState
    data object Unavailable : RaftArtifactViewerBodyState

    /**
     * The page navigated off this Artifact ([RaftArtifactViewerNavigation]) to [to]
     * ([raftArtifactViewerLogDestination]): the viewer closes.
     */
    data class Left(val navigation: RaftArtifactViewerNavigation, val to: String) : RaftArtifactViewerBodyState

    /**
     * A reused kept document wandered off on its own ([RaftArtifactViewerLeaveAction.ReloadFresh]):
     * once its WebView left the screen, it is dropped and the Artifact loads in a new one.
     */
    data class ReloadingFresh(val to: String) : RaftArtifactViewerBodyState
}

private fun RaftArtifactViewerNavigation.logToken(): String =
    when (this) {
        is RaftArtifactViewerNavigation.OpenMessage -> "open_message"
        is RaftArtifactViewerNavigation.OpenArtifact -> "open_artifact"
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
    // In the wide layout's Artifact column the column's Back closes it (only while it is on screen): this one is off.
    val inColumn = LocalRaftArtifactViewerInColumn.current
    RaftBackHandler(enabled = !inColumn, label = "artifact_viewer", blocksBackSwipe = false, onBack = ::closePage)
    // Desktop Mode: this device's choice, offered on a touch screen narrower than a desktop.
    val desktopModePreference = remember { raftArtifactViewerDesktopModePreference() }
    var desktopModeChosen by remember { mutableStateOf(desktopModePreference?.read() == true) }
    val desktopModeOffered = desktopModePreference != null && raftArtifactViewerOffersDesktopModeHere()
    val desktopMode = desktopModeOffered && desktopModeChosen
    if (target == null) {
        RaftArtifactViewerFrame(title = stringResource(Res.string.artifacts_app_name), onBack = ::closePage) {
            RaftArtifactViewerUnavailable(onClose = ::closePage)
        }
        return
    }
    val scope = remember(params.toString()) { host.scope() }
    val webBaseUrl = remember(params.toString()) { host.webBaseUrl }
    LaunchedEffect(Unit) {
        raftLogInfo(
            RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG,
            raftArtifactViewerOpenLogLine(target, scopeKnown = scope != null, webBaseUrl = webBaseUrl),
        )
    }
    // Open in browser: the page as the web shows it. The WebView: the same page under this title bar.
    val pageUrl = remember(webBaseUrl, target) { raftArtifactViewerPageUrl(webBaseUrl, target) }
    // Desktop Mode's state for the page's own ⋯ (`hostDesktop`; null: not offered here).
    val hostDesktop: Boolean? = desktopMode.takeIf { desktopModeOffered }
    // With Desktop Mode's state at open; the App keeps it current in the address afterwards.
    val embeddedPageUrl =
        remember(webBaseUrl, target) { raftArtifactViewerWithHostDesktop(raftArtifactViewerEmbeddedPageUrl(webBaseUrl, target), hostDesktop) }
    // Paint the header at once: the opener's card (put in the cache on tap), else the last one seen,
    // in this scope only.
    var header by remember(params.toString()) { mutableStateOf(RaftArtifactViewerMemory.cache.header(scope, target.canonicalId)) }
    LaunchedEffect(Unit) { if (header != null) RaftArtifactOpenTrace.mark("header_painted", "from=card_or_cache") }
    var bodyState by remember(params.toString()) { mutableStateOf<RaftArtifactViewerBodyState>(RaftArtifactViewerBodyState.Viewing) }
    // The version the page shows, from its address (a version picked in the page's ⋯, Back to
    // Latest): Open in Browser opens that one.
    var addressed by remember(params.toString()) { mutableStateOf<RaftArtifactViewerNavigation.Stay?>(null) }
    // Moves when the page goes from a pinned version to the latest (View latest, Back to Latest):
    // the latest may be newer than the one read on open, so it is read again.
    var latestReads by remember(params.toString()) { mutableStateOf(0) }
    // A new WebView (nothing kept), a new timer: Retry after a stalled / crashed load, or the one
    // fresh load after a reused kept document wandered off ([RaftArtifactViewerBodyState.ReloadingFresh]).
    var webRetry by remember(params.toString()) { mutableStateOf(0) }
    var freshReloadUsed by remember(params.toString()) { mutableStateOf(false) }
    // The page draws the only bar (`hostToolbar=1`, shown, no load error): this one is left out.
    var pageDrawsBar by remember(params.toString()) { mutableStateOf(false) }

    // Revalidate (never trusting the cached header for access): a fresh summary; unavailable (403/404)
    // replaces the screen and drops the cached copy.
    LaunchedEffect(scope, target, latestReads) {
        val serverId = scope?.serverId ?: return@LaunchedEffect
        val store = host.sharedStores.artifactSummaryStore
        store.refresh(serverId, target)
        val answer = store.state.first { store.isFresh(serverId, target) }.answersFor(serverId, listOf(target))[target.key]
        when (answer) {
            is ArtifactSummaryAnswer.Ok -> {
                val fresh = RaftArtifactViewerHeader.of(answer.summary, target)
                header = fresh
                RaftArtifactViewerMemory.cache.remember(scope, fresh)
            }
            // A deleted Artifact opens nothing (web: its card has no viewer): the one "can't open" state.
            ArtifactSummaryAnswer.Unavailable, is ArtifactSummaryAnswer.Deleted -> bodyState = RaftArtifactViewerBodyState.Unavailable
            ArtifactSummaryAnswer.Failed, null -> Unit
        }
        raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_summary result=${raftArtifactViewerSummaryLogToken(answer)}")
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
        (bodyState as? RaftArtifactViewerBodyState.ReloadingFresh)?.let { reloading ->
            // The WebView left the screen (and was parked): dropping it destroys it, so the next
            // body finds nothing kept and loads the Artifact's page in a new one.
            RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
            raftWebViewKeepAliveClear()
            host.bridge.log("artifact_viewer_kept_reload reason=self_navigation to=${reloading.to}")
            webRetry += 1
            bodyState = RaftArtifactViewerBodyState.Viewing
            return@LaunchedEffect
        }
        val leaving = bodyState as? RaftArtifactViewerBodyState.Left ?: return@LaunchedEffect
        RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
        host.bridge.log("artifact_viewer_left action=${leaving.navigation.logToken()} to=${leaving.to}")
        closePage()
        when (val navigation = leaving.navigation) {
            is RaftArtifactViewerNavigation.OpenMessage -> RaftMessageLinkIngress.accept(navigation.url, "artifact_viewer", webBaseUrl)
            // Another Artifact (a link in the page): in the column, in its place (web: one Artifact at a time); else the
            // app shell opens it in the viewer.
            is RaftArtifactViewerNavigation.OpenArtifact ->
                if (!inColumn || !pager.openRaftArtifactViewerPage(navigation.target, host.openStoreScope)) {
                    RaftArtifactLinkInbox.Process.submit(navigation.target, "artifact_viewer")
                }
            else -> Unit
        }
    }

    val controller = rememberRaftWebViewController()

    val untitled = stringResource(Res.string.artifacts_activity_untitled)
    // The link of what is shown (web `useCopyArtifactLink`: this deployment's origin, `/v/<n>` only
    // when pinned), opened in the browser.
    val shownLink = ArtifactLinkTarget(target.id, if (addressed != null) addressed?.version else target.version)
    val shownUrl = remember(webBaseUrl, shownLink) { raftArtifactViewerPageUrl(webBaseUrl, shownLink) }
    val downloadInBrowser = stringResource(Res.string.artifacts_viewer_download_in_browser)
    fun toggleDesktopMode(from: String) {
        desktopModeChosen = !desktopModeChosen
        desktopModePreference?.write(desktopModeChosen)
        host.bridge.log("artifact_viewer_desktop_mode on=$desktopModeChosen from=$from")
    }
    fun openInBrowser(from: String, url: String = shownUrl) {
        host.bridge.log("artifact_viewer_open_in_browser from=$from")
        host.bridge.openUrl(url, inBrowser = true)
    }
    val viewing = bodyState == RaftArtifactViewerBodyState.Viewing
    // One bar where the page draws it (`hostToolbar=1`, artin "双栏"): its toolbar has Back, the
    // title and this bar's ⋮ items in its own ⋯. Otherwise (a web that does not, while loading, a
    // load error, unavailable) this bar has Back, the title and a ⋮ with only what the page's own
    // toolbar and ⋯ (under it) cannot offer; nothing shows twice.
    RaftArtifactViewerFrame(
        title = header?.title?.ifBlank { untitled } ?: untitled,
        onBack = ::closePage,
        desktopMode = desktopMode.takeIf { desktopModeOffered && viewing },
        // In a column the App's bar stays: its head has the column's Close.
        pageDrawsBar = pageDrawsBar && viewing && scope != null && !inColumn,
        actions =
            RaftArtifactViewerBarActions(
                onToggleDesktopMode = { toggleDesktopMode("bar") },
                onOpenInBrowser = { openInBrowser("bar") },
            ),
    ) {
        when (bodyState) {
            RaftArtifactViewerBodyState.Unavailable -> RaftArtifactViewerUnavailable(onClose = ::closePage)
            is RaftArtifactViewerBodyState.Left -> Unit
            is RaftArtifactViewerBodyState.ReloadingFresh -> RaftCenteredLoading(stringResource(Res.string.common_loading))
            RaftArtifactViewerBodyState.Viewing ->
                if (scope == null) {
                    // An unknown subject reuses nothing and keeps nothing: the page in the browser, as before.
                    LaunchedEffect(Unit) {
                        raftLogWarn(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_browser_fallback reason=no_scope")
                        host.bridge.openUrl(pageUrl, inBrowser = true)
                        closePage()
                    }
                } else {
                    key(webRetry) {
                        RaftArtifactViewerWebBody(
                            host = host,
                            scope = scope,
                            target = target,
                            pageUrl = embeddedPageUrl,
                            controller = controller,
                            desktopMode = desktopMode,
                            hostDesktop = hostDesktop,
                            retry = webRetry,
                            onRetry = { webRetry += 1 },
                            onStay = { next ->
                                if (raftArtifactViewerRereadsLatest(addressed, next)) latestReads += 1
                                addressed = next
                            },
                            onDownload = {
                                // The WebView cannot save the page's files (they are `blob:` links): the
                                // browser can, so the Artifact opens there, said first.
                                host.bridge.log("artifact_viewer_download action=open_in_browser")
                                host.bridge.toast(downloadInBrowser)
                                host.bridge.openUrl(shownUrl, inBrowser = true)
                            },
                            onHostAction = { action, version ->
                                when (action) {
                                    RaftArtifactViewerHostAction.DesktopMode -> if (desktopModeOffered) toggleDesktopMode("page")
                                    // The version the page shows, from the address that asked (the web sends no payload).
                                    RaftArtifactViewerHostAction.OpenInBrowser ->
                                        openInBrowser("page", raftArtifactViewerPageUrl(webBaseUrl, ArtifactLinkTarget(target.id, version)))
                                }
                            },
                            onPageDrawsBar = { pageDrawsBar = it },
                            freshReloadAvailable = !freshReloadUsed,
                            onLeave = { navigation, to -> bodyState = RaftArtifactViewerBodyState.Left(navigation, to) },
                            onReloadFresh = { to ->
                                freshReloadUsed = true
                                bodyState = RaftArtifactViewerBodyState.ReloadingFresh(to)
                            },
                        )
                    }
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
    controller: RaftWebViewController,
    desktopMode: Boolean,
    /** `hostDesktop` the address keeps ([raftArtifactViewerHostDesktopScript]); null: not offered. */
    hostDesktop: Boolean?,
    retry: Int,
    onRetry: () -> Unit,
    onStay: (RaftArtifactViewerNavigation.Stay) -> Unit,
    onDownload: () -> Unit,
    /** An App item in the page's own ⋯ (`#host=<name>`), with the version its address shows (null: the latest). */
    onHostAction: (action: RaftArtifactViewerHostAction, version: Int?) -> Unit,
    /** Whether the page now draws the only bar (`hostToolbar=1`, shown, no load error). */
    onPageDrawsBar: (Boolean) -> Unit,
    /** One fresh load per open may replace a close ([raftArtifactViewerLeaveAction]). */
    freshReloadAvailable: Boolean,
    onLeave: (navigation: RaftArtifactViewerNavigation, to: String) -> Unit,
    onReloadFresh: (to: String) -> Unit,
) {
    val authStore = remember(host) { host.sharedStores.authStore(host.storeScope) }
    val snapshot = rememberSettingsWebViewAuthSnapshot(authStore, host.sharedStores.authStore)
    val binding = rememberSettingsWebViewHostAuthBinding(snapshot, controller)
    val session = snapshot.session?.takeIf { it.user.id.trim() == scope.accountId }
    // Nothing is attached until the session is read: a new document needs it before it loads, and a
    // kept one is reused only with the session it was parked with.
    if (session == null) {
        // Nothing loads until the session is read: say so when it takes long (or never comes, e.g.
        // a session of another account than the viewer's scope).
        val readSession by rememberUpdatedState(snapshot.session)
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(RAFT_ARTIFACT_VIEWER_SLOW_LOAD_MS)
            // sessionRead=true here means a session of another account than the viewer's scope.
            raftLogWarn(
                RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG,
                "artifact_viewer_waiting_session elapsedMs=$RAFT_ARTIFACT_VIEWER_SLOW_LOAD_MS sessionRead=${readSession != null}",
            )
        }
        RaftCenteredLoading(stringResource(Res.string.common_loading))
        return
    }
    // A kept document of this scope and session is shown as it is (the same Artifact: at once;
    // another: the web app routes to it); otherwise the viewer's page loads. One kept under an older
    // session (a token refresh while it was parked) is dropped before the WebView is attached, so
    // it is not taken either.
    val kept =
        remember(scope) {
            val candidate = raftWebViewKeptDocument(scope.keepAliveKey) ?: return@remember null
            if (RaftArtifactViewerMemory.cache.keptDocumentUsable(scope, session.accessToken)) {
                candidate
            } else {
                raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_kept_dropped reason=session_changed")
                RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
                raftWebViewKeepAliveClear()
                null
            }
        }
    // A rotation synced into the shown document: it is parked with this session.
    LaunchedEffect(session.accessToken) { RaftArtifactViewerMemory.cache.noteKeptSession(scope, session.accessToken) }
    val src = kept?.src ?: pageUrl
    val loadWatchdog = remember { RaftWebViewLoadWatchdog(page = "artifact_viewer", url = src, retry = retry) }
    val loadError = loadWatchdog.showsError
    LaunchedEffect(loadError) {
        if (!loadError) return@LaunchedEffect
        // The WebView left the screen (and may have been parked): never keep a stalled or crashed
        // document for the next open.
        RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
        raftWebViewKeepAliveClear()
    }
    val showsTarget = remember(scope, target) { kept != null && RaftArtifactViewerMemory.cache.keptDocumentShows(scope, target) }
    var visible by remember { mutableStateOf(showsTarget) }
    var documentReady by remember { mutableStateOf(false) }
    var navigated by remember { mutableStateOf(kept == null || showsTarget) }
    // Every top-level address (a load, or the web app's own history change). Only once the page has
    // shown this Artifact: before that a kept document is still on the warm route or the last one.
    var armed by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(false) }
    val currentOnStay by rememberUpdatedState(onStay)
    val currentOnDownload by rememberUpdatedState(onDownload)
    val currentOnLeave by rememberUpdatedState(onLeave)
    val currentOnReloadFresh by rememberUpdatedState(onReloadFresh)
    val currentOnHostAction by rememberUpdatedState(onHostAction)
    // The page said once (this document) that its toolbar is the only bar: a web build does not
    // change under a document, and the address may lose the marker for a moment while it routes.
    var pageAcked by remember { mutableStateOf(false) }
    // The last address seen (keeps `hostDesktop` current, takes a handled `#host=<name>` off).
    var address by remember { mutableStateOf("") }
    // A `#host=<name>` address being handled: its fragment is taken off first, then the App acts
    // (Desktop Mode reloads the document on Android: at the address without it, never asking again).
    var pendingHostAction by remember { mutableStateOf<Pair<RaftArtifactViewerHostAction, String>?>(null) }
    // The last one handled: the same address again (a platform's second callback, or before it
    // was taken off) is the same tap.
    var handledHostActionUrl by remember { mutableStateOf<String?>(null) }
    var handledHostActionAt by remember { mutableStateOf(kotlin.time.TimeSource.Monotonic.markNow()) }
    var handledHostActionCleared by remember { mutableStateOf(true) }
    val currentFreshReloadAvailable by rememberUpdatedState(freshReloadAvailable)
    val attachedAt = remember { kotlin.time.TimeSource.Monotonic.markNow() }
    fun onAddress(url: String) {
        if (left) return
        address = url
        if (!pageAcked && raftArtifactViewerPageDrawsHostBar(url)) {
            pageAcked = true
            raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_page_bar on=true")
        }
        val hostAction = raftArtifactViewerHostAction(url)
        if (hostAction == null) {
            handledHostActionCleared = true
        } else if (
            pendingHostAction == null &&
            raftArtifactViewerHostActionIsNew(
                url = url,
                handledUrl = handledHostActionUrl,
                handledCleared = handledHostActionCleared,
                sinceHandledMs = handledHostActionAt.elapsedNow().inWholeMilliseconds,
            )
        ) {
            handledHostActionUrl = url
            handledHostActionAt = kotlin.time.TimeSource.Monotonic.markNow()
            handledHostActionCleared = false
            pendingHostAction = hostAction to url
        }
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
                    val to = raftArtifactViewerLogDestination(url)
                    val action =
                        raftArtifactViewerLeaveAction(
                            navigation = navigation,
                            url = url,
                            reusedKept = kept != null,
                            sinceShownMs = attachedAt.elapsedNow().inWholeMilliseconds,
                            reloadAvailable = currentFreshReloadAvailable,
                        )
                    when (action) {
                        RaftArtifactViewerLeaveAction.ReloadFresh -> currentOnReloadFresh(to)
                        RaftArtifactViewerLeaveAction.Leave -> currentOnLeave(navigation, to)
                    }
                }
        }
    }
    LaunchedEffect(Unit) { kept?.currentUrl?.let(::onAddress) }
    LaunchedEffect(Unit) {
        RaftArtifactOpenTrace.mark("webview_attach", "kept=${kept != null} showsTarget=$showsTarget")
        if (kept == null) RaftArtifactViewerMemory.cache.rememberKeptDocument(scope, target, pageUrl, session.accessToken)
    }
    val injected =
        remember(scope, session.accessToken, binding?.webViewGeneration, desktopMode) {
            raftArtifactViewerInjectedScript(scope.webOrigin, raftArtifactViewerSessionScript(scope, session, binding), desktopMode)
        }
    LaunchedEffect(documentReady, navigated) {
        if (!documentReady || navigated) return@LaunchedEffect
        val script = raftArtifactViewerNavigateScript(scope.webOrigin, settingsWebViewAuthStorageJavaScript(session, binding), target)
        if (controller.evaluateJavaScript(script)) {
            navigated = true
            RaftArtifactViewerMemory.cache.rememberKeptDocument(scope, target, pageUrl, session.accessToken)
            RaftArtifactOpenTrace.mark("viewer_nav_started", "where=page")
        }
    }
    LaunchedEffect(navigated) { if (navigated && kept != null) visible = true }
    // `hostDesktop` in the page's address follows Desktop Mode (and comes back if a route dropped
    // it); a handled `#host=<name>` is taken off.
    LaunchedEffect(pendingHostAction) {
        val (action, url) = pendingHostAction ?: return@LaunchedEffect
        if (controller.evaluateJavaScript(raftArtifactViewerHostDesktopScript(scope.webOrigin, hostDesktop, clearHostAction = true))) {
            handledHostActionCleared = true
        }
        host.bridge.log("artifact_viewer_host_action action=${action.wireName}")
        val shown = raftArtifactViewerNavigation(url, scope.webOrigin, target.id) as? RaftArtifactViewerNavigation.Stay
        currentOnHostAction(action, shown?.version)
        pendingHostAction = null
    }
    LaunchedEffect(visible, address, hostDesktop) {
        if (!visible || address.isBlank() || pendingHostAction != null) return@LaunchedEffect
        if (raftArtifactViewerNavigation(address, scope.webOrigin, target.id) !is RaftArtifactViewerNavigation.Stay) return@LaunchedEffect
        if (raftArtifactViewerAddressSaysDesktop(address, hostDesktop)) return@LaunchedEffect
        controller.evaluateJavaScript(raftArtifactViewerHostDesktopScript(scope.webOrigin, hostDesktop))
    }
    val drawsBar = pageAcked && visible && !loadError
    val currentOnPageDrawsBar by rememberUpdatedState(onPageDrawsBar)
    LaunchedEffect(drawsBar) { currentOnPageDrawsBar(drawsBar) }
    DisposableEffect(Unit) { onDispose { currentOnPageDrawsBar(false) } }
    // Diagnostics: a load still covered after a while, and what the page reached when it closes.
    val latestState by rememberUpdatedState(
        "kept=${kept != null} showsTarget=$showsTarget visible=$visible documentReady=$documentReady " +
            "navigated=$navigated session=true"
    )
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(RAFT_ARTIFACT_VIEWER_SLOW_LOAD_MS)
        if (!visible || !documentReady) {
            raftLogWarn(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_load_slow elapsedMs=$RAFT_ARTIFACT_VIEWER_SLOW_LOAD_MS $latestState")
        }
    }
    DisposableEffect(Unit) {
        onDispose { raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_closed $latestState") }
    }
    val density = LocalDensity.current
    val bottomInsetPx =
        raftArtifactViewerWebBottomInsetPx(
            imeBottomPx = raftImeBottomPx(density),
            navigationBarBottomPx = with(density) { raftBottomSafeAreaPadding().roundToPx() },
            hostPadsWebView = raftWebViewNeedsHostBottomInsets,
        )
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
                .padding(bottom = with(density) { bottomInsetPx.toDp() }),
    ) {
        if (!loadError) RaftSandboxWebView(
            url = src,
            modifier = Modifier.fillMaxSize().alpha(if (visible) 1f else 0f),
            allowScripts = true,
            allowNavigation = true,
            domStorageEnabled = true,
            // Desktop Mode: Android reloads the document at its current address with the desktop
            // viewport (the version shown stays); iOS re-zooms the page in place.
            useWideViewPort = desktopMode,
            cacheEnabled = true,
            ephemeral = false,
            injectedJavaScript = injected,
            hostEventsEnabled = false,
            controller = controller,
            allowedNavigationSchemes = setOf("http", "https"),
            mainFrameOrigin = scope.webOrigin,
            // The content frame (desktop declares it; see RaftWebViewConfig.frameHosts).
            frameHosts = setOf(ARTIFACT_CONTENT_DOMAIN),
            keepAliveKey = scope.keepAliveKey,
            loadWatchdog = loadWatchdog,
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
                // Rule 2: off the web origin never loads here; a new window the page asked for is
                // routed as a link ([raftArtifactViewerBlockedNavigationTarget]).
                when (val routed = raftArtifactViewerBlockedNavigationTarget(blocked, scope.webOrigin, target.id)) {
                    is RaftArtifactViewerBlockedTarget.SystemBrowser -> host.bridge.openUrl(routed.url, inBrowser = true)
                    RaftArtifactViewerBlockedTarget.Download -> currentOnDownload()
                    is RaftArtifactViewerBlockedTarget.OpenMessage -> RaftMessageLinkIngress.accept(routed.url, "artifact_viewer", scope.webOrigin)
                    is RaftArtifactViewerBlockedTarget.OpenArtifact -> RaftArtifactLinkInbox.Process.submit(routed.target, "artifact_viewer")
                    RaftArtifactViewerBlockedTarget.Ignore -> Unit
                }
            },
            // A file the page saves (Android DownloadListener, an iOS attachment response): the browser can.
            onDownloadRequested = { currentOnDownload() },
        )
        SettingsWebViewAccessTokenSyncEffect(
            snapshot = snapshot,
            documentReady = documentReady,
            controller = controller,
            binding = binding,
            surface = SettingsWebViewSurface.ArtifactViewer,
            logger = host.bridge::log,
        )
        if (loadError) {
            SettingsWebViewErrorContent(
                actionLabel = stringResource(Res.string.common_retry),
                actionIcon = RaftLucideIconName.RefreshCw,
                body = settingsWebViewErrorBody(loadWatchdog.state.phase),
                onAction = onRetry,
            )
        } else if (!visible) {
            RaftCenteredLoading(stringResource(Res.string.common_loading))
        }
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
