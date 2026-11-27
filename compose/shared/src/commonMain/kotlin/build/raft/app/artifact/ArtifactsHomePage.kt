package build.raft.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.base.Utils
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactOwnFollowEvent
import build.raft.app.data.ArtifactsHomeEvent
import build.raft.app.data.ArtifactsHomeFilterRepository
import build.raft.app.data.ArtifactsHomeIntent
import build.raft.app.data.ArtifactsHomePreferenceRepository
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.ArtifactsHomeSettledSeenRepository
import build.raft.app.data.ArtifactsHomeSnapshotCache
import build.raft.app.data.ArtifactsHomeSort
import build.raft.app.data.ArtifactsHomeSource
import build.raft.app.data.ArtifactsHomeState
import build.raft.app.data.ArtifactsHomeStatus
import build.raft.app.data.NetworkRaftArtifactsDashboardApi
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.SharedArtifactsHomeStore
import build.raft.app.data.WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.asArtifactsHomeSource
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ServerId
import build.raft.app.resources.*
import build.raft.app.settings.LocalRaftSettingsWideDetail
import build.raft.app.settings.currentServerSummary
import build.raft.app.settings.settingsStandalonePageData
import build.raft.app.settings.standaloneSettingsWebBaseUrl
import build.raft.app.thread.ArtifactCardScreenshotView
import build.raft.app.thread.LocalArtifactScreenshotReader
import build.raft.app.thread.dispatch
import build.raft.app.thread.key
import build.raft.app.thread.rememberArtifactCardScreenshot
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftPaneGridExpand
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The Artifacts home page (web route `/artifacts`, `ArtifactsDashboard`). */
internal const val ARTIFACTS_HOME_PAGE = "compose_artifacts_home"

@Page(ARTIFACTS_HOME_PAGE, supportInLocal = true)
internal class ComposeArtifactsHomePage : AuthenticatedProductPager(AuthenticatedProductRoute.ArtifactsHome) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { ArtifactsHomeRoute(raftPageVisibleResumeTick, raftPageVisible) }
    }
}

/**
 * web `useArtifactsRailShown`: whether the app's Artifacts entries show (the server panel's row, the
 * wide rail's tab): the current server's workspace_artifacts_v0, as already loaded, on; a flag not
 * yet known is hidden.
 */
internal fun appShellArtifactsShown(flags: build.raft.app.data.SharedServerFeatureFlags, serverId: String?): Boolean {
    val id = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return !flags.isDecisionPending(ServerId(id), WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY) && flags.isEnabled(ServerId(id), WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY)
}

/** Identity only (the Route / PageData rule): the shell scope; the page reads its own list. */
internal fun artifactsHomePageData(storeScope: RaftShellStoreScope): JSONObject = settingsStandalonePageData(ARTIFACTS_HOME_PAGE, storeScope)

internal fun Pager.openArtifactsHomePage(storeScope: RaftShellStoreScope) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(ARTIFACTS_HOME_PAGE, artifactsHomePageData(storeScope))
}

internal class ArtifactsHomeViewModel(
    source: ArtifactsHomeSource,
    initialSort: ArtifactsHomeSort,
    onSortChosen: (ArtifactsHomeSort) -> Unit,
    logger: (String) -> Unit,
    filterRepository: ArtifactsHomeFilterRepository,
    settledSeenRepository: ArtifactsHomeSettledSeenRepository,
    ownFollowEvents: SharedFlow<ArtifactOwnFollowEvent>? = null,
    onOwnFollowCommitted: (serverId: String, artifactId: String, following: Boolean) -> Unit = { _, _, _ -> },
    snapshotCache: ArtifactsHomeSnapshotCache? = null,
    environmentIdProvider: () -> String = { "" },
    initialServerId: String? = null,
    initialReaderId: String? = null,
) : ViewModel() {
    val store =
        SharedArtifactsHomeStore(
            api = source,
            scope = viewModelScope,
            initialSort = initialSort,
            onSortChosen = onSortChosen,
            logger = logger,
            filterRepository = filterRepository,
            settledSeenRepository = settledSeenRepository,
            ownFollowEvents = ownFollowEvents,
            onOwnFollowCommitted = onOwnFollowCommitted,
            snapshotCache = snapshotCache,
            environmentIdProvider = environmentIdProvider,
            initialServerId = initialServerId,
            initialReaderId = initialReaderId,
        )
    val state: StateFlow<ArtifactsHomeState> get() = store.state

    override fun onCleared() {
        store.clear()
        super.onCleared()
    }
}

