package build.raft.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactsHomeEvent
import build.raft.app.data.ArtifactsHomeIntent
import build.raft.app.data.ArtifactsHomePreferenceRepository
import build.raft.app.data.ArtifactsHomeRow
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
) : ViewModel() {
    val store = SharedArtifactsHomeStore(source, viewModelScope, initialSort, onSortChosen, logger)
    val state: StateFlow<ArtifactsHomeState> get() = store.state

    override fun onCleared() {
        store.clear()
        super.onCleared()
    }
}

/** Who reads and where: the current server, its name, the signed-in person. */
private data class ArtifactsHomeSession(val serverId: String?, val serverName: String?, val readerId: String?)

/**
 * Wires [ArtifactsHomeScreen]: the store (`dashboardAll` through the shell's network client), the
 * server's workspace_artifacts_v0 (live: off once resolved off is the off state, web parity), the
 * device prefs (view, sort), `activity:changed` while the page shows, and the in-app viewer: a row
 * opens the latest version (web `version: null`), and coming back from it reads the list again.
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
    val viewModel: ArtifactsHomeViewModel =
        viewModel(key = "artifacts-home-${storeScope.shellInstanceId}") {
            ArtifactsHomeViewModel(
                source = NetworkRaftArtifactsDashboardApi(networkClient).asArtifactsHomeSource(),
                initialSort = prefs.sort,
                onSortChosen = { sort -> prefsRepository.write(prefsRepository.read().copy(sort = sort)) },
                logger = { bridge.log(it) },
            )
        }
    val state by viewModel.state.collectAsState()
    val session by produceState(ArtifactsHomeSession(storeScope.initialServerId, null, null), authStore) {
        suspend fun read() {
            val read = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull() ?: return
            val next = ArtifactsHomeSession(read.currentServerId?.takeIf { it.isNotBlank() }, read.currentServerSummary()?.name, read.user.id.takeIf { it.isNotBlank() })
            if (next != value) value = next
        }
        read()
        // A server switch while the page shows (the wide rail's inline page): another server's list.
        val events = (authStore as? build.raft.app.data.RaftAuthSessionEventSource)?.authSessionEvents ?: return@produceState
        events.collect { read() }
    }
    val serverId = session.serverId
    // workspace_artifacts_v0, live (web `useServerFeatureFlag`): unknown while it loads.
    val flags = sharedStores.serverFeatureFlags
    val revision by flags.snapshotRevision.collectAsState()
    val flagEnabled = remember(serverId, revision) { serverId != null && flags.isEnabled(ServerId(serverId), WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY) }
    val flagPending = remember(serverId, revision) { serverId == null || flags.isDecisionPending(ServerId(serverId), WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY) }
    LaunchedEffect(serverId, flagEnabled) {
        viewModel.store.dispatch(ArtifactsHomeIntent.Start(serverId?.takeIf { flagEnabled }))
    }
    ArtifactsHomeActivityEffect(storeScope, serverId?.takeIf { flagEnabled && pageVisible }) { viewModel.store.dispatch(it) }
    // Back from the viewer: opening it read the current version, so what was new may be gone.
    LaunchedEffect(pageVisibleResumeTick) {
        if (pageVisibleResumeTick > 0 && viewModel.state.value.viewerOpen) viewModel.store.dispatch(ArtifactsHomeIntent.ViewerClosed)
    }
    val sortFailed = stringResource(Res.string.artifacts_dashboard_failed_title)
    val moreFailed = stringResource(Res.string.artifacts_dashboard_more_failed)
    LaunchedEffect(viewModel) {
        viewModel.store.events.collect { event ->
            when (event) {
                is ArtifactsHomeEvent.SortFailed -> bridge.errorToast(sortFailed, event.error)
                is ArtifactsHomeEvent.MoreFailed -> event.error?.let { bridge.errorToast(moreFailed, it) } ?: bridge.toast(moreFailed)
            }
        }
    }
    val openArtifactWebPage = rememberRaftWebPageOpener()
    val webBaseUrl = remember(sharedStores) { standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore) }
    val opener = rememberRaftArtifactOpener { openArtifactWebPage(artifactWebUrl(webBaseUrl, it)) }
    RaftArtifactViewerPrewarm(enabled = pageVisible && !state.rows.isNullOrEmpty())
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val wide = LocalRaftSettingsWideDetail.current || windowWidth.value >= DualPaneMinimumWidthDp
    val screenshots = remember(sharedStores, serverId) { serverId?.let { sharedStores.artifactScreenshotStore.reader(it) } }
    // A flag resolved off (or a 403) is the off state; nothing is read.
    val shown = if (!flagPending && !flagEnabled) state.copy(status = ArtifactsHomeStatus.Off) else state
    CompositionLocalProvider(LocalArtifactScreenshotReader provides screenshots) {
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
                viewModel.store.dispatch(ArtifactsHomeIntent.ViewerOpened)
                opener.open(target, RaftArtifactViewerHeader.of(row.summary, target), "artifacts_home")
            },
            onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
            cover = { row -> ArtifactsHomeTileCover(row) },
        )
    }
}

/** web `TileCover`: the publish check's screenshot, else a Markdown version's excerpt, else the kind's glyph. */
@Composable
private fun ArtifactsHomeTileCover(row: ArtifactsHomeRow) {
    val summary = row.summary
    val excerpt = summary.excerpt
    when {
        summary.screenshot != null ->
            ArtifactsHomeTileCoverFrame {
                // A screenshot still read, or one that can't be, is the empty box: no spinner, no error.
                val view = rememberArtifactCardScreenshot(summary)
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
                    )
                }
            }
        excerpt != null -> ArtifactsHomeTileCoverFrame(Alignment.TopCenter) { ArtifactCardExcerpt(excerpt) }
        else -> ArtifactsHomeTilePlaceholder(summary.kind)
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
