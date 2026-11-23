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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
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
import build.raft.app.network.HEADER_AUTHORIZATION
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.AttachmentDownloadLocalBytes
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val RAFT_ARTIFACT_VIEWER_PAGE = "compose_artifact_viewer"
private const val VIEWER_ARTIFACT_ID = "artifactId"
private const val VIEWER_VERSION = "artifactVersion"
private const val VIEWER_CONVERSATION_ID = "conversationId"

@Page(RAFT_ARTIFACT_VIEWER_PAGE, supportInLocal = true)
internal class ComposeRaftArtifactViewerPage : AuthenticatedProductPager(AuthenticatedProductRoute.ArtifactViewer) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { RaftArtifactViewerScreen() }
    }
}

/**
 * Identity only (the Route / PageData rule): the Artifact, its pinned version and the conversation
 * it was opened from (`hostConversation`, [raftArtifactViewerHostConversationOrNull]; absent: none).
 * The header the page paints at once comes from [RaftArtifactViewerMemory], where the opener put its
 * card, read only in the same scope.
 */
internal fun raftArtifactViewerPageData(target: ArtifactLinkTarget, conversationId: String? = null): JSONObject =
    JSONObject().apply {
        put("pageName", RAFT_ARTIFACT_VIEWER_PAGE)
        put(VIEWER_ARTIFACT_ID, target.id)
        target.version?.let { put(VIEWER_VERSION, it) }
        raftArtifactViewerHostConversationOrNull(conversationId)?.let { put(VIEWER_CONVERSATION_ID, it) }
    }

/** The conversation the viewer was opened from ([raftArtifactViewerPageData]); null: none. */
internal fun JSONObject.raftArtifactViewerConversationOrNull(): String? =
    raftArtifactViewerHostConversationOrNull(optString(VIEWER_CONVERSATION_ID))

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
        ephemeral = raftPlatformUsesEphemeralAuthWebView(),
        injectedJavaScript = injectedJavaScript,
        allowedNavigationSchemes = setOf("http", "https"),
        // Rule 3: no bridge into the app, for the page or the content frame.
        hostEventsEnabled = false,
        // Rule 2: top-level documents stay on the web origin.
        mainFrameOrigin = scope.webOrigin,
        frameHosts = setOf(ARTIFACT_CONTENT_DOMAIN),
        keepAliveKey = scope.keepAliveKey,
        diagnosticsSurface = RAFT_ARTIFACT_VIEWER_WEBVIEW_SURFACE,
        // #proj-workspace:#360: pinch-to-zoom stays available outside Desktop Mode too.
        allowPinchZoom = true,
    )

/** [RaftWebViewConfig.diagnosticsSurface] of the Artifact viewer (its prewarmed view too). */
internal const val RAFT_ARTIFACT_VIEWER_WEBVIEW_SURFACE = "artifact_viewer"

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
 * The conversation an Artifact opened here is opened from (`hostConversation`,
 * [RAFT_ARTIFACT_VIEWER_HOST_CONVERSATION_PARAM]): a conversation page provides it (ThreadPage: the
 * channel or DM; for a thread, the channel it belongs to). Null elsewhere (a task conversation, whose
 * web dialog records nothing; Artifacts home, Activity, links from outside the app).
 */
internal val LocalRaftArtifactOpenConversation = compositionLocalOf<String?> { null }

/**
 * True for the viewer in the wide layout's Artifact column (#189 step 4, RaftWidePanes.kt `RaftArtifactColumnCell`):
 * the column owns Back (its own handler, on only while the column is on screen), the bar is drawn as on the window
 * page but without touching the window's top ([RaftArtifactViewerFrame] `inColumn`), the page is told the shell's
 * head height so its own bar lines up with the conversation's head (`hostHead`, [raftArtifactViewerHostHeadHere]), and
 * an Artifact the page navigates to opens in the same column.
 */
internal val LocalRaftArtifactViewerInColumn = compositionLocalOf { false }

/**
 * The width the Artifact column lays its page out at (#189 step 6; its content keeps 480 and clips below that), in
 * dp; null outside a column. Desktop Mode is offered by it there, not by the window's.
 */
internal val LocalRaftArtifactViewerColumnWidthDp = compositionLocalOf<Float?> { null }

/**
 * True where an Artifact opened here opens in the wide layout's Artifact column: the conversation, its thread and the
 * columns beside them, where their row has the column (RaftWidePanes.kt `RaftWorkspaceDetailGrid`, wide). False
 * elsewhere, where the viewer opens as a page of its own (a phone; Artifacts home, Activity). What a prewarm here boots
 * and what an opener here reuses carry the `hostHead` that viewer sends ([raftArtifactViewerOpenHostHead]).
 */
internal val LocalRaftArtifactOpenInColumn = compositionLocalOf { false }

/**
 * The `hostHead` of the viewer composed here ([raftArtifactViewerHostHead]): the shell's head height in the Artifact
 * column ([LocalRaftArtifactViewerInColumn]), 56 by touch and 44 with a mouse as the input in use changes; null as a
 * page of its own.
 */
@Composable
@ReadOnlyComposable
internal fun raftArtifactViewerHostHeadHere(): Int? =
    raftArtifactViewerHostHead(LocalRaftArtifactViewerInColumn.current, RaftTheme.controls.headHeight)

/** The `hostHead` a viewer opened from here sends ([LocalRaftArtifactOpenInColumn]): the one a prewarm or a reuse here needs. */
@Composable
@ReadOnlyComposable
internal fun raftArtifactViewerOpenHostHead(): Int? =
    raftArtifactViewerHostHead(LocalRaftArtifactOpenInColumn.current, RaftTheme.controls.headHeight)

/**
 * The viewer screen's frame as the screen decides it: whether the App's bar is left out ([pageSaysItDrawsBar]: the
 * page said `hostToolbar=1`, it shows the Artifact, [viewing], and the subject is known, [scopeKnown]) and where it
 * stands ([LocalRaftArtifactViewerInColumn]). One bar in a column too (#4635, web #14672): where the page draws it,
 * its Back (`#host=close`) closes the page, and so the column; where it doesn't (an older web), the App's bar has the
 * column's Close. Its own composable so a test composes this decision, not a frame built by hand (PR #4666 review).
 */
@Composable
internal fun RaftArtifactViewerScreenFrame(
    title: String,
    onBack: () -> Unit,
    pageSaysItDrawsBar: Boolean,
    viewing: Boolean,
    scopeKnown: Boolean,
    desktopMode: Boolean? = null,
    actions: RaftArtifactViewerBarActions = RaftArtifactViewerBarActions(),
    content: @Composable () -> Unit,
) {
    RaftArtifactViewerFrame(
        title = title,
        onBack = onBack,
        actions = actions,
        desktopMode = desktopMode,
        pageDrawsBar = pageSaysItDrawsBar && viewing && scopeKnown,
        inColumn = LocalRaftArtifactViewerInColumn.current,
        content = content,
    )
}

/**
 * Opens the viewer page for [target] from [this] page, with [storeScope] when given (else the
 * page's own scope). False when no page can be opened from here (no scope): the caller falls back.
 */