/** Who reads and where: the current server, its name, the signed-in person, and whether readSession has landed. */
private data class ArtifactsHomeSession(
    val serverId: String?,
    val serverName: String?,
    val readerId: String?,
    val resolved: Boolean,
)

/**
 * Wires [ArtifactsHomeScreen]: the store (`dashboardAll` through the shell's network client), the
 * server's workspace_artifacts_v0 (live: off once resolved off is the off state, web parity), the
 * device prefs (view, sort), per-(account, server) persisted filters and settled-seen state,
 * `activity:changed` while the page shows, and the in-app viewer: a row opens the latest version
 * (web `version: null`), and coming back from it reads the list again.
 */
@Composable
internal fun ArtifactsHomeRoute(pageVisibleResumeTick: Int = 0, pageVisible: Boolean = true) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val prefsRepository = remember { ArtifactsHomePreferenceRepository(raftSharedPreferenceStore()) }
    var prefs by remember { mutableStateOf(prefsRepository.read()) }
    val initialSession =
        remember(sharedStores, storeScope) {
            val committed = sharedStores.authStore.readCommittedSessionSnapshot()
            val committedReader = sharedStores.currentAccountBindingOrNull()?.second ?: committed?.user?.id?.takeIf { it.isNotBlank() }
            val committedServer = storeScope.initialServerId ?: committed?.currentServerId?.takeIf { it.isNotBlank() }
            ArtifactsHomeSession(
                serverId = committedServer,
                serverName = committed?.currentServerSummary()?.name,
                readerId = committedReader,
                resolved = committedReader != null,
            )
        }
    val flags = sharedStores.serverFeatureFlags
    val initialFlagEnabled =
        remember(initialSession.serverId, flags) {
            val sid = initialSession.serverId
            sid != null && flags.isEnabled(ServerId(sid), WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY)
        }
    val viewModel: ArtifactsHomeViewModel =
        viewModel(key = "artifacts-home-${storeScope.shellInstanceId}") {
            ArtifactsHomeViewModel(
                source = NetworkRaftArtifactsDashboardApi(networkClient).asArtifactsHomeSource(),
                initialSort = prefs.sort,
                onSortChosen = { sort -> prefsRepository.write(prefsRepository.read().copy(sort = sort)) },
                logger = { bridge.log(it) },
                filterRepository = sharedStores.artifactsHomeFilterRepository,
                settledSeenRepository = sharedStores.artifactsHomeSettledSeenRepository,
                ownFollowEvents = sharedStores.artifactFollowStore.events,
                onOwnFollowCommitted = { srv, id, following -> sharedStores.artifactFollowStore.reportOwnFollow(srv, id, following) },
                snapshotCache = sharedStores.artifactsHomeSnapshotCache,
                environmentIdProvider = { sharedStores.apiEnvironmentStore.current().apiBaseUrl },
                initialServerId = initialSession.serverId?.takeIf { initialSession.resolved && initialFlagEnabled },
                initialReaderId = initialSession.readerId,
            )
        }
    val state by viewModel.state.collectAsState()
    val session by produceState(initialSession, authStore) {
        suspend fun read() {
            val read = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
            val next =
                if (read == null) {
                    value.copy(resolved = true)
                } else {
                    ArtifactsHomeSession(
                        serverId = read.currentServerId?.takeIf { it.isNotBlank() },
                        serverName = read.currentServerSummary()?.name,
                        readerId = read.user.id.takeIf { it.isNotBlank() },
                        resolved = true,
                    )
                }
            if (next != value) value = next
        }
        read()
        // A server switch while the page shows (the wide rail's inline page): another server's list.
        val events = (authStore as? build.raft.app.data.RaftAuthSessionEventSource)?.authSessionEvents ?: return@produceState
        events.collect { read() }
    }
    val serverId = session.serverId
    // workspace_artifacts_v0, live (web `useServerFeatureFlag`): unknown while it loads.
    val revision by flags.snapshotRevision.collectAsState()
    val flagEnabled = remember(serverId, revision) { serverId != null && flags.isEnabled(ServerId(serverId), WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY) }
    val flagPending = remember(serverId, revision) { serverId == null || flags.isDecisionPending(ServerId(serverId), WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY) }
    // Wait until readerId is resolved before starting, so persisted filters restore on frame 1 without flashing unfiltered rows.
    LaunchedEffect(serverId, session.readerId, session.resolved, flagEnabled) {
        if (!session.resolved) return@LaunchedEffect
        viewModel.store.dispatch(ArtifactsHomeIntent.Start(serverId?.takeIf { flagEnabled }, session.readerId))
    }
    ArtifactsHomeActivityEffect(storeScope, serverId?.takeIf { flagEnabled && pageVisible }) { viewModel.store.dispatch(it) }
    // #wg-mobile-feedback:#274: store owns RaftFreshnessSignal subscription and outcome-tracked gate.
    LaunchedEffect(viewModel.store, pageVisible) {
        viewModel.store.dispatch(ArtifactsHomeIntent.SetPageVisible(pageVisible))
    }
    // Back from the viewer: opening it read the current version, so what was new may be gone.
    LaunchedEffect(pageVisibleResumeTick) {
        if (pageVisibleResumeTick > 0 && viewModel.state.value.viewerOpen) viewModel.store.dispatch(ArtifactsHomeIntent.ViewerClosed)
    }
    val sortFailed = stringResource(Res.string.artifacts_dashboard_failed_title)
    val moreFailed = stringResource(Res.string.artifacts_dashboard_more_failed)
    val settleFailed = stringResource(Res.string.artifacts_settled_failed)
    val settleForbidden = stringResource(Res.string.artifacts_settled_forbidden)
    val unfollowedToast = stringResource(Res.string.artifacts_follow_unfollowed)
    val undoLabel = stringResource(Res.string.artifacts_follow_undo)
    val followFailed = stringResource(Res.string.artifacts_follow_failed)
    val copiedMessage = stringResource(Res.string.common_copied)
    val toastHost = LocalRaftToastHostState.current
    val currentToastHost by rememberUpdatedState(toastHost)
    val undoToastIds = remember { mutableMapOf<String, Long>() }
    LaunchedEffect(viewModel) {
        viewModel.store.events.collect { event ->
            when (event) {
                is ArtifactsHomeEvent.SortFailed -> bridge.errorToast(sortFailed, event.error)
                is ArtifactsHomeEvent.MoreFailed -> event.error?.let { bridge.errorToast(moreFailed, it) } ?: bridge.toast(moreFailed)
                is ArtifactsHomeEvent.SettleFailed -> {
                    val message = if (event.forbidden) settleForbidden else settleFailed
                    event.error?.let { bridge.errorToast(message, it) } ?: bridge.toast(message)
                }
                is ArtifactsHomeEvent.Unfollowed -> {
                    undoToastIds.remove(event.artifactId)?.let { currentToastHost?.dismiss(it) }
                    val undo = { viewModel.store.dispatch(ArtifactsHomeIntent.SetFollowing(event.artifactId, true)) }
                    val host = currentToastHost
                    if (host != null) {
                        undoToastIds[event.artifactId] = host.show(title = unfollowedToast, actionLabel = undoLabel, onAction = undo)
                    } else {
                        bridge.toastWithAction(unfollowedToast, undoLabel, undo)
                    }
                }
                is ArtifactsHomeEvent.Followed -> {
                    undoToastIds.remove(event.artifactId)?.let { currentToastHost?.dismiss(it) }
                }
                is ArtifactsHomeEvent.FollowFailed -> {
                    event.error?.let { bridge.errorToast(followFailed, it) } ?: bridge.toast(followFailed)
                }
            }
        }
    }
    val openArtifactWebPage = rememberRaftWebPageOpener()
    val webBaseUrl = remember(sharedStores) { standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore) }
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val wide = LocalRaftSettingsWideDetail.current || windowWidth.value >= DualPaneMinimumWidthDp
    var artifactPane by remember { mutableStateOf(RaftArtifactColumnPane()) }
    val splitExpand = remember { RaftPaneGridExpand() }
    var wasSplitOpen by remember { mutableStateOf(false) }
    val shellHost = LocalRaftHost.current
    // Server switch closes the previous server's split viewer column.
    LaunchedEffect(serverId) {
        if (artifactPane.isOpen) {
            artifactPane = artifactPane.cleared()
            splitExpand.restore()
            wasSplitOpen = false
        }
    }
    // Shrinking from wide split column to narrow promotes the open viewer onto the full-screen stack.
    LaunchedEffect(wide, artifactPane.entry?.id) {
        val openEntry = artifactPane.entry
        if (!wide && openEntry != null) {
            artifactPane = artifactPane.cleared()
            splitExpand.restore()
            wasSplitOpen = false
            viewModel.store.dispatch(ArtifactsHomeIntent.ViewerOpened)
            shellHost.navigator.openPage(openEntry.pageName, openEntry.pageData)
        }
    }
    // Closing the split viewer on wide refreshes read state (while open beside the list, live activity refreshes stay active).
    LaunchedEffect(wide, artifactPane.isOpen) {
        if (!wide) return@LaunchedEffect
        val openNow = artifactPane.isOpen
        if (wasSplitOpen && !openNow) {
            viewModel.store.dispatch(ArtifactsHomeIntent.ViewerClosed)
        }
        wasSplitOpen = openNow
    }
    val screenshots = remember(sharedStores, serverId) { serverId?.let { sharedStores.artifactScreenshotStore.reader(it) } }
    // A flag resolved off (or a 403) is the off state; nothing is read.
    val shown = if (!flagPending && !flagEnabled) state.copy(status = ArtifactsHomeStatus.Off) else state
    CompositionLocalProvider(LocalArtifactScreenshotReader provides screenshots) {
        ArtifactsHomeSplitLayout(
            wide = wide,
            artifact = { artifactPane },
            onArtifactChange = { artifactPane = it },
            active = pageVisible,
            expand = splitExpand,
            modifier = Modifier.fillMaxSize(),
        ) {
            // Composed inside ArtifactsHomeSplitLayout so LocalRaftArtifactOpenInColumn matches wide split vs full screen.
            val opener =
                rememberRaftArtifactOpener(
                    conversationId = null,
                    storeScope = storeScope,
                    openPageOverride =
                        if (wide) {
                            { target, scopeOverride, conv ->
                                val resolvedScope = sharedStores.currentNavigationStoreScope(scopeOverride ?: storeScope)
                                val data = raftArtifactViewerPageData(target, conv).withRaftShellStoreScope(resolvedScope).toJsonObject()
                                splitExpand.restore()
                                artifactPane = artifactPane.open(RAFT_ARTIFACT_VIEWER_PAGE, data)
                                true
                            }
                        } else {
                            null
                        },
                ) { openArtifactWebPage(artifactWebUrl(webBaseUrl, it)) }
            RaftArtifactViewerPrewarm(enabled = pageVisible && !state.rows.isNullOrEmpty(), conversationId = null, storeScope = storeScope)
            ArtifactsHomeScreen(
                state = shown,
                view = prefs.view,
                readerId = session.readerId,
                serverName = session.serverName,
                wide = wide,
                onIntent = viewModel.store::dispatch,
                onView = { view ->
                    prefs = prefsRepository.read().copy(view = view)
                    prefsRepository.write(prefs)
                },
                onOpen = { row ->
                    val target = ArtifactLinkTarget(row.summary.id)
                    if (!wide) viewModel.store.dispatch(ArtifactsHomeIntent.ViewerOpened)
                    opener.open(target, RaftArtifactViewerHeader.of(row.summary, target), "artifacts_home")
                },
                onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
                onCopyLink = { row ->
                    bridge.copyToPasteboard(artifactWebUrl(webBaseUrl, ArtifactLinkTarget(row.summary.id)))
                    bridge.toast(copiedMessage)
                },
                cover = { row, dimmed, corner -> ArtifactsHomeTileCover(row, dimmed, corner) },
                thumbnail = { row, dimmed -> ArtifactsHomeRowThumbnail(row, dimmed) },
                currentArtifactId = artifactPane.currentArtifactIdOrNull().takeIf { wide },
            )
        }
    }
}