internal fun Pager.openRaftArtifactViewerPage(
    target: ArtifactLinkTarget,
    storeScope: RaftShellStoreScope?,
    conversationId: String? = null,
): Boolean =
    openRaftPage(
        pageName = RAFT_ARTIFACT_VIEWER_PAGE,
        pageData = raftArtifactViewerPageData(target, conversationId),
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
 * when no page can be opened from here. [conversationId]: the conversation the Artifact is opened
 * from ([LocalRaftArtifactOpenConversation]; null: none).
 */
@Composable
internal fun rememberRaftArtifactOpener(
    conversationId: String? = LocalRaftArtifactOpenConversation.current,
    fallback: (ArtifactLinkTarget) -> Unit,
): RaftArtifactOpener {
    val host = rememberRaftArtifactViewerHost(LocalRaftArtifactOpenStoreScope.current)
    val coroutineScope = rememberCoroutineScope()
    val currentFallback by rememberUpdatedState(fallback)
    val conversation = raftArtifactViewerHostConversationOrNull(conversationId)
    // The head height the viewer opened from here sends (the Artifact column's; none elsewhere), as at the tap.
    val currentHostHead by rememberUpdatedState(raftArtifactViewerOpenHostHead())
    return remember(host, coroutineScope, conversation) {
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
                val hostHead = currentHostHead
                coroutineScope.launch {
                    val session =
                        runCatchingPreservingCancellation { host.sharedStores.authStore(host.storeScope).readSession() }.getOrNull()
                    if (session == null || session.user.id.trim() != scope.accountId) return@launch
                    // Only with the session it was parked with, and booted for this conversation and head height (else
                    // the page loads a new one).
                    if (!RaftArtifactViewerMemory.cache.keptDocumentUsable(scope, session.accessToken, conversation, hostHead)) return@launch
                    val script =
                        raftArtifactViewerNavigateScript(
                            scope.webOrigin,
                            settingsWebViewAuthStorageJavaScript(session, null),
                            target,
                            conversation,
                            hostHead,
                            raftEmbeddedWebLangTag(),
                        )
                    if (raftWebViewEvaluateInKept(scope.keepAliveKey, scope.webOrigin, script)) {
                        RaftArtifactViewerMemory.cache.rememberKeptDocument(
                            scope,
                            target,
                            raftArtifactViewerEmbeddedPageUrl(host.webBaseUrl, target, conversation, hostHead, raftEmbeddedWebLangTag()),
                            session.accessToken,
                        )
                        RaftArtifactOpenTrace.mark("viewer_nav_started", "where=tap")
                    }
                }
            }
            val opened = host.pager.openRaftArtifactViewerPage(target, host.openStoreScope, conversation)
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
 * Nothing is read or marked read. No-op without keep-alive (iOS, desktop) or a known subject, or
 * while the hosting page is covered (`!pager.isAppeared`) or the app is in the background.
 * Booted for [conversationId] (`hostConversation`, latched by the page), the one the cards on screen
 * open from, and with the `hostHead` the viewer opened from them sends (the Artifact column's beside a
 * conversation on a tablet, by the input in use; none for a page of its own): what that viewer reuses. A
 * kept document of another conversation or head height is not reused for them.
 */
@Composable
internal fun RaftArtifactViewerPrewarm(enabled: Boolean, conversationId: String? = LocalRaftArtifactOpenConversation.current) {
    if (!enabled) return
    val host = rememberRaftArtifactViewerHost(LocalRaftArtifactOpenStoreScope.current) ?: return
    val authStore = remember(host) { host.sharedStores.authStore(host.storeScope) }
    RaftArtifactViewerPrewarmRuntime(
        enabled = true,
        pager = host.pager,
        authStore = authStore,
        processAuthEvents = host.sharedStores.authStore,
        webBaseUrl = host.webBaseUrl,
        scope = host.scope(),
        conversationId = conversationId,
        hostHead = raftArtifactViewerOpenHostHead(),
    )
}

@Composable
internal fun RaftArtifactViewerPrewarmRuntime(
    enabled: Boolean,
    pager: Pager?,
    authStore: build.raft.app.data.RaftAuthSessionStore,
    processAuthEvents: build.raft.app.data.RaftAuthSessionEventSource,
    webBaseUrl: String,
    scope: RaftArtifactViewerScope?,
    conversationId: String? = null,
    /** `hostHead` of the viewer this warm document is for ([raftArtifactViewerOpenHostHead]); null: a page of its own. */
    hostHead: Int? = null,
    desktopModeOverride: Boolean? = null,
    keptDocumentPresent: (String) -> Boolean = { key -> raftWebViewKeptDocument(key) != null },
    prewarmEffect: @Composable (RaftWebViewConfig?) -> Unit = { config -> RaftWebViewPrewarmEffect(config) },
) {
    if (!enabled) return
    val lifecycleOwner = LocalLifecycleOwner.current
    val pagerAppeared = rememberRaftPagerAppeared(pager)
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer =
            LifecycleEventObserver { _, _ ->
                foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!pagerAppeared || !foreground) return
    val desktopMode =
        desktopModeOverride
            ?: (raftArtifactViewerOffersDesktopModeHere() && remember { raftArtifactViewerDesktopModePreference()?.read() == true })
    val snapshot = rememberSettingsWebViewAuthSnapshot(authStore, processAuthEvents)
    val controller = rememberRaftWebViewController()
    val binding = rememberSettingsWebViewHostAuthBinding(snapshot, controller)
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS)
        settled = true
    }
    val conversation = raftArtifactViewerHostConversationOrNull(conversationId)
    val head = raftArtifactViewerHostHeadOrNull(hostHead)
    val config =
        remember(settled, scope, snapshot.session?.accessToken, binding?.webViewGeneration, desktopMode, conversation, head) {
            val session = snapshot.session
            if (!settled || scope == null || session == null || session.user.id.trim() != scope.accountId) {
                null
            } else if (keptDocumentPresent(scope.keepAliveKey)) {
                null
            } else {
                RaftArtifactViewerMemory.cache.markKeptWarm(scope, session.accessToken, conversation, head)
                raftArtifactViewerWebViewConfig(
                    url = raftArtifactViewerWarmUrl(webBaseUrl, conversation, head, raftEmbeddedWebLangTag()),
                    scope = scope,
                    injectedJavaScript =
                        raftArtifactViewerInjectedScript(scope.webOrigin, raftArtifactViewerSessionScript(scope, session, binding), desktopMode),
                    desktopMode = desktopMode,
                )
            }
        }
    prewarmEffect(config)
}

internal const val RAFT_ARTIFACT_VIEWER_PREWARM_DELAY_MS = 1_200L

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

/**
 * The device's Desktop Mode choice, the page's or ([inColumn]) the Artifact column's own (#189 step 6); null where no
 * app-scope store is installed (fixtures).
 */
private fun raftArtifactViewerDesktopModePreference(inColumn: Boolean = false): ArtifactViewerDesktopModePreferenceRepository? =
    runCatching {
        ArtifactViewerDesktopModePreferenceRepository(
            build.raft.app.data.raftSharedPreferenceStore(),
            if (inColumn) ArtifactViewerDesktopModePreferenceRepository.ColumnKey else ArtifactViewerDesktopModePreferenceRepository.Key,
        )
    }.getOrNull()

/**
 * [raftArtifactViewerOffersDesktopMode] here (touch, narrower than a desktop): by the Artifact column's width in a
 * column ([LocalRaftArtifactViewerColumnWidthDp], artin #189 step 6), else by the window's.
 */