/**
 * web `ArtifactCoverPicture` `variant="tile"` (slock #16751): publish-check screenshot, else
 * Markdown excerpt (`fit="frame"`), else kind glyph. [corner] sits overlaid at top-right (`6.dp`)
 * over a screenshot or placeholder, and wraps inside the words of a Markdown excerpt at the exact
 * same top-right position.
 */
@Composable
internal fun ArtifactsHomeTileCover(
    row: ArtifactsHomeRow,
    dimmed: Boolean,
    corner: (@Composable () -> Unit)? = null,
) {
    val summary = row.summary
    val excerpt = summary.excerpt
    when {
        summary.screenshot != null ->
            ArtifactsHomeTileCoverFrame(corner = corner) {
                ArtifactsHomeScreenshotImage(row, dimmed)
            }
        excerpt != null ->
            ArtifactsHomeTileCoverFrame(Alignment.TopStart) {
                ArtifactCardExcerpt(excerpt, fitFrame = true, corner = corner)
            }
        else -> ArtifactsHomeTilePlaceholder(summary.kind, corner = corner)
    }
}

/** web `ArtifactCoverPicture` `variant="thumbnail"`: 76dp 16:10 screenshot, scaled excerpt miniature, or kind glyph. */
@Composable
private fun ArtifactsHomeRowThumbnail(row: ArtifactsHomeRow, dimmed: Boolean) {
    val summary = row.summary
    val excerpt = summary.excerpt
    when {
        summary.screenshot != null ->
            ArtifactsHomeRowThumbnailFrame {
                ArtifactsHomeScreenshotImage(row, dimmed)
            }
        excerpt != null ->
            ArtifactsHomeRowThumbnailFrame(Alignment.TopStart) {
                // web `h-[123px] w-[196px] origin-top-left scale-[0.3878]` scaled into the 76x47.5dp thumbnail box.
                Box(
                    Modifier
                        .requiredSize(width = 196.dp, height = 123.dp)
                        .graphicsLayer {
                            scaleX = 0.3878f
                            scaleY = 0.3878f
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                ) {
                    ArtifactCardExcerpt(excerpt, fitFrame = true)
                }
            }
        else -> ArtifactsHomeRowThumbnailPlaceholder(summary.kind)
    }
}

@Composable
private fun ArtifactsHomeScreenshotImage(row: ArtifactsHomeRow, dimmed: Boolean) {
    // A screenshot still read, or one that can't be, is the empty box: no spinner, no error.
    val view = rememberArtifactCardScreenshot(row.summary)
    if (view is ArtifactCardScreenshotView.Shown) {
        val context = LocalPlatformContext.current
        val request =
            remember(view.cardId, view.fileId, view.bytes, context) {
                ImageRequest.Builder(context)
                    .data(view.bytes)
                    .memoryCacheKey("artifact-screenshot|${view.cardId}|${view.fileId}")
                    .build()
            }
        // `object-cover object-top`: cut from the bottom.
        Image(
            painter = rememberAsyncImagePainter(request),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            colorFilter = if (dimmed) ArtifactsHomeGrayscaleFilter else null,
        )
    }
}

/**
 * New activity on this server's Artifacts (`activity:changed`, the realtime domain's accepted
 * events) while the page shows: the store decides whether it reads again (web
 * `useArtifactActivityChanged`).
 */
@Composable
private fun ArtifactsHomeActivityEffect(storeScope: RaftShellStoreScope, serverId: String?, dispatch: (ArtifactsHomeIntent) -> Unit) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val owner = remember(bridge, storeScope) { RaftSharedStoreRegistry.get(bridge).realtimeDomainEventOwner(storeScope) }
    val latestDispatch by rememberUpdatedState(dispatch)
    DisposableEffect(owner, serverId) {
        if (serverId == null) return@DisposableEffect onDispose {}
        val sink =
            RealtimeGuardedActiveThreadSink { eventServerId, event ->
                if (event is ActivityChanged && event.serverId.value == serverId && eventServerId == serverId) {
                    latestDispatch(ArtifactsHomeIntent.ActivityChanged(serverId))
                }
            }
        owner.registerGuardedActiveThreadSink(sink)
        onDispose { owner.unregisterGuardedActiveThreadSink(sink) }
    }
}