@Composable
private fun raftArtifactViewerOffersDesktopModeHere(): Boolean {
    val windowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value }
    val widthDp = LocalRaftArtifactViewerColumnWidthDp.current ?: windowWidthDp
    return raftArtifactViewerOffersDesktopMode(raftPlatformHasFinePointer(), widthDp)
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

    /**
     * The page signed itself out ([RaftArtifactViewerLeaveAction.RecoverSession], #300): the App's
     * token is refreshed, the signed-out document dropped, and the Artifact's page opened again in
     * a new WebView (the normal open path, so the page gets a new entry ticket for its content).
     */
    data class RecoveringSession(val to: String, val pageToken: String) : RaftArtifactViewerBodyState
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
    // Desktop Mode: this device's choice, offered on a touch screen narrower than a desktop. In the Artifact column
    // (#189 step 6) the column's own choice, off until picked in its ⋯: the page lays out at the column's width.
    val desktopModePreference = remember(inColumn) { raftArtifactViewerDesktopModePreference(inColumn) }
    var desktopModeChosen by remember(inColumn) { mutableStateOf(desktopModePreference?.read() == true) }
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
    // The conversation it was opened from (`hostConversation`): the page's title then lists the Artifacts
    // recently opened there, and picking one moves the page in place ([raftArtifactViewerSwitchesInPlace]).
    val conversationId = remember(params.toString()) { params.raftArtifactViewerConversationOrNull() }
    // The Artifact shown: the one opened, then the one the page moved to in place (its title, Copy
    // link, the summary read). The WebView's document is not reloaded for it ([bodyTarget]).
    var shown by remember(params.toString()) { mutableStateOf<ArtifactLinkTarget>(target) }
    // Leaving it, it is seen: a channel shown whose announcement it is reads at once (#16289 row 7).
    ChannelAnnouncementViewedEffect(host.sharedStores, host.storeScope, scope?.serverId, shown.canonicalId)
    LaunchedEffect(Unit) {
        raftLogInfo(
            RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG,
            raftArtifactViewerOpenLogLine(target, scopeKnown = scope != null, webBaseUrl = webBaseUrl),
        )
    }
    // Fallback URL when scope == null: the page as the web shows it.
    val pageUrl = remember(webBaseUrl, shown) { raftArtifactViewerPageUrl(webBaseUrl, shown) }
    // Desktop Mode's state for the page's own ⋯ (`hostDesktop`; null: not offered here).
    val hostDesktop: Boolean? = desktopMode.takeIf { desktopModeOffered }
    // In the Artifact column, the head the page's toolbar lines up with (`hostHead`: the conversation's head height,
    // by the input in use); null as a page of its own, where the page keeps its phone toolbar.
    val hostHead: Int? = raftArtifactViewerHostHeadHere()
    // Paint the header at once: the opener's card (put in the cache on tap), else the last one seen,
    // in this scope only.
    var header by remember(params.toString()) { mutableStateOf(RaftArtifactViewerMemory.cache.header(scope, target.canonicalId)) }
    LaunchedEffect(Unit) { if (header != null) RaftArtifactOpenTrace.mark("header_painted", "from=card_or_cache") }
    var bodyState by remember(params.toString()) { mutableStateOf<RaftArtifactViewerBodyState>(RaftArtifactViewerBodyState.Viewing) }
    // The version the page shows, from its address (a version picked in the page's ⋯, Back to Latest).
    var addressed by remember(params.toString()) { mutableStateOf<RaftArtifactViewerNavigation.Stay?>(null) }
    // Moves when the page goes from a pinned version to the latest (View latest, Back to Latest):
    // the latest may be newer than the one read on open, so it is read again.
    var latestReads by remember(params.toString()) { mutableStateOf(0) }
    // A new WebView (nothing kept), a new timer: Retry after a stalled / crashed load, or the one
    // fresh load after a reused kept document wandered off ([RaftArtifactViewerBodyState.ReloadingFresh]).
    var webRetry by remember(params.toString()) { mutableStateOf(0) }
    // What a WebView body is opened on: [shown] when it is made (the open, or a new one after Retry or a
    // fresh reload); a move in place changes [shown] only, never the document.
    val bodyTarget = remember(params.toString(), webRetry) { shown }
    // With Desktop Mode's state and the head height at open; the App keeps both current in the address afterwards.
    val embeddedPageUrl =
        remember(webBaseUrl, bodyTarget) {
            raftArtifactViewerWithHostDesktop(raftArtifactViewerEmbeddedPageUrl(webBaseUrl, bodyTarget, conversationId, hostHead, raftEmbeddedWebLangTag()), hostDesktop)
        }
    var freshReloadUsed by remember(params.toString()) { mutableStateOf(false) }
    // #300: one session recovery reload a minute at most ([RaftArtifactViewerSessionRecoveryGuard]).
    val sessionRecovery = remember(params.toString()) { RaftArtifactViewerSessionRecoveryGuard() }
    val sessionRecoveryClock = remember(params.toString()) { kotlin.time.TimeSource.Monotonic.markNow() }
    val authStore = remember(host) { host.sharedStores.authStore(host.storeScope) }
    // The page holds only the access token: refreshed ahead of its expiry while shown, and on a sign-out.
    val accessTokenRefresher =
        remember(host, authStore) {
            SettingsWebViewAccessTokenRefresher(authStore, host.sharedStores.createAuthDataSourceForCurrentEnvironment(host.storeScope))
        }
    // #301: the opening's loading splash (the web's, ported) counts from here: its 1-second wait and
    // its cycle. Moved to when the page starts loading while nothing shows yet, so it runs on the same
    // clock as the page's own splash, which counts from its navigation.
    var splashOpenedAt by remember(params.toString()) { mutableStateOf(kotlin.time.TimeSource.Monotonic.markNow()) }
    fun splashStartsNowIfWaiting() {
        if (splashOpenedAt.elapsedNow().inWholeMilliseconds < RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS) {
            splashOpenedAt = kotlin.time.TimeSource.Monotonic.markNow()
        }
    }
    // Its kind: the card's (null: a document until it is known); a kind learned while the icon
    // cycles takes over at the next cycle, never mid-cycle (Kirby, #301).
    val splashKinds = remember(params.toString()) { RaftArtifactSplashKindState(header?.kind) }
    LaunchedEffect(splashKinds, header?.kind) { splashKinds.update(header?.kind, splashOpenedAt) }
    // The page draws the only bar (`hostToolbar=1`, shown, no load error): this one is left out.
    var pageDrawsBar by remember(params.toString()) { mutableStateOf(false) }

    // Revalidate (never trusting the cached header for access): a fresh summary; unavailable (403/404)
    // replaces the screen and drops the cached copy.
    LaunchedEffect(scope, shown, latestReads) {
        val serverId = scope?.serverId ?: return@LaunchedEffect
        val store = host.sharedStores.artifactSummaryStore
        val reading = shown
        store.refresh(serverId, reading)
        val answer = store.state.first { store.isFresh(serverId, reading) }.answersFor(serverId, listOf(reading))[reading.key]
        when (answer) {
            is ArtifactSummaryAnswer.Ok -> {
                val fresh = RaftArtifactViewerHeader.of(answer.summary, reading)
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
            RaftArtifactViewerMemory.cache.evict(scope, shown.canonicalId)
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
            // A new load: a new opening for the splash, as the page's own starts again.
            splashOpenedAt = kotlin.time.TimeSource.Monotonic.markNow()
            webRetry += 1
            bodyState = RaftArtifactViewerBodyState.Viewing
            return@LaunchedEffect
        }
        (bodyState as? RaftArtifactViewerBodyState.RecoveringSession)?.let { recovering ->
            // A new load after the refresh: a new opening for the splash, drawn while the token is refreshed.
            splashOpenedAt = kotlin.time.TimeSource.Monotonic.markNow()
            // A new token first, so the new WebView boots signed in with one that is not about to expire.
            // The token the page held: when the App already has a newer one (the refresh-ahead timer),
            // nothing is sent and the new WebView boots with that one.
            val refreshed = accessTokenRefresher.refresh("artifact_viewer_session_lost", expiringAccessToken = recovering.pageToken)
            // The signed-out document (its storage cleared by the web) is dropped, never kept.
            RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
            raftWebViewKeepAliveClear()
            raftLogWarn(
                RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG,
                "artifact_viewer_session_recover outcome=reloaded refresh=${if (refreshed) "ok" else "failed"} to=${recovering.to}",
            )
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
    val downloadSaved = stringResource(Res.string.file_preview_private_download_saved)
    val downloadSavedToDownloads = stringResource(Res.string.file_preview_download_saved)
    val downloadAlreadySaved = stringResource(Res.string.file_preview_download_already_saved)
    val downloadSavedToFiles = stringResource(Res.string.file_preview_export_saved)
    val downloadFailed = stringResource(Res.string.file_preview_error_download_attachment)
    val downloadOpenAction = stringResource(Res.string.file_preview_action_open)
    val downloadOpenResultTerms =
        RaftAttachmentOpenResultTerms(
            downloadStopped = stringResource(Res.string.file_preview_download_stopped),
            unableToDownloadAttachment = downloadFailed,
            noAppCanOpenFile = stringResource(Res.string.file_preview_error_no_app),
            storageUnavailable = stringResource(Res.string.file_preview_error_storage_unavailable),
            downloadFailed = stringResource(Res.string.file_preview_download_failed),
            unableToOpenAttachment = stringResource(Res.string.file_preview_error_open_attachment),
        )
    val downloadScope = rememberCoroutineScope()
    fun toggleDesktopMode(from: String) {
        desktopModeChosen = !desktopModeChosen
        desktopModePreference?.write(desktopModeChosen)
        host.bridge.log("artifact_viewer_desktop_mode on=$desktopModeChosen from=$from")
    }
    val copiedMessage = stringResource(Res.string.common_copied)
    fun copyLink(url: String) {
        host.bridge.log("artifact_viewer_copy_link from=page")
        host.bridge.copyToPasteboard(url)
        host.bridge.toast(copiedMessage)
    }
    val viewing = bodyState == RaftArtifactViewerBodyState.Viewing
    // One bar where the page draws it (`hostToolbar=1`, artin "双栏"): its toolbar has Back, the
    // title and this bar's ⋮ items in its own ⋯. Otherwise (a web that does not, while loading, a
    // load error, unavailable) this bar has Back, the title and a ⋮ with only what the page's own
    // toolbar and ⋯ (under it) cannot offer; nothing shows twice.
    RaftArtifactViewerScreenFrame(
        title = header?.title?.ifBlank { untitled } ?: untitled,
        onBack = ::closePage,
        desktopMode = desktopMode.takeIf { desktopModeOffered && viewing },
        pageSaysItDrawsBar = pageDrawsBar,
        viewing = viewing,
        scopeKnown = scope != null,
        actions =
            RaftArtifactViewerBarActions(
                onToggleDesktopMode = { toggleDesktopMode("bar") },
            ),
    ) {
        when (bodyState) {
            RaftArtifactViewerBodyState.Unavailable -> RaftArtifactViewerUnavailable(onClose = ::closePage)
            is RaftArtifactViewerBodyState.Left -> Unit
            is RaftArtifactViewerBodyState.ReloadingFresh,
            is RaftArtifactViewerBodyState.RecoveringSession ->
                RaftArtifactViewerSplashCover(splashKinds, splashOpenedAt, desktopMode)
            RaftArtifactViewerBodyState.Viewing ->
                if (scope == null) {
                    // An unknown viewer scope reuses nothing and keeps nothing: replace in place when
                    // standalone (or push over the shell and dismiss the column when inColumn) if a
                    // session and shell scope exist, else fall back to the browser and close this page.
                    LaunchedEffect(Unit) {
                        raftLogWarn(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_browser_fallback reason=no_scope")
                        val hasSession = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull() != null
                        val configuredWebBaseUrl = standaloneSettingsWebBaseUrl(host.sharedStores.apiEnvironmentStore)
                        val openedInApp =
                            hasSession &&
                                pager.openRaftOwnWebPage(
                                    rawUrl = pageUrl,
                                    storeScopeOverride = host.openStoreScope,
                                    activeWebBaseUrl = configuredWebBaseUrl,
                                    replaceCurrentPage = !inColumn,
                                )
                        if (!openedInApp) {
                            host.bridge.openUrl(pageUrl, inBrowser = true)
                        }
                        if (!openedInApp || inColumn) {
                            closePage()
                        }
                    }
                } else {
                    key(webRetry) {
                        RaftArtifactViewerWebBody(
                            host = host,
                            scope = scope,
                            target = bodyTarget,
                            conversationId = conversationId,
                            hostHead = hostHead,
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
                            onSwitch = { next ->
                                // Picked in the page's title (botiverse/slock #15103): the same viewer shows it now,
                                // its header from the cache until its summary is read (keyed by [shown]).
                                shown = next
                                addressed = null
                                header = RaftArtifactViewerMemory.cache.header(scope, next.canonicalId)
                            },
                            onDownload = { download ->
                                // A file the page saves, kept in the app as a browser keeps it
                                // (#proj-mobile:#202): never the page in the browser, where nobody is signed in.
                                downloadScope.launch {
                                    raftArtifactViewerSaveDownload(
                                        host = host,
                                        scope = scope,
                                        download = download,
                                        savedMessage = downloadSaved,
                                        savedToDownloadsMessage = downloadSavedToDownloads,
                                        alreadySavedMessage = downloadAlreadySaved,
                                        savedToFilesMessage = downloadSavedToFiles,
                                        failedMessage = downloadFailed,
                                        openActionLabel = downloadOpenAction,
                                        openResultTerms = downloadOpenResultTerms,
                                    )
                                }
                            },
                            onHostAction = { action, version ->
                                when (action) {
                                    RaftArtifactViewerHostAction.DesktopMode -> if (desktopModeOffered) toggleDesktopMode("page")
                                    // Copied by the App: the link built from this Artifact's id and the address's
                                    // version on the viewer's own web base (never anything the page sends).
                                    RaftArtifactViewerHostAction.CopyLink ->
                                        copyLink(raftArtifactViewerPageUrl(webBaseUrl, ArtifactLinkTarget(shown.id, version)))
                                }
                            },
                            onPageDrawsBar = { pageDrawsBar = it },
                            freshReloadAvailable = !freshReloadUsed,
                            splashKinds = splashKinds,
                            splashOpenedAt = splashOpenedAt,
                            onPageLoadStarted = ::splashStartsNowIfWaiting,
                            onLeave = { navigation, to -> bodyState = RaftArtifactViewerBodyState.Left(navigation, to) },
                            onReloadFresh = { to ->
                                freshReloadUsed = true
                                bodyState = RaftArtifactViewerBodyState.ReloadingFresh(to)
                            },
                            accessTokenRefresher = accessTokenRefresher,
                            sessionRecoveryAvailable = { sessionRecovery.available(sessionRecoveryClock.elapsedNow().inWholeMilliseconds) },
                            onSessionLost = { to, recover, pageToken ->
                                if (recover && sessionRecovery.admit(sessionRecoveryClock.elapsedNow().inWholeMilliseconds)) {
                                    bodyState = RaftArtifactViewerBodyState.RecoveringSession(to, pageToken)
                                } else {
                                    // Signed out again within a minute of a recovery: say it, with Close.
                                    raftLogWarn(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_session_recover outcome=gave_up to=$to")
                                    bodyState = RaftArtifactViewerBodyState.Unavailable
                                }
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
    /** The Artifact the viewer was opened on (what a new or kept document is routed to). */
    target: ArtifactLinkTarget,
    /** The conversation it was opened from (`hostConversation` in [pageUrl]); null: none. */
    conversationId: String?,
    /**
     * The head the page's toolbar lines up with, now (`hostHead`, [raftArtifactViewerHostHeadHere]: the Artifact
     * column's, by the input in use; null: a page of its own). The one at open is in [pageUrl]; the address keeps
     * the current one ([raftArtifactViewerHostHeadScript]).
     */
    hostHead: Int?,
    pageUrl: String,
    controller: RaftWebViewController,
    desktopMode: Boolean,
    /** `hostDesktop` the address keeps ([raftArtifactViewerHostDesktopScript]); null: not offered. */
    hostDesktop: Boolean?,
    retry: Int,
    onRetry: () -> Unit,
    onStay: (RaftArtifactViewerNavigation.Stay) -> Unit,
    /** The page moved to another Artifact in place ([raftArtifactViewerSwitchesInPlace]): the viewer shows that one. */
    onSwitch: (ArtifactLinkTarget) -> Unit,
    /** A file the page saves ([RaftWebViewDownload]). */
    onDownload: (RaftWebViewDownload) -> Unit,
    /** An App item in the page's own ⋯ (`#host=<name>`), with the version its address shows (null: the latest). */
    onHostAction: (action: RaftArtifactViewerHostAction, version: Int?) -> Unit,
    /** Whether the page now draws the only bar (`hostToolbar=1`, shown, no load error). */
    onPageDrawsBar: (Boolean) -> Unit,
    /** One fresh load per open may replace a close ([raftArtifactViewerLeaveAction]). */
    freshReloadAvailable: Boolean,
    /** The loading splash's Artifact kind ([RaftArtifactSplashKindState]) and its opening ([RaftArtifactViewerSplashCover]). */
    splashKinds: RaftArtifactSplashKindState,
    splashOpenedAt: kotlin.time.TimeMark,
    /** A new document starts loading (the splash then counts from it, as the page's own does). */
    onPageLoadStarted: () -> Unit,
    onLeave: (navigation: RaftArtifactViewerNavigation, to: String) -> Unit,
    onReloadFresh: (to: String) -> Unit,
    accessTokenRefresher: SettingsWebViewAccessTokenRefresher,
    /** Whether a sign-out of the page may still be recovered ([RaftArtifactViewerSessionRecoveryGuard]). */
    sessionRecoveryAvailable: () -> Boolean,
    /** The page signed itself out: [recover] it (reload once), or give up (Unavailable). */
    onSessionLost: (to: String, recover: Boolean, pageToken: String) -> Unit,
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
        RaftArtifactViewerSplashCover(splashKinds, splashOpenedAt, desktopMode)
        return
    }
    // A kept document of this scope and session is shown as it is (the same Artifact: at once;
    // another: the web app routes to it); otherwise the viewer's page loads. One kept under an older
    // session (a token refresh while it was parked) is dropped before the WebView is attached, so
    // it is not taken either.
    val kept =
        remember(scope) {
            val candidate = raftWebViewKeptDocument(scope.keepAliveKey) ?: return@remember null
            if (!raftArtifactViewerKeptDocumentHasEmbedContract(candidate)) {
                raftLogWarn(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_kept_dropped reason=embed_contract_missing")
                RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
                raftWebViewKeepAliveClear()
                null
            } else if (RaftArtifactViewerMemory.cache.keptDocumentUsable(scope, session.accessToken, conversationId, hostHead)) {
                candidate
            } else {
                // Another session, or booted for another conversation or head height (the page latched those).
                raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_kept_dropped reason=session_conversation_or_head_changed")
                RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
                raftWebViewKeepAliveClear()
                null
            }
        }
    // A rotation synced into the shown document: it is parked with this session.
    LaunchedEffect(session.accessToken) { RaftArtifactViewerMemory.cache.noteKeptSession(scope, session.accessToken) }
    val src = kept?.src ?: pageUrl
    // The head height the document read as it booted (the web reads `hostHead` once per document): a kept one's is the
    // one asked for now (keptDocumentUsable), a new one's is in its first address.
    val documentHostHead = remember { if (kept != null) hostHead else raftArtifactViewerHostHeadIn(pageUrl) }
    // The input changed while it was shown (a mouse used, then a finger): the address follows it, but what the page
    // read (at boot, or again if it reloaded where it is: Desktop Mode on Android) is no longer known, so the
    // document is not kept for the next open, which loads one with its own head height.
    var hostHeadMoved by remember { mutableStateOf(false) }
    LaunchedEffect(hostHead) { if (!hostHeadMoved && hostHead != documentHostHead) hostHeadMoved = true }
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
    val currentOnSwitch by rememberUpdatedState(onSwitch)
    // The Artifact the page shows: [target], then one it moved to in place (its title's menu).
    var shownTarget by remember { mutableStateOf<ArtifactLinkTarget>(target) }
    val currentOnDownload by rememberUpdatedState(onDownload)
    val currentOnLeave by rememberUpdatedState(onLeave)
    val currentOnReloadFresh by rememberUpdatedState(onReloadFresh)
    val currentOnSessionLost by rememberUpdatedState(onSessionLost)
    val currentSessionRecoveryAvailable by rememberUpdatedState(sessionRecoveryAvailable)
    // The token the page holds (the one it loaded with, then each push) and the App's: the evidence
    // a move to `/` is a sign-out, not a link.
    val pageTokens =
        remember {
            RaftArtifactViewerPageTokenTracker(session.accessToken, ProcessSettingsWebViewTokenClock, build.raft.app.data.defaultNowMillis())
        }
    val currentAppToken by rememberUpdatedState(session.accessToken)
    val currentOnHostAction by rememberUpdatedState(onHostAction)
    // A kept document that drew its own bar before (`hostToolbar=1` kept in its address): it showed an
    // Artifact already, says no `hostLoaded` now, and its bar is the only one from the first frame.
    // One only booted (the warm route) has drawn nothing yet: covered as a fresh load (#301, one bar).
    val keptDrewBar = remember(kept) { raftArtifactViewerKeptPageDrewBar(kept?.currentUrl) }
    // The page said once (this document) that its toolbar is the only bar: a web build does not
    // change under a document, and the address may lose the marker for a moment while it routes.
    // Read from the kept address at once, not a frame later (no App bar flashed over the page's).
    var pageAcked by remember { mutableStateOf(keptDrewBar) }
    // The page said its content loaded (`hostLoaded=1`, #302): the App's splash plays its ending
    // from then, and is gone after it ([splashEnded]).
    var pageLoadedAt by remember { mutableStateOf<kotlin.time.TimeMark?>(null) }
    var splashEnded by remember { mutableStateOf(false) }
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
    val inColumn = LocalRaftArtifactViewerInColumn.current
    // A message the page asks the App to open (`#host=open&to=…`, `hostOpen=message`; the page stays on the
    // Artifact), being handled: the link (null: not one the App takes) and the fragment the address had before.
    var pendingOpenMessage by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var hashBeforeHostRequest by remember { mutableStateOf("") }
    fun onAddress(url: String) {
        if (left) return
        address = url
        if (!pageAcked && raftArtifactViewerPageDrawsHostBar(url)) {
            pageAcked = true
            raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_page_bar on=true")
        }
        val hostAction = raftArtifactViewerHostAction(url)
        val asksOpen = raftArtifactViewerAsksHostOpen(url)
        if (hostAction == null && !asksOpen) {
            handledHostActionCleared = true
            hashBeforeHostRequest = url.substringAfter('#', "")
        } else if (
            pendingHostAction == null && pendingOpenMessage == null &&
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
            if (hostAction != null) {
                pendingHostAction = hostAction to url
            } else {
                pendingOpenMessage = raftArtifactViewerHostOpenMessageLink(url, scope.webOrigin) to hashBeforeHostRequest
            }
        }
        val addressNavigation = raftArtifactViewerNavigation(url, scope.webOrigin, shownTarget.id)
        val switched = if (armed) raftArtifactViewerSwitchesInPlace(addressNavigation, conversationId) else null
        if (switched != null) {
            // Picked in the page's title: the same document, now on [switched]. Nothing is waited for (the
            // page writes no `hostLoaded=1` after this move) and the splash state stays as it is.
            raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_switched where=in_place version=${switched.version ?: "latest"}")
            shownTarget = switched
            RaftArtifactViewerMemory.cache.rememberKeptDocument(
                scope,
                switched,
                raftArtifactViewerEmbeddedPageUrl(host.webBaseUrl, switched, conversationId, hostHead, raftEmbeddedWebLangTag()),
                currentAppToken,
            )
            currentOnSwitch(switched)
        }
        when (val navigation = if (switched != null) raftArtifactViewerNavigation(url, scope.webOrigin, switched.id) else addressNavigation) {
            is RaftArtifactViewerNavigation.Stay -> {
                // Only for this Artifact, once the page is on it (a kept page's address may still be the last one's).
                if (pageLoadedAt == null && navigated && raftArtifactViewerPageSaysLoaded(url)) {
                    pageLoadedAt = kotlin.time.TimeSource.Monotonic.markNow()
                    raftLogInfo(
                        RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG,
                        "artifact_viewer_page_loaded sinceSplashOpenedMs=${splashOpenedAt.elapsedNow().inWholeMilliseconds}",
                    )
                }
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
                            sessionLossEvident =
                                pageTokens.sessionLossEvident(currentToken = currentAppToken, nowMs = build.raft.app.data.defaultNowMillis()),
                            sessionRecoveryAvailable = currentSessionRecoveryAvailable(),
                        )
                    when (action) {
                        RaftArtifactViewerLeaveAction.ReloadFresh -> currentOnReloadFresh(to)
                        RaftArtifactViewerLeaveAction.Leave -> currentOnLeave(navigation, to)
                        RaftArtifactViewerLeaveAction.RecoverSession -> currentOnSessionLost(to, true, pageTokens.pageToken)
                        RaftArtifactViewerLeaveAction.SessionLost -> currentOnSessionLost(to, false, pageTokens.pageToken)
                    }
                }
        }
    }
    LaunchedEffect(Unit) { kept?.currentUrl?.let(::onAddress) }
    val currentOnPageLoadStarted by rememberUpdatedState(onPageLoadStarted)
    LaunchedEffect(Unit) {
        RaftArtifactOpenTrace.mark("webview_attach", "kept=${kept != null} showsTarget=$showsTarget")
        if (kept == null) {
            RaftArtifactViewerMemory.cache.rememberKeptDocument(scope, target, pageUrl, session.accessToken)
            // What this new document latches (`hostConversation` and `hostHead` in its first address).
            RaftArtifactViewerMemory.cache.noteKeptBoot(scope, conversationId, documentHostHead)
        }
        // A new document: the page's splash counts from its navigation, so the App's does too.
        if (kept == null) currentOnPageLoadStarted()
    }
    val injected =
        remember(scope, session.accessToken, binding?.webViewGeneration, desktopMode) {
            raftArtifactViewerInjectedScript(scope.webOrigin, raftArtifactViewerSessionScript(scope, session, binding), desktopMode)
        }
    LaunchedEffect(documentReady, navigated) {
        if (!documentReady || navigated) return@LaunchedEffect
        val script =
            raftArtifactViewerNavigateScript(
                scope.webOrigin,
                settingsWebViewAuthStorageJavaScript(session, binding),
                target,
                conversationId,
                hostHead,
                raftEmbeddedWebLangTag(),
            )
        if (controller.evaluateJavaScript(script)) {
            // The kept page routes to this Artifact now: its splash counts from here, so the App's does too.
            currentOnPageLoadStarted()
            // The routed page now holds this session's token.
            pageTokens.onDocumentLoaded(session.accessToken, build.raft.app.data.defaultNowMillis())
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
        val nextHostDesktop = raftArtifactViewerHostDesktopAfterAction(action, hostDesktop)
        if (controller.evaluateJavaScript(raftArtifactViewerHostDesktopScript(scope.webOrigin, nextHostDesktop, clearHostAction = true))) {
            handledHostActionCleared = true
        }
        host.bridge.log("artifact_viewer_host_action action=${action.wireName}")
        val shown = raftArtifactViewerNavigation(url, scope.webOrigin, shownTarget.id) as? RaftArtifactViewerNavigation.Stay
        currentOnHostAction(action, shown?.version)
        pendingHostAction = null
    }
    LaunchedEffect(pendingOpenMessage) {
        val (link, hashBefore) = pendingOpenMessage ?: return@LaunchedEffect
        // The request comes off the address (the fragment it had goes back): the page never left the Artifact.
        if (controller.evaluateJavaScript(raftArtifactViewerHostOpenTakenScript(scope.webOrigin, hashBefore))) {
            handledHostActionCleared = true
        }
        if (link == null) {
            host.bridge.log("artifact_viewer_host_open result=dropped reason=not_a_message_link")
        } else if (inColumn) {
            // The wide layout's Artifact column closes for it, as when the page routed to the message itself.
            host.bridge.log("artifact_viewer_host_open result=column_closes")
            left = true
            currentOnLeave(RaftArtifactViewerNavigation.OpenMessage(link), raftArtifactViewerLogDestination(link))
        } else {
            // Over the viewer, which stays as it is: Back from the conversation returns to this Artifact.
            host.bridge.log("artifact_viewer_host_open result=opened_over to=${raftArtifactViewerLogDestination(link)}")
            RaftMessageLinkIngress.accept(link, "artifact_viewer", scope.webOrigin)
        }
        pendingOpenMessage = null
    }
    LaunchedEffect(visible, address, hostDesktop) {
        if (!visible || address.isBlank() || pendingHostAction != null) return@LaunchedEffect
        if (raftArtifactViewerNavigation(address, scope.webOrigin, shownTarget.id) !is RaftArtifactViewerNavigation.Stay) return@LaunchedEffect
        if (raftArtifactViewerAddressSaysDesktop(address, hostDesktop)) return@LaunchedEffect
        controller.evaluateJavaScript(raftArtifactViewerHostDesktopScript(scope.webOrigin, hostDesktop))
    }
    // `hostHead` too follows the input in use (a mouse used, then a finger) and comes back if a route dropped it.
    LaunchedEffect(visible, address, hostHead) {
        if (!visible || address.isBlank() || pendingHostAction != null) return@LaunchedEffect
        if (raftArtifactViewerNavigation(address, scope.webOrigin, shownTarget.id) !is RaftArtifactViewerNavigation.Stay) return@LaunchedEffect
        if (raftArtifactViewerAddressSaysHostHead(address, hostHead)) return@LaunchedEffect
        controller.evaluateJavaScript(raftArtifactViewerHostHeadScript(scope.webOrigin, hostHead))
    }
    val drawsBar = pageAcked && visible && !loadError
    // #301: one loader. The App's splash covers the page until the page says its content loaded
    // (`hostLoaded=1`) and plays its ending; an older web that never says so is shown at the fallback
    // (it drew its bar, `hostToolbar=1`, by then: its own splash, on the same clock, or its content).
    var documentReadyAt by remember { mutableStateOf<kotlin.time.TimeMark?>(null) }
    LaunchedEffect(documentReady) { documentReadyAt = if (documentReady) kotlin.time.TimeSource.Monotonic.markNow() else null }
    // A kept document is loaded already (no new load to finish): its fallback counts from when it shows.
    var keptShownAt by remember { mutableStateOf<kotlin.time.TimeMark?>(null) }
    LaunchedEffect(visible) { if (visible && kept != null && keptShownAt == null) keptShownAt = kotlin.time.TimeSource.Monotonic.markNow() }
    val coverFallbackFrom = documentReadyAt ?: keptShownAt
    var coverFallbackTick by remember { mutableStateOf(0) }
    LaunchedEffect(coverFallbackFrom) {
        if (coverFallbackFrom == null) return@LaunchedEffect
        kotlinx.coroutines.delay(RAFT_ARTIFACT_VIEWER_SPLASH_FALLBACK_MS)
        coverFallbackTick += 1
    }
    val loadingIndicator =
        raftArtifactViewerLoadingIndicator(
            RaftArtifactViewerOpenState(
                sessionRead = true,
                documentShown = visible,
                reusesKeptPage = kept != null && keptDrewBar,
                loadError = loadError,
                sinceDocumentReadyMs = coverFallbackFrom?.elapsedNow()?.inWholeMilliseconds.also { coverFallbackTick },
                pageLoaded = pageLoadedAt != null,
                splashEnded = splashEnded,
                pageDrawsBar = drawsBar,
            )
        )
    LaunchedEffect(loadingIndicator) {
        raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_loading_indicator shown=${loadingIndicator.logToken}")
    }
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
    val currentShownTarget by rememberUpdatedState(shownTarget)
    val currentLeft by rememberUpdatedState(left)
    val currentLoadError by rememberUpdatedState(loadError)
    val currentVisible by rememberUpdatedState(visible)
    val currentNavigated by rememberUpdatedState(navigated)
    val currentHostHeadMoved by rememberUpdatedState(hostHeadMoved)
    DisposableEffect(Unit) {
        onDispose {
            raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_closed $latestState")
            // Keep-alive parks this page's WebView right as it leaves composition: align cache metadata with
            // the document actually parked (including when a stacked Artifact viewer closed before this one),
            // and never leave an un-embedded or stale-scope document parked in keep-alive.
            val parked = raftWebViewKeptDocument(scope.keepAliveKey) ?: return@onDispose
            if (host.scope() != scope) {
                raftWebViewKeepAliveClear()
                return@onDispose
            }
            if (!raftArtifactViewerKeptDocumentHasEmbedContract(parked)) {
                raftLogWarn(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_kept_dropped reason=dispose_embed_contract_missing")
                RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
                raftWebViewKeepAliveClear()
            } else if (currentHostHeadMoved) {
                raftLogInfo(RAFT_ARTIFACT_VIEWER_DIAGNOSTICS_TAG, "artifact_viewer_kept_dropped reason=head_changed_while_shown")
                RaftArtifactViewerMemory.cache.forgetKeptDocument(scope)
                raftWebViewKeepAliveClear()
            } else if (!currentLeft && !currentLoadError && currentVisible && currentNavigated) {
                RaftArtifactViewerMemory.cache.rememberKeptDocument(
                    scope,
                    currentShownTarget,
                    raftArtifactViewerEmbeddedPageUrl(host.webBaseUrl, currentShownTarget, conversationId, documentHostHead, raftEmbeddedWebLangTag()),
                    currentAppToken,
                )
                RaftArtifactViewerMemory.cache.noteKeptBoot(scope, conversationId, documentHostHead)
            }
        }
    }
    val density = LocalDensity.current
    val bottomInsetPx =
        raftArtifactViewerWebBottomInsetPx(
            imeBottomPx = raftImeBottomPx(density),
            navigationBarBottomPx = with(density) { raftBottomSafeAreaPadding().roundToPx() },
            hostPadsWebView = raftWebViewNeedsHostBottomInsets,
        )
    // The page, and over it the App's splash while it covers the page (one loader at a time).
    Box(Modifier.fillMaxSize()) {
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
            ephemeral = raftPlatformUsesEphemeralAuthWebView(),
            injectedJavaScript = injected,
            hostEventsEnabled = false,
            controller = controller,
            allowedNavigationSchemes = setOf("http", "https"),
            mainFrameOrigin = scope.webOrigin,
            // The content frame (desktop declares it; see RaftWebViewConfig.frameHosts).
            frameHosts = setOf(ARTIFACT_CONTENT_DOMAIN),
            keepAliveKey = scope.keepAliveKey,
            diagnosticsSurface = RAFT_ARTIFACT_VIEWER_WEBVIEW_SURFACE,
            // #proj-workspace:#360: pinch-to-zoom stays available outside Desktop Mode too.
            allowPinchZoom = true,
            loadWatchdog = loadWatchdog,
            onLoadCommit = {
                // A new document: it holds the token its injected session wrote.
                pageTokens.onDocumentLoaded(currentAppToken, build.raft.app.data.defaultNowMillis())
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
                val configuredWebBaseUrl = standaloneSettingsWebBaseUrl(host.sharedStores.apiEnvironmentStore)
                fun openExternallyRespectingAppStore(targetUrl: String) {
                    if (
                        raftPlatformFollowsAppStoreBillingRules() &&
                        raftUrlIsAppStoreRestrictedPurchaseUrl(targetUrl, configuredWebBaseUrl)
                    ) {
                        host.bridge.logWarn("artifact_viewer_appstore_purchase_blocked url=${raftOwnWebUrlForLog(targetUrl)}")
                        if (raftUrlIsOwnBillingPath(targetUrl, configuredWebBaseUrl)) {
                            host.pager.openSettingsBillingPage(host.storeScope)
                        }
                        return
                    }
                    host.bridge.openUrl(targetUrl, inBrowser = true)
                }
                when (val routed = raftArtifactViewerBlockedNavigationTarget(blocked, scope.webOrigin, shownTarget.id)) {
                    is RaftArtifactViewerBlockedTarget.RaftWebPage ->
                        if (!host.pager.openRaftOwnWebPage(routed.url, host.openStoreScope, activeWebBaseUrl = configuredWebBaseUrl)) {
                            openExternallyRespectingAppStore(routed.url)
                        }
                    is RaftArtifactViewerBlockedTarget.SystemBrowser -> openExternallyRespectingAppStore(routed.url)
                    // A save the page asked for as a new window: an http(s) address on the page's origin is
                    // downloaded with the app's session; a `blob:` one cannot be read from here.
                    RaftArtifactViewerBlockedTarget.Download ->
                        currentOnDownload(
                            if (blocked.trim().startsWith("blob:", ignoreCase = true)) {
                                RaftWebViewDownload.Failed("blob_new_window")
                            } else {
                                RaftWebViewDownload.Url(blocked.trim(), pageFileName = null, contentDisposition = null, mimeType = null)
                            }
                        )
                    is RaftArtifactViewerBlockedTarget.OpenMessage -> RaftMessageLinkIngress.accept(routed.url, "artifact_viewer", scope.webOrigin)
                    is RaftArtifactViewerBlockedTarget.OpenArtifact ->
                        if (!inColumn || !host.pager.openRaftArtifactViewerPage(routed.target, host.openStoreScope)) {
                            RaftArtifactLinkInbox.Process.submit(routed.target, "artifact_viewer")
                        }
                    RaftArtifactViewerBlockedTarget.Ignore -> {
                        val sameArtifact = raftArtifactViewerNavigation(blocked, scope.webOrigin, shownTarget.id) as? RaftArtifactViewerNavigation.Stay
                        if (sameArtifact != null && !raftArtifactViewerHasEmbedContract(blocked)) {
                            val currentStay = raftArtifactViewerNavigation(address, scope.webOrigin, shownTarget.id) as? RaftArtifactViewerNavigation.Stay
                            val currentVersion = if (currentStay != null) currentStay.version else shownTarget.version
                            if (sameArtifact.version != currentVersion) {
                                controller.evaluateJavaScript(
                                    raftArtifactViewerNavigateScript(
                                        scope.webOrigin,
                                        settingsWebViewAuthStorageJavaScript(session, binding),
                                        ArtifactLinkTarget(shownTarget.id, sameArtifact.version),
                                        conversationId,
                                        hostHead,
                                        raftEmbeddedWebLangTag(),
                                    )
                                )
                            }
                        }
                    }
                }
            },
            // A file the page saves (Android DownloadListener, an iOS attachment response): the browser can.
            onDownloadRequested = { download -> currentOnDownload(download) },
            // A Raft link the page opened in place (Android: `window.open` loads in this one window): routed
            // as the blocked navigation above, the viewer staying, as on the web and iOS.
            routesMainFrameNavigation = { url -> raftArtifactViewerRoutesDocumentAsLink(url, scope.webOrigin, shownTarget.id) },
        )
        SettingsWebViewAccessTokenSyncEffect(
            snapshot = snapshot,
            documentReady = documentReady,
            controller = controller,
            binding = binding,
            surface = SettingsWebViewSurface.ArtifactViewer,
            logger = host.bridge::log,
            refresher = accessTokenRefresher,
            visible = rememberRaftPagerAppeared(host.pager),
            onAccessTokenDispatched = { token -> pageTokens.onPushed(token, build.raft.app.data.defaultNowMillis()) },
            webOrigin = scope.webOrigin,
        )
        if (loadError) {
            SettingsWebViewErrorContent(
                actionLabel = stringResource(Res.string.common_retry),
                actionIcon = RaftLucideIconName.RefreshCw,
                body = settingsWebViewErrorBody(loadWatchdog.state.phase),
                onAction = onRetry,
            )
        }
    }
    if (loadingIndicator == RaftArtifactViewerLoadingIndicator.AppSplash) {
        RaftArtifactViewerSplashCover(
            splashKinds,
            splashOpenedAt,
            desktopMode,
            underPageBar = drawsBar,
            loadedAt = pageLoadedAt,
            onGone = { splashEnded = true },
        )
    }
    }
}

/** The App's splash cover over the viewer's body ([RaftArtifactViewerSplashCover]), for tests. */
internal const val RAFT_ARTIFACT_VIEWER_SPLASH_COVER_TAG = "artifact_viewer_splash_cover"

/**
 * The App's loading splash over the viewer's body (#301): the web's ArtifactLoadingSplash, ported
 * ([RaftArtifactLoadingSplash]), where the page's own will show, so the hand-over moves nothing.
 * The page's splash lies over its content area, under its toolbar and above its bottom safe area;
 * that toolbar takes the place of the App's bar when the page draws itself. In the Artifact column
 * it is one row exactly the App's head height, which the App sends as `hostHead`
 * ([raftArtifactViewerHostHead]), so centred in the body below the App's bar the icon is where the
 * page's will be. A page of its own (a phone) gets no `hostHead` and keeps its own toolbar, whose
 * height is the page's, not the App's bar's. The bottom: what the WebView is padded by (Android) or
 * the safe area the page keeps (iOS, `safe-bottom`). In Desktop Mode the page is fitted from a
 * desktop width, so its splash is drawn smaller: scaled alike.
 * The kind is the card's (null: a document and "Loading Artifact", as the page's link screen); one
 * learned mid-cycle takes over at the next cycle ([RaftArtifactSplashKindState]). [loadedAt]: the
 * page said its content loaded: the ending plays, then [onGone].
 *
 * One bar (#301): while the App's bar is up the cover hides the whole body, the page's own loading
 * toolbar row (Back alone, `back=host`) too, so no second bar shows under the App's; [underPageBar]
 * (the page draws the only bar, `hostToolbar=1`, the App's is gone) it starts under the page's bar
 * ([raftArtifactViewerSplashCoverTop]: the App's head height, which the page's bar has in the
 * Artifact column), so that bar shows and the splash does not move.
 */
@Composable
internal fun RaftArtifactViewerSplashCover(
    kinds: RaftArtifactSplashKindState,
    openedAt: kotlin.time.TimeMark,
    desktopMode: Boolean,
    underPageBar: Boolean = false,
    loadedAt: kotlin.time.TimeMark? = null,
    onGone: () -> Unit = {},
) {
    val density = LocalDensity.current
    val bottomPx =
        if (raftWebViewNeedsHostBottomInsets) {
            raftArtifactViewerWebBottomInsetPx(
                imeBottomPx = raftImeBottomPx(density),
                navigationBarBottomPx = with(density) { raftBottomSafeAreaPadding().roundToPx() },
                hostPadsWebView = true,
            )
        } else {
            with(density) { raftBottomSafeAreaPadding().roundToPx() }
        }
    val windowWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp().value }
    val widthDp = LocalRaftArtifactViewerColumnWidthDp.current ?: windowWidthDp
    val scale = raftArtifactViewerSplashScale(desktopMode, widthDp)
    val loadingArtifact = stringResource(Res.string.artifacts_card_loading)
    val gettingPage = stringResource(Res.string.artifacts_viewer_getting_page)
    val top = raftArtifactViewerSplashCoverTop(underPageBar, RaftTheme.controls.headHeight, scale)
    Box(
        Modifier
            .fillMaxSize()
            .padding(top = top)
            .testTag(RAFT_ARTIFACT_VIEWER_SPLASH_COVER_TAG)
            .background(RaftColor.Canvas)
            .padding(bottom = with(density) { bottomPx.toDp() })
    ) {
        RaftArtifactLoadingSplash(
            kinds = kinds,
            labelFor = { kind -> if (kind == null) loadingArtifact else gettingPage },
            openedAt = openedAt,
            loadedAt = loadedAt,
            scale = scale,
            onGone = onGone,
        )
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

/**
 * Saves a file the page saved ([RaftWebViewDownload]) as a message attachment is saved (Android:
 * Downloads; iOS: the system's save sheet), with the name the page gave it:
 * - [RaftWebViewDownload.Bytes] (a `blob:` save read in the page, an iOS WKDownload): from those
 *   bytes, under a `raft-local:` address never fetched;
 * - [RaftWebViewDownload.Url] (an http(s) address on the page's own web origin): downloaded with the
 *   app's own session (in memory for that transfer, never over the bridge), named by the page's
 *   `download` name or else the response's Content-Disposition.
 * A failure says so (with the response's trace id when one was answered); nothing opens the browser.
 */
private suspend fun raftArtifactViewerSaveDownload(
    host: RaftArtifactViewerHost,
    scope: RaftArtifactViewerScope,
    download: RaftWebViewDownload,
    savedMessage: String,
    savedToDownloadsMessage: String,
    alreadySavedMessage: String,
    savedToFilesMessage: String,
    failedMessage: String,
    openActionLabel: String,
    openResultTerms: RaftAttachmentOpenResultTerms,
) {
    raftSaveArtifactDownloadWithOpenToast(
        bridge = host.bridge,
        scope = scope,
        owner = currentAttachmentOwnerLease(host.storeScope),
        readSession = {
            runCatchingPreservingCancellation { host.sharedStores.authStore(host.storeScope).readSession() }
                .getOrNull()
        },
        download = download,
        savedMessage = savedMessage,
        savedToDownloadsMessage = savedToDownloadsMessage,
        alreadySavedMessage = alreadySavedMessage,
        savedToFilesMessage = savedToFilesMessage,
        failedMessage = failedMessage,
        openActionLabel = openActionLabel,
        openResultTerms = openResultTerms,
    )
}

internal suspend fun raftSaveArtifactDownloadWithOpenToast(
    bridge: BridgeModule,
    scope: RaftArtifactViewerScope,
    owner: AttachmentOwnerLease?,
    readSession: suspend () -> StoredAuthSession?,
    download: RaftWebViewDownload,
    savedMessage: String,
    savedToDownloadsMessage: String,
    alreadySavedMessage: String,
    savedToFilesMessage: String,
    failedMessage: String,
    openActionLabel: String,
    openResultTerms: RaftAttachmentOpenResultTerms,
    hasDesktopDownloadBar: Boolean = raftPlatformSavedFileHost() != null,
    openScope: kotlinx.coroutines.CoroutineScope = raftSavedDownloadToastOpenScope,
) {
    var savedAttachmentId = ""
    var fallbackFileName = ""
    var savedMimeType: String? = null
    val result: JSONObject? =
        when (download) {
            is RaftWebViewDownload.Failed -> {
                bridge.log("artifact_viewer_download action=save result=failed reason=${download.reason}")
                bridge.errorToast(failedMessage)
                return
            }
            is RaftWebViewDownload.Bytes -> {
                if (owner == null) {
                    bridge.log("artifact_viewer_download action=save kind=bytes result=no_owner")
                    bridge.errorToast(failedMessage)
                    return
                }
                bridge.log("artifact_viewer_download action=save kind=bytes bytes=${download.bytes.size}")
                val token = raftArtifactViewerDownloadCacheId(scope.serverId, download.fileName + "|" + kotlin.random.Random.nextLong())
                savedAttachmentId = token
                fallbackFileName = download.fileName
                savedMimeType = download.mimeType
                bridge.saveAttachment(
                    url = "${AttachmentDownloadLocalBytes.SCHEME}://download/$token",
                    attachmentId = token,
                    fileName = download.fileName,
                    owner = owner,
                    mimeType = download.mimeType,
                    localBytes = AttachmentDownloadLocalBytes.Source(download.bytes, download.fileName, download.mimeType),
                )
            }
            is RaftWebViewDownload.Url -> {
                val session =
                    readSession()
                        ?.takeIf { it.user.id.trim() == scope.accountId && it.accessToken.isNotBlank() }
                if (owner == null || session == null || raftWebOriginOf(download.url) != raftWebOriginOf(scope.webOrigin)) {
                    bridge.log("artifact_viewer_download action=save kind=url result=refused")
                    bridge.errorToast(failedMessage)
                    return
                }
                bridge.log("artifact_viewer_download action=save kind=url")
                val pageName = download.pageFileName
                val cacheId = raftArtifactViewerDownloadCacheId(scope.serverId, download.url)
                val requestedName = raftWebViewDownloadFileName(pageName, download.contentDisposition, download.url, download.mimeType)
                savedAttachmentId = cacheId
                fallbackFileName = requestedName
                savedMimeType = download.mimeType
                bridge.saveAttachment(
                    url = download.url,
                    attachmentId = cacheId,
                    fileName = requestedName,
                    owner = owner,
                    mimeType = download.mimeType,
                    // The page's own name wins; else the name the response gives.
                    fileNameFromResponse = pageName == null,
                    sessionHeaders =
                        mapOf(
                            HEADER_AUTHORIZATION to "Bearer ${session.accessToken}",
                            HEADER_SERVER_ID to scope.serverId,
                        ),
                )
            }
        }
    when (val status = result?.optString("status").orEmpty()) {
        "saved" -> {
            bridge.log("artifact_viewer_download action=save result=saved")
            val toastMessage =
                raftArtifactViewerSavedToast(
                    result,
                    saved = savedMessage,
                    savedToDownloads = savedToDownloadsMessage,
                    alreadySaved = alreadySavedMessage,
                    savedToFiles = savedToFilesMessage,
                )
            val openTarget =
                owner?.let { currentOwner ->
                    raftSavedDownloadTargetFromResult(
                        attachmentId = savedAttachmentId,
                        fallbackFileName = fallbackFileName,
                        owner = currentOwner,
                        mimeType = savedMimeType,
                        result = result,
                    )
                }
            raftShowSavedDownloadToast(
                bridge = bridge,
                message = toastMessage,
                openLabel = openActionLabel,
                target = openTarget,
                openResultTerms = openResultTerms,
                hasDesktopDownloadBar = hasDesktopDownloadBar,
                openScope = openScope,
            )
        }
        "cancelled" -> bridge.log("artifact_viewer_download action=save result=cancelled")
        else -> {
            bridge.log(
                "artifact_viewer_download action=save result=failed status=${status.ifBlank { "none" }} " +
                    "errorKind=${result?.optString("errorKind").orEmpty().ifBlank { "none" }}"
            )
            bridge.errorToast(failedMessage)
        }
    }
}

/**
 * The toast after the host saved a file an Artifact page downloads, by the result's status words,
 * never its `message` (the host's own English, for diagnostics). Android answers a file it had
 * saved before with `alreadySaved`, and a copy committed to Downloads with `cached`; any other
 * save (the iOS Files export, desktop) says [saved].
 */
internal fun raftArtifactViewerSavedToast(
    result: JSONObject?,
    saved: String,
    savedToDownloads: String,
    alreadySaved: String,
    savedToFiles: String,
): String =
    when {
        result?.optBoolean("alreadySaved") == true -> alreadySaved
        result?.optBoolean("cached") == true -> savedToDownloads
        // iOS: exported through the Files picker (HRBridgeModule `savedTo`).
        result?.optString("savedTo") == "files" -> savedToFiles
        else -> saved
    }

/** A file-name-safe key for the device copy of [url] in [serverId]: `artifact-<16 hex>` (FNV-1a 64). */
internal fun raftArtifactViewerDownloadCacheId(serverId: String, url: String): String {
    var hash = -0x340d631b7bdddcdbL // FNV offset basis 14695981039346656037
    for (byte in "$serverId|$url".encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return "artifact-" + hash.toULong().toString(16).padStart(16, '0')
}
