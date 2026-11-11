package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.rememberTextMeasurer
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RaftComputersRackUsageSession
import build.raft.app.data.raftComputersRackUsageKeys
import build.raft.designsystem.components.RaftMeter
import build.raft.designsystem.components.RaftMeterSize
import build.raft.designsystem.components.RaftProgressBar
import build.raft.designsystem.components.RaftProgressBarSize
import build.raft.designsystem.components.RaftProgressBarVariant
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftStatusSize
import build.raft.designsystem.components.RaftStatusVariant
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import build.raft.designsystem.components.raftGrayscale
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.tokens.RaftUiPalette
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.base.Utils
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Page
import build.raft.app.compat.pager.Pager
import build.raft.app.data.COMPUTER_ACTION_DELETE
import build.raft.app.data.ProcessRaftDatetimeFoundationOwner
import build.raft.app.data.COMPUTER_ACTION_DESCRIPTION
import build.raft.app.data.COMPUTER_ACTION_RENAME
import build.raft.app.data.COMPUTER_ACTION_OWNER
import build.raft.app.model.MemberProfile
import build.raft.app.data.SettingsComputerOwnerRequest
import build.raft.designsystem.components.LocalRaftToastHostState
import build.raft.designsystem.components.RaftToastIntent
import build.raft.app.data.COMPUTER_ACTION_SCAN_WORKSPACES
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.ServerRuntimeCatalog
import build.raft.app.base.BridgeModule
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerControlState
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.SettingsComputerDescriptionUpdateRequest
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerRenameRequest
import build.raft.app.data.SettingsComputerWorkspaceSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SettingsComputerUpgradeFailureHintSnapshot
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsEffect
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.isRefreshing
import build.raft.app.components.RaftStorePullToRefreshBox
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.MemberId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import androidx.compose.foundation.layout.heightIn
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowValue
import build.raft.designsystem.components.RaftListRowMeta
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.app.data.ComputersRackView
import build.raft.app.data.ComputersRackSortKey
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftRelativeTimeUnit
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.key
import build.raft.designsystem.components.raftMaskFadeX
import build.raft.app.data.ComputersRackGroupBy
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.designsystem.components.RaftEmptyState
import androidx.compose.ui.platform.testTag
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.launch
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.RaftHoverTier

const val SETTINGS_COMPUTERS_PAGE = "compose_settings_computers"

/**
 * Native Settings > Computers (replaces the embedded web Computers surface). Page data carries
 * shell identity only; with [SETTINGS_COMPUTER_ID] (and the target server in
 * [RAFT_SHELL_SERVER_ID]) it is the detail page of that one machine. Content comes from the shared
 * settings store, i.e. `GET servers/:serverId/machines`, `GET agents` and, on demand,
 * `GET servers/:serverId/machines/:id/workspaces`.
 */
@Page(SETTINGS_COMPUTERS_PAGE, supportInLocal = true)
internal class RaftSettingsComputersPage : AuthenticatedProductPager(AuthenticatedProductRoute.SettingsComputers) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { RaftSettingsComputersRoute(raftPageVisibleResumeTick, raftPageVisible) }
    }
}

@Composable
internal fun RaftSettingsComputersRoute(pageVisibleResumeTick: Int = 0, pageVisible: Boolean = true) {
    val pager = LocalActivity.current.getPager() as Pager
    val target =
        remember(pager.pageData.params.toString()) {
            pager.pageData.params.optString(SETTINGS_COMPUTER_ID).trim().takeIf { it.isNotBlank() }?.let { machineId ->
                RaftComputerPageTarget(
                    machineId = machineId,
                    serverId = pager.pageData.params.optString(RAFT_SHELL_SERVER_ID).trim().takeIf { it.isNotBlank() },
                )
            }
        }
    if (target == null) {
        RaftSettingsComputersScreen(pager, pageVisibleResumeTick, pageVisible)
    } else {
        RaftSettingsComputerDetailScreen(pager, target, pageVisible)
    }
}

/**
 * The pending "open Add Computer" request against the page's load: null while it is still loading
 * (wait), true once its snapshot is in (open), false when the load ended without one (drop).
 */
internal fun raftComputersOpenAddOnLoad(state: ScreenLoadState<*>): Boolean? =
    when (state) {
        ScreenLoadState.Loading -> null
        is ScreenLoadState.Content -> true
        else -> false
    }

internal data class RaftComputerPageTarget(val machineId: String, val serverId: String?)

/** The usage sheet open on the Computers page: whose usage, the runtime's name and its reported version. */
private data class RaftComputersOpenUsage(val key: RuntimeAccountUsageKey, val label: String, val runtimeVersion: String?)

private data class RaftComputersFirstFrame(val computersStore: SharedComputersStore, val serverId: String?)

@Composable
private fun rememberRaftComputersFirstFrame(bridge: BridgeModule, storeScope: RaftShellStoreScope): RaftComputersFirstFrame {
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    return remember(sharedStores, storeScope) {
        RaftComputersFirstFrame(
            computersStore = sharedStores.computersStore(storeScope),
            serverId = sharedStores.currentSelectedServerIdOrNull(storeScope),
        )
    }
}

/** The page's own load state, or — while that is still Loading — the cached snapshot. */
internal fun <T> raftComputersFirstFrameState(state: ScreenLoadState<T>, cached: T?): ScreenLoadState<T> =
    if (state == ScreenLoadState.Loading && cached != null) ScreenLoadState.Content(cached) else state

private sealed interface RaftComputersSessionRead {
    data object Pending : RaftComputersSessionRead

    data class Read(val currentServerId: String?) : RaftComputersSessionRead
}

/**
 * The shell's current server, read reactively: re-read on every auth-session event (server switch,
 * sign-out) and emitted only when it actually changes.
 */
@Composable
private fun rememberRaftComputersSession(
    authStore: RaftAuthSessionStore,
    firstFrameServerId: String? = null,
): State<RaftComputersSessionRead> =
    produceState<RaftComputersSessionRead>(
        // First frame: the shell's selected server (synchronous), so a cached page paints at once;
        // the session read below confirms or corrects it.
        firstFrameServerId?.let { RaftComputersSessionRead.Read(it) } ?: RaftComputersSessionRead.Pending,
        authStore,
    ) {
        suspend fun read() =
            RaftComputersSessionRead.Read(
                runCatchingPreservingCancellation { authStore.readSession()?.currentServerId }.getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            )
        value = read()
        val events = (authStore as? RaftAuthSessionEventSource)?.authSessionEvents ?: return@produceState
        events.collect {
            val next = read()
            if (next != value) value = next
        }
    }

/**
 * Routes Machines-scope realtime events of [serverId] into the page's settings store while the page
 * is visible: status dots, renames, deletes and upgrade flags follow the server without a manual
 * refresh. The store coalesces bursts into one in-flight reload.
 */
@Composable
private fun RaftComputersRealtimeEffect(
    pager: Pager,
    storeScope: RaftShellStoreScope,
    serverId: String?,
    visible: Boolean,
    dispatch: (SharedSettingsIntent) -> Unit,
) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val owner = remember(bridge, storeScope) { RaftSharedStoreRegistry.get(bridge).realtimeDomainEventOwner(storeScope) }
    val latestDispatch by rememberUpdatedState(dispatch)
    val subscribedServerId = serverId?.takeIf { visible }
    DisposableEffect(owner, subscribedServerId) {
        if (subscribedServerId == null) return@DisposableEffect onDispose {}
        val sink =
            RealtimeGuardedActiveThreadSink { eventServerId, event ->
                if (eventServerId == subscribedServerId && raftComputersRealtimeEventApplies(event, subscribedServerId)) {
                    latestDispatch(SharedSettingsIntent.ApplyRealtimeEvent(subscribedServerId, event))
                }
            }
        owner.registerGuardedActiveThreadSink(sink)
        onDispose { owner.unregisterGuardedActiveThreadSink(sink) }
    }
}

// region List

@Composable
private fun RaftSettingsComputersScreen(pager: Pager, pageVisibleResumeTick: Int, pageVisible: Boolean) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-computers") {
            SharedSettingsViewModel(environment.dataSource) { bridge.log("settings_computers $it") }
        }
    val settingsState by settingsViewModel.state.collectAsState()
    val navigationScope = rememberCoroutineScope()
    val firstFrame = rememberRaftComputersFirstFrame(bridge, environment.storeScope)
    // Data-layer plan step 5: until the page's own load lands, the first frames read the shell's
    // machine cache (memory, seeded from disk on a cold start) instead of a spinner.
    val cachedComputers = remember(firstFrame) { firstFrame.computersStore.currentSettingsComputers(firstFrame.serverId) }
    LaunchedEffect(Unit) {
        bridge.log("settings_computers_open target=list")
        settingsViewModel.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
    }
    // The detail page runs its own store; coming back from it (delete, rename) reloads the list.
    LaunchedEffect(pageVisibleResumeTick) {
        if (pageVisibleResumeTick > 0) {
            bridge.log("settings_computers_resume_refresh tick=$pageVisibleResumeTick")
            settingsViewModel.dispatch(SharedSettingsIntent.RefreshComputers)
        }
    }
    val session by rememberRaftComputersSession(environment.authStore, firstFrame.serverId)
    val currentServerId = (session as? RaftComputersSessionRead.Read)?.currentServerId
    // A card's ⋯ → Create agent: the canonical create form, as the signed-in owner of this page's server.
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val routeOwner by
        remember(sharedStores, environment.storeScope) {
            sharedStores.authenticatedRouteOwnerFlow(environment.storeScope)
        }.collectAsState(sharedStores.currentAuthenticatedRouteOwner(environment.storeScope))
    val createAgentOwner = routeOwner?.takeIf { currentServerId != null && it.serverId == currentServerId }
    var createAgentMachineId by remember { mutableStateOf<String?>(null) }
    var createAgentServerPlan by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(createAgentOwner) {
        if (createAgentOwner == null) createAgentMachineId = null
        createAgentServerPlan =
            createAgentOwner?.let { owner ->
                runCatchingPreservingCancellation {
                    environment.authStore.readSession()?.servers?.firstOrNull { it.id == owner.serverId }?.toServerSummary()?.plan
                }.getOrNull()
            }
    }
    RaftComputersRealtimeEffect(pager, environment.storeScope, currentServerId, pageVisible, settingsViewModel::dispatch)
    // Machine pushes stop in the background and while the socket is down: catch up on return.
    RaftLiveCatchUpEffect(serverId = currentServerId, visible = pageVisible) {
        settingsViewModel.dispatch(SharedSettingsIntent.CatchUpComputers)
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    LaunchedEffect(settingsState.effect) {
        // A finished Add Computer dialog lands on the new machine, as the web dialog does.
        val effect = settingsState.effect as? SharedSettingsEffect.OpenComputer ?: return@LaunchedEffect
        val serverId = environment.authStore.readSession()?.currentServerId
        settingsComputerRouteIdentity(serverId, effect.machineId)?.let { pager.openSettingsComputerPage(environment.storeScope, it) }
    }
    // Create Agent's "Add Computer" lands here with [SETTINGS_COMPUTERS_OPEN_ADD]: open the dialog
    // once, on the page's own loaded snapshot (it carries the add permission); a failed load just
    // shows the page.
    var openAddComputerPending by remember { mutableStateOf(pager.pageData.params.optBoolean(SETTINGS_COMPUTERS_OPEN_ADD)) }
    LaunchedEffect(openAddComputerPending, settingsState.computers) {
        if (!openAddComputerPending) return@LaunchedEffect
        val open = raftComputersOpenAddOnLoad(settingsState.computers) ?: return@LaunchedEffect
        openAddComputerPending = false
        if (open) settingsViewModel.dispatch(SharedSettingsIntent.ShowAddComputerDialog)
    }
    val computersLoadState =
        raftComputersFirstFrameState(settingsState.computers, cachedComputers?.takeIf { firstFrame.serverId == currentServerId })
    val model = raftComputersListModel(computersLoadState)
    val refreshing = settingsState.isRefreshing(SharedSettingsDestination.Computers)
    val refreshErrorCode =
        raftComputersRefreshErrorCode(
            hasContent = computersLoadState is ScreenLoadState.Content,
            routeErrorCode = settingsState.routeErrorCode,
            refreshing = refreshing,
        )
    // Runtime usage on the cards (Tenny's request rules, [RaftComputersRackUsageSession]): read when
    // the page opens and on pull-to-refresh only, through the usage sheet's store (60 s cache, at
    // most 4 reads in flight), for online computers the viewer may see the usage of. The rows are
    // the store's (permission-gated), never the first frame's cache.
    val usageAccess = rememberShellRuntimeAccountUsageAccess()
    val usageSession = remember(usageAccess.store) { RaftComputersRackUsageSession(usageAccess.store) }
    val usageScope = rememberCoroutineScope()
    var usageReads by remember { mutableStateOf<Map<RuntimeAccountUsageKey, RuntimeAccountUsageLoad>>(emptyMap()) }
    val usageServerId = usageAccess.serverId?.takeIf { it == currentServerId }
    val usageKeys =
        raftComputersRackUsageKeys(
            owner = usageAccess.owner,
            serverId = usageServerId,
            computers = (settingsState.computers as? ScreenLoadState.Content)?.data?.computers.orEmpty(),
        )
    val onUsageResult: (RuntimeAccountUsageKey, RuntimeAccountUsageLoad) -> Unit = { key, load ->
        if (load == RuntimeAccountUsageLoad.Superseded) {
            usageAccess.onIdentityChanged()
        } else {
            usageReads = usageReads + (key to load)
        }
    }
    LaunchedEffect(usageKeys) {
        // Outside this effect's scope: a list update must not cancel a read already counted as made.
        if (usageKeys.isNotEmpty()) usageScope.launch { usageSession.open(usageKeys, onUsageResult) }
    }
    val usageLoads =
        remember(usageReads, usageAccess.owner, usageServerId) {
            usageReads.filterKeys { it.owner == usageAccess.owner && it.serverId == usageServerId }
                .entries
                .groupBy({ it.key.machineId }, { it.key.provider to it.value })
                .mapValues { (_, loads) -> loads.toMap() }
        }
    var openUsage by remember { mutableStateOf<RaftComputersOpenUsage?>(null) }
    var deleteComputerId by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        settingsViewModel.dispatch(SharedSettingsIntent.RefreshComputers)
        if (usageKeys.isNotEmpty()) usageScope.launch { usageSession.pullToRefresh(usageKeys, onUsageResult) }
    }
    // The agents on each card: the members store's roster (as the Members page groups it), no new API.
    val membersServerId = currentServerId?.let(::ServerId)
    val membersEntry by
        remember(environment.serverMembersStore, membersServerId) {
            membersServerId?.let { environment.serverMembersStore.serverMembersEntryFlow(it) } ?: kotlinx.coroutines.flow.flowOf(null)
        }.collectAsState(membersServerId?.let { environment.serverMembersStore.currentServerMembersEntry(it) })
    val agentsByMachine = remember(membersEntry?.members) { raftComputerAgentsByMachine(membersEntry?.members.orEmpty()) }
    val agentStatusResolver =
        remember(environment.serverMembersStore, currentServerId) {
            raftScopedAgentStatusResolver(environment.serverMembersStore, currentServerId)
        }
    // The connected computer's runtime chips, and the cards' runtime lines, name the current
    // server's runtimes.
    val runtimeCatalog =
        rememberServerRuntimeCatalog(
            remember(bridge, environment.storeScope) {
                RaftSharedStoreRegistry.get(bridge).serverRuntimeCatalogRepository(environment.storeScope)
            },
            currentServerId,
        )
    // The toolbar (search, Status, Runtime, Group, Sort) and Cards / List: one store per shell
    // window for the app session; Group, Sort and View are kept on this device. Another server
    // starts with no search or filters: shown at once, committed to the store just after.
    val toolbarStore = remember(sharedStores, environment.storeScope) { sharedStores.computersRackToolbarStore(environment.storeScope) }
    val storedToolbar by toolbarStore.state.collectAsState()
    val toolbar = storedToolbar.onServer(currentServerId)
    // A computer attention entry (a Home machine notice's View, web `/computers?filter=attention`):
    // the list starts at Status: Needs attention, once, on the page's server.
    var attentionPending by remember { mutableStateOf(pager.pageData.params.optBoolean(SETTINGS_COMPUTERS_FILTER_ATTENTION)) }
    LaunchedEffect(toolbarStore, currentServerId) {
        toolbarStore.onServer(currentServerId)
        if (attentionPending && currentServerId != null) {
            attentionPending = false
            bridge.log("settings_computers_open_attention")
            toolbarStore.dispatch(RaftComputersRackToolbarIntent.SetStatus(RaftComputersRackStatusFilter.Attention))
        }
    }
    val rackView = toolbar.view
    val chooseView: (ComputersRackView) -> Unit = { toolbarStore.dispatch(RaftComputersRackToolbarIntent.SetView(it)) }
    // Wide (the shell's content area / the wide Settings pane): the web's desktop head, the counts
    // beside the title and Add Computer beside the toggle; the phone keeps them on the first row.
    val wideHead = LocalRaftSettingsWideDetail.current
    val computersTitle = stringResource(Res.string.settings_computers_title)
    SettingsDetailShell(
        title = computersTitle,
        subtitle = "",
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
        titleContent =
            if (wideHead && model is RaftComputersListModel.Content) {
                {
                    RaftComputersWideTitle(computersTitle, raftComputersCountsLine(raftComputersCounts(model.rows, agentsByMachine)))
                }
            } else {
                null
            },
        // Web: the toggle sits in the page header while there are computers to lay out.
        trailingContent =
            if (model is RaftComputersListModel.Content) {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftComputersRackViewToggle(rackView, chooseView)
                        if (wideHead && model.canAddComputer) {
                            RaftComputersAddButton { settingsViewModel.dispatch(SharedSettingsIntent.ShowAddComputerDialog) }
                        }
                    }
                }
            } else {
                null
            },
        // PROTOTYPE (native nav bar): the List / Cards toggle as a bar button with a checked menu.
        nativeActions =
            if (model is RaftComputersListModel.Content) {
                val choose = chooseView
                listOf(
                    RaftNativeNavBarAction(
                        id = "computers-rack-view",
                        label = stringResource(Res.string.settings_computers_rack_view_label),
                        systemSymbol = if (rackView == ComputersRackView.List) "list.bullet" else "square.grid.2x2",
                        onClick = {},
                        icon = if (rackView == ComputersRackView.List) RaftLucideIconName.LayoutList else RaftLucideIconName.LayoutGrid,
                        children =
                            listOf(
                                RaftNativeNavBarAction(
                                    "computers-rack-view-cards",
                                    stringResource(Res.string.settings_computers_rack_view_cards),
                                    "square.grid.2x2",
                                    { choose(ComputersRackView.Cards) },
                                    checked = rackView == ComputersRackView.Cards,
                                    icon = RaftLucideIconName.LayoutGrid,
                                ),
                                RaftNativeNavBarAction(
                                    "computers-rack-view-list",
                                    stringResource(Res.string.settings_computers_rack_view_list),
                                    "list.bullet",
                                    { choose(ComputersRackView.List) },
                                    checked = rackView == ComputersRackView.List,
                                    icon = RaftLucideIconName.LayoutList,
                                ),
                            ),
                    )
                )
            } else {
                emptyList()
            },
    ) {
        // Mobile refreshes by pull only (no header refresh button): same store refresh as Retry.
        RaftStorePullToRefreshBox(refreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        // A usage line's card on hover (web rack `UsageCardTrigger` → RuntimeUsageHoverCard).
        val usageCard: RaftRackUsageCard? =
            usageAccess.owner?.let { owner ->
                usageServerId?.let { serverId ->
                    RaftRackUsageCard { machineId: String, column: RaftRackUsageColumn, label: String, trigger: @Composable () -> Unit ->
                        val key = remember(owner, serverId, machineId, column.provider) {
                            RuntimeAccountUsageKey(owner, serverId, machineId, column.provider)
                        }
                        val runtimeVersion =
                            (settingsState.computers as? ScreenLoadState.Content)?.data?.computers
                                ?.firstOrNull { it.id == machineId }
                                ?.runtimeVersions
                                ?.get(column.runtimeId)
                        RaftRackUsagePreviewCard(label, key, usageAccess, runtimeVersion, trigger)
                    }
                }
            }
        RaftComputersTheme {
          CompositionLocalProvider(LocalRaftRackUsageCard provides usageCard) {
            RaftSettingsComputersContent(
                model = model,
                refreshErrorCode = refreshErrorCode,
                bottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg,
                onRetry = ::refresh,
                onAddComputer = { settingsViewModel.dispatch(SharedSettingsIntent.ShowAddComputerDialog) },
                onOpenComputer = { machineId ->
                    navigationScope.launch {
                        val identity = settingsComputerRouteIdentity(currentServerId, machineId)
                        if (identity == null) {
                            bridge.log("settings_computers_open_detail_skip reason=no_server")
                        } else {
                            pager.openSettingsComputerPage(environment.storeScope, identity)
                        }
                    }
                },
                rackView = rackView,
                summaryInHeader = wideHead,
                computerControl = settingsState.computerControl,
                onUpgradeComputer = { machineId, version -> settingsViewModel.dispatch(SharedSettingsIntent.UpgradeComputer(machineId, version)) },
                onCopyCommand = { command -> settingsViewModel.dispatch(SharedSettingsIntent.CopyComputerCommand(command)) },
                onCreateAgent = if (createAgentOwner != null) { machineId -> createAgentMachineId = machineId } else null,
                onRestartComputer = { machineId -> settingsViewModel.dispatch(SharedSettingsIntent.RestartComputer(machineId)) },
                onDeleteComputer = { machineId -> deleteComputerId = machineId },
                onOpenInstallGuide = { settingsViewModel.dispatch(SharedSettingsIntent.OpenComputerInstallGuide) },
                actionError =
                    settingsState.actionState?.takeIf {
                        it.action == COMPUTER_ACTION_DELETE && !it.inProgress && it.hasComputerActionError()
                    },
                toolbar = toolbar,
                onToolbarIntent = toolbarStore::dispatch,
                serverId = currentServerId,
                agentsByMachine = agentsByMachine,
                agentStatusResolver = agentStatusResolver,
                usageLoads = usageLoads,
                runtimeCatalog = runtimeCatalog,
                onOpenUsage = { machineId, column, label ->
                    val owner = usageAccess.owner
                    if (owner != null && usageServerId != null) {
                        // Web rack: the sheet names `machine.runtimeVersions[cell.runtimeId]`.
                        val runtimeVersion =
                            (settingsState.computers as? ScreenLoadState.Content)?.data?.computers
                                ?.firstOrNull { it.id == machineId }
                                ?.runtimeVersions
                                ?.get(column.runtimeId)
                        openUsage =
                            RaftComputersOpenUsage(RuntimeAccountUsageKey(owner, usageServerId, machineId, column.provider), label, runtimeVersion)
                    }
                },
                onOpenAgent = { agent ->
                    val pageData =
                        RaftMembersPayload(instanceId = "settings-computers-agent").toMemberDetailPageData(
                            MemberDetailIdentity(MemberId(agent.id), MemberKind.Agent, currentServerId?.let(::ServerId))
                        )
                    if (pageData == null) {
                        bridge.log("settings_computers_open_agent_skip reason=invalid_agent")
                    } else {
                        pager.openRaftPage(
                            pageName = MEMBER_DETAIL_PAGE,
                            pageData = pageData,
                            storeScopeOverride = environment.storeScope,
                            onMissingScope = { bridge.log("settings_computers_open_agent_skip reason=missing_shell_scope") },
                        )
                    }
                },
            )
          }
        }
        }
    }
    openUsage?.let { (key, label, runtimeVersion) ->
        RaftComputersTheme {
            ComputerRuntimeUsageSheetHost(
                runtimeLabel = label,
                key = key,
                access = usageAccess,
                runtimeVersion = runtimeVersion,
                onDismiss = {
                    openUsage = null
                    // The sheet may have refreshed it: show the store's value (no request).
                    usageScope.launch { usageSession.cached(key)?.let { load -> usageReads = usageReads + (key to load) } }
                },
            )
        }
    }
    settingsState.addComputerDialog?.let { dialog ->
        AddComputerDialog(state = dialog, onIntent = settingsViewModel::dispatch, runtimeCatalog = runtimeCatalog)
    }
    // A card's ⋯ → Delete computer (web RackRowMenu → DeleteComputerDialog): the Computer page's
    // confirm; the agents on it are the ones the cards show.
    deleteComputerId?.let { machineId ->
        val row = (model as? RaftComputersListModel.Content)?.rows?.firstOrNull { it.id == machineId }
        if (row == null) {
            deleteComputerId = null
        } else {
            RaftComputersTheme {
                RaftComputerDeleteDialog(
                    name = row.name,
                    agentCount = agentsByMachine[machineId]?.size ?: 0,
                    onConfirm = {
                        deleteComputerId = null
                        bridge.log("settings_computers_card_delete")
                        settingsViewModel.dispatch(SharedSettingsIntent.DeleteComputer(machineId))
                    },
                    onDismiss = { deleteComputerId = null },
                )
            }
        }
    }
    val createAgentFor = createAgentMachineId
    if (createAgentOwner != null && createAgentFor != null) {
        RaftComputerCreateAgentDialog(
            owner = createAgentOwner,
            machineId = createAgentFor,
            authority = remember(sharedStores, environment.storeScope) { sharedStores.authenticatedRouteAuthorityGateway(environment.storeScope) },
            computersStore = remember(sharedStores, environment.storeScope) { sharedStores.computersStore(environment.storeScope) },
            formStore = remember(sharedStores, environment.storeScope) { sharedStores.createAgentFormStore(environment.storeScope) },
            membersStore = environment.serverMembersStore,
            onDismiss = { createAgentMachineId = null },
            onCreated = { agentId ->
                bridge.log("settings_computers_create_agent_done agentId=$agentId")
                createAgentMachineId = null
                // The new agent joins its computer's card: the roster and the machine rows.
                usageScope.launch { environment.serverMembersStore.refreshAgents(ServerId(createAgentOwner.serverId), "computer_card_create_agent") }
                settingsViewModel.dispatch(SharedSettingsIntent.RefreshComputers)
            },
            onAddComputer = {
                createAgentMachineId = null
                settingsViewModel.dispatch(SharedSettingsIntent.ShowAddComputerDialog)
            },
            serverPlan = createAgentServerPlan,
            onUpgrade = { pager.openRaftUpgradeDialog(RaftUpgradeGate.SeatLimitAgentCreate, environment.storeScope) },
        )
    }
}

@Composable
internal fun RaftSettingsComputersContent(
    model: RaftComputersListModel,
    refreshErrorCode: String? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onRetry: () -> Unit,
    onAddComputer: () -> Unit,
    onOpenComputer: (String) -> Unit,
    /** The members store's agents per computer ([raftComputerAgentsByMachine]); empty while it loads. */
    agentsByMachine: Map<String, List<RaftComputerCardAgent>> = emptyMap(),
    /** Live agent activity for the avatars' lights (the members store); empty in fixtures. */
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    onOpenAgent: (RaftComputerCardAgent) -> Unit = {},
    /** The runtime usage reads per computer and provider ([RaftComputersRackUsageSession]); empty until read. */
    usageLoads: Map<String, Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>> = emptyMap(),
    /** The server's runtimes: their order and names on the cards' runtime lines. */
    runtimeCatalog: ServerRuntimeCatalog = BundledRuntimeCatalog,
    /** A usage line was tapped: open that provider's usage sheet ([runtimeLabel] names it). */
    onOpenUsage: (machineId: String, column: RaftRackUsageColumn, runtimeLabel: String) -> Unit = { _, _, _ -> },
    /** Cards (each with its next step) or List rows; the header's toggle picks it. */
    rackView: ComputersRackView = ComputersRackView.Default,
    /** The counts and Add Computer are in the page head (wide): no first row for them. */
    summaryInHeader: Boolean = false,
    /** Restart / upgrade progress (the settings store's `computerControl`), for the cards' next step. */
    computerControl: SettingsComputerControlState = SettingsComputerControlState(),
    onUpgradeComputer: (machineId: String, version: String) -> Unit = { _, _ -> },
    onCopyCommand: (String) -> Unit = {},
    /** A card's ⋯ → Create agent with that computer preselected; null (no signed-in owner) hides it. */
    onCreateAgent: ((machineId: String) -> Unit)? = null,
    /** A card's ⋯ → Restart. */
    onRestartComputer: (machineId: String) -> Unit = {},
    /** A card's ⋯ → Delete computer: the page opens its confirm. */
    onDeleteComputer: (machineId: String) -> Unit = {},
    /** The install guide link under a card's install command. */
    onOpenInstallGuide: () -> Unit = {},
    /** A card's Delete that failed (the settings store's action state), shown over the list with its trace ID. */
    actionError: SharedSettingsActionState? = null,
    /** The toolbar's search, filters, Group and Sort ([RaftComputersRackToolbarStore]); the list follows them. */
    toolbar: RaftComputersRackToolbarState = RaftComputersRackToolbarState(),
    onToolbarIntent: (RaftComputersRackToolbarIntent) -> Unit = {},
    /** The page's server: the folds hold on it only. */
    serverId: String? = null,
    /** The loading skeleton pulses; screenshots draw its resting frame. */
    skeletonAnimated: Boolean = true,
) {
    // The upgrade's in-flight window (web `computerUpgradeInFlight`) reads the clock.
    val nowMillis = currentRaftTimeContextProvider?.current?.nowEpochMillis ?: defaultNowMillis()
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        when (model) {
            RaftComputersListModel.Loading -> RaftComputersRackLoading(toolbar, onToolbarIntent, skeletonAnimated)
            is RaftComputersListModel.Failed ->
                if (model.failure.noAccess) {
                    RaftComputersStateBlock(
                        title = stringResource(Res.string.settings_computers_no_access),
                        detail = stringResource(Res.string.settings_computers_no_access_detail),
                        iconName = RaftLucideIconName.Lock,
                    )
                } else {
                    RaftComputersStateBlock(
                        title = stringResource(Res.string.settings_computers_error_load),
                        detail = raftComputerFailureMessage(model.failure),
                        iconName = RaftLucideIconName.TriangleAlert,
                        actionLabel = stringResource(Res.string.common_retry).takeIf { model.failure.retryable },
                        onAction = onRetry,
                    )
                }
            is RaftComputersListModel.Empty ->
                RaftComputersStateBlock(
                    title = stringResource(Res.string.settings_computers_empty),
                    detail = stringResource(Res.string.settings_computers_empty_detail),
                    iconName = RaftLucideIconName.Monitor,
                    actionLabel = stringResource(Res.string.settings_computers_add_computer).takeIf { model.canAddComputer },
                    actionIcon = RaftLucideIconName.Plus,
                    onAction = onAddComputer,
                )
            is RaftComputersListModel.Content ->
                RaftComputersRackBody(
                    model = model,
                    refreshErrorCode = refreshErrorCode,
                    bottomContentPadding = bottomContentPadding,
                    onAddComputer = onAddComputer,
                    onOpenComputer = onOpenComputer,
                    agentsByMachine = agentsByMachine,
                    agentStatusResolver = agentStatusResolver,
                    onOpenAgent = onOpenAgent,
                    usageLoads = usageLoads,
                    runtimeCatalog = runtimeCatalog,
                    onOpenUsage = onOpenUsage,
                    rackView = rackView,
                    summaryInHeader = summaryInHeader,
                    computerControl = computerControl,
                    onUpgradeComputer = onUpgradeComputer,
                    onCopyCommand = onCopyCommand,
                    onCreateAgent = onCreateAgent,
                    onRestartComputer = onRestartComputer,
                    onDeleteComputer = onDeleteComputer,
                    onOpenInstallGuide = onOpenInstallGuide,
                    actionError = actionError,
                    toolbar = toolbar,
                    onToolbarIntent = onToolbarIntent,
                    serverId = serverId,
                    nowMillis = nowMillis,
                )
        }
    }
}

/**
 * The loaded page (web `MobileComputersPanel` with the rack): the counts and Add Computer, the
 * toolbar ([RaftComputersRackToolbar]), then the computers as the toolbar has them
 * ([raftComputersRackLayout]): filtered, sorted (Name A to Z by default), the online ones in
 * their groups under fold titles when grouped, the offline ones in one folded section at the end
 * (open when nothing else is online), or the no-match state with Clear all. Cards or List rows,
 * as the head's toggle picks.
 *
 * Folds are this page's own (web `useState`): a group's fold holds on this server only; the
 * Offline section's holds on this server while its default (open when nothing is online) stays.
 */
@Composable
private fun RaftComputersRackBody(
    model: RaftComputersListModel.Content,
    refreshErrorCode: String?,
    bottomContentPadding: Dp,
    onAddComputer: () -> Unit,
    onOpenComputer: (String) -> Unit,
    agentsByMachine: Map<String, List<RaftComputerCardAgent>>,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpenAgent: (RaftComputerCardAgent) -> Unit,
    usageLoads: Map<String, Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>>,
    runtimeCatalog: ServerRuntimeCatalog,
    onOpenUsage: (machineId: String, column: RaftRackUsageColumn, runtimeLabel: String) -> Unit,
    rackView: ComputersRackView,
    summaryInHeader: Boolean,
    computerControl: SettingsComputerControlState,
    onUpgradeComputer: (machineId: String, version: String) -> Unit,
    onCopyCommand: (String) -> Unit,
    onCreateAgent: ((machineId: String) -> Unit)?,
    onRestartComputer: (machineId: String) -> Unit,
    onDeleteComputer: (machineId: String) -> Unit,
    onOpenInstallGuide: () -> Unit,
    actionError: SharedSettingsActionState?,
    toolbar: RaftComputersRackToolbarState,
    onToolbarIntent: (RaftComputersRackToolbarIntent) -> Unit,
    serverId: String?,
    nowMillis: Long,
) {
    val catalogOrder = remember(runtimeCatalog) { runtimeCatalog.runtimes.map { it.id } }
    val fleetColumns = remember(model.computers, catalogOrder) { raftRackFleetRuntimeColumns(model.computers, catalogOrder) }
    val toolbarModel = remember(toolbar, fleetColumns) { raftComputersRackToolbarModel(toolbar, fleetColumns) }
    val groupLabels =
        RaftRackGroupLabels(
            personal = stringResource(Res.string.settings_computers_rack_group_personal),
            other = stringResource(Res.string.settings_computers_rack_group_other),
        )
    val layout =
        remember(model.computers, toolbarModel, toolbar.groupBy, usageLoads, groupLabels) {
            raftComputersRackLayout(model.computers, toolbarModel, toolbar.groupBy, usageLoads, groupLabels)
        }
    val rowsById = remember(model.rows) { model.rows.associateBy { it.id } }
    val computersById = remember(model.computers) { model.computers.associateBy { it.id } }
    // Web ComputersRackList: `offlineChoice` per server and default, `folded` per server.
    var offlineChoice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val offlineScope = "${serverId.orEmpty()}\u0000${if (layout.offlineOpenByDefault) "open" else "folded"}"
    val offlineOpen = offlineChoice?.takeIf { it.first == offlineScope }?.second ?: layout.offlineOpenByDefault
    var folded by remember { mutableStateOf<Pair<String?, Set<String>>>(serverId to emptySet()) }
    val collapsedGroups = if (folded.first == serverId) folded.second else emptySet()
    val grouped = toolbar.groupBy != ComputersRackGroupBy.None
    val offlineTitle = stringResource(Res.string.settings_computers_rack_filter_status_offline)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardColumns = raftComputerCardColumns(maxWidth)
        // Web ComputersRack desktop: `p-5` around the grid; the phone's single column keeps 16dp.
        val cardEdge = if (cardColumns > 1) RaftComputerCardGridEdge else 16.dp
        val list = rackView == ComputersRackView.List
        // The phone list's runtime cells and their header: one width for every row (web `phoneRuntimeGrid`).
        val listCellWidth = raftRackPhoneUsageCellWidth(maxWidth - RaftComputerBlockEdge * 2, fleetColumns.usage.size, fleetColumns.other.isNotEmpty())
        Column(Modifier.fillMaxSize()) {
            if (!summaryInHeader) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftText(
                        raftComputersCountsLine(raftComputersCounts(model.rows, agentsByMachine)),
                        modifier = Modifier.weight(1f),
                        style = RaftTheme.typography.monoMeta,
                        color = RaftTheme.colors.foregroundHint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.canAddComputer) {
                        RaftButton(
                            text = stringResource(Res.string.settings_computers_add_computer),
                            onClick = onAddComputer,
                            variant = RaftButtonVariant.Primary,
                            leadingIcon = { RaftComputersIcon(RaftLucideIconName.Plus) },
                        )
                    }
                }
            }
            RaftComputersRackToolbar(toolbar, toolbarModel, onToolbarIntent)
            // Web ComputersRack cards: one column on a phone, a grid of cards at least 250dp wide on
            // a wide window (`repeat(auto-fill,minmax(250px,1fr))`, gap 12).
            fun actionOf(row: RaftComputerRowModel): RaftComputerRackAction? {
                val computer = computersById[row.id] ?: return null
                val upgrade = raftComputerUpgradeModel(computer, computerControl, nowMillis)
                return raftComputerRackAction(computer = computer, upgrade = upgrade, restart = computerControl.restart, commandContext = model.commandContext)
            }

            fun installOf(row: RaftComputerRowModel): RaftComputerRackInstall? =
                computersById[row.id]?.let {
                    val platform = raftComputerCommandPlatform(it.platform)
                    RaftComputerRackInstall(raftComputerCommands(model.commandContext, platform), platform, onOpenInstallGuide)
                }

            @Composable
            fun block(row: RaftComputerRowModel) {
                RaftComputerRackBlock(
                    row = row,
                    agents = agentsByMachine[row.id].orEmpty(),
                    agentStatusResolver = agentStatusResolver,
                    onClick = { onOpenComputer(row.id) },
                    onOpenAgent = onOpenAgent,
                    fleetColumns = fleetColumns,
                    cellWidth = listCellWidth,
                    usageLoads = usageLoads[row.id].orEmpty(),
                    runtimeCatalog = runtimeCatalog,
                    onOpenUsage = { column, label -> onOpenUsage(row.id, column, label) },
                    action = actionOf(row),
                    onUpgrade = { version -> onUpgradeComputer(row.id, version) },
                    onCopyCommand = onCopyCommand,
                    nowMillis = nowMillis,
                    install = installOf(row),
                )
            }

            @Composable
            fun card(row: RaftComputerRowModel, modifier: Modifier) {
                val computer = computersById[row.id]
                val upgrade = computer?.let { raftComputerUpgradeModel(it, computerControl, nowMillis) }
                val action = actionOf(row)
                val menu =
                    if (computer != null && upgrade != null) {
                        raftComputerCardMenu(computer = computer, upgrade = upgrade, restart = computerControl.restart, createAgentAvailable = onCreateAgent != null)
                    } else {
                        null
                    }
                RaftComputerRackCard(
                    row = row,
                    agents = agentsByMachine[row.id].orEmpty(),
                    agentStatusResolver = agentStatusResolver,
                    onClick = { onOpenComputer(row.id) },
                    onOpenAgent = onOpenAgent,
                    usageLoads = usageLoads[row.id].orEmpty(),
                    runtimeCatalog = runtimeCatalog,
                    onOpenUsage = { column, label -> onOpenUsage(row.id, column, label) },
                    action = action,
                    onUpgrade = { version -> onUpgradeComputer(row.id, version) },
                    onCopyCommand = onCopyCommand,
                    menu = menu,
                    onCreateAgent = { onCreateAgent?.invoke(row.id) },
                    onRestart = { onRestartComputer(row.id) },
                    sortKey = toolbarModel.sort.key,
                    nowMillis = nowMillis,
                    onDelete = { onDeleteComputer(row.id) },
                    install = installOf(row),
                    modifier = modifier,
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                refreshErrorCode?.let { code -> item(key = "refresh-error") { RaftComputersRefreshErrorBanner(code) } }
                // Web: the cards sit in the page's padding; the phone list runs edge to edge under its header.
                if (!list) item(key = "top-gap") { Spacer(Modifier.height(if (cardColumns > 1) RaftComputerCardGridEdge else 16.dp)) }
                if (list && !layout.empty && (fleetColumns.usage.isNotEmpty() || fleetColumns.other.isNotEmpty())) {
                    stickyHeader(key = "rack-header") { RaftComputersRackListHeader(fleetColumns, listCellWidth, runtimeCatalog) }
                }
                actionError?.let { failed -> item(key = "action-error") { RaftComputerActionErrorBanner(failed) } }
                if (layout.empty) {
                    // Web ComputersRackList `data-empty="filters"`: nothing matches; Clear all.
                    // Only Needs attention, and nothing needs it (web MobileComputersPanel `attentionOnly`).
                    val attentionOnly = toolbarModel.filters == RaftComputersRackFilters(status = RaftComputersRackStatusFilter.Attention)
                    item(key = "no-match") {
                        RaftEmptyState(
                            title =
                                stringResource(
                                    if (attentionOnly) Res.string.settings_computers_rack_no_attention_title else Res.string.settings_computers_rack_no_match_title
                                ),
                            description =
                                stringResource(
                                    if (attentionOnly) Res.string.settings_computers_rack_no_attention_description else Res.string.settings_computers_rack_no_match_description
                                ),
                            icon = { RaftComputersIcon(RaftLucideIconName.SearchX, size = 36.dp) },
                            actions = {
                                RaftButton(
                                    text = stringResource(Res.string.settings_computers_rack_filter_clear_all),
                                    onClick = { onToolbarIntent(RaftComputersRackToolbarIntent.ClearFilters) },
                                    modifier = Modifier.testTag(RaftComputersRackTags.EmptyClear),
                                )
                            },
                        )
                    }
                } else {
                    // Every card block after the first sits 12dp under the one before (the grid's gap);
                    // the list's blocks and titles meet at their seams.
                    var blocks = 0
                    fun gap(): Dp = if (blocks++ > 0 && !list) RaftComputerCardGridGap else 0.dp
                    fun computers(section: String, computers: List<SettingsComputerSnapshot>) {
                        val rows = computers.mapNotNull { rowsById[it.id] }
                        if (rows.isEmpty()) return
                        if (list) {
                            // Web MobileComputersPanel's phone list: flat blocks between seams, the whole block opens the computer.
                            rows.forEach { row -> item(key = "computer-$section-${row.id}") { block(row) } }
                            gap()
                        } else {
                            rows.chunked(cardColumns).forEach { rowCards ->
                                val top = gap()
                                item(key = "computers-$section-${rowCards.first().id}") {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = cardEdge).padding(top = top),
                                        horizontalArrangement = Arrangement.spacedBy(RaftComputerCardGridGap),
                                    ) {
                                        rowCards.forEach { row -> card(row, Modifier.weight(1f)) }
                                        // A short last row keeps the grid's card width.
                                        repeat(cardColumns - rowCards.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                    fun foldTitle(key: String, testTag: String, title: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
                        val top = gap()
                        item(key = "title-$key") {
                            if (list) {
                                // Web RackGroupTitle in the list: a row of its own on the canvas, a hairline under it.
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(RaftTheme.colors.layerCanvas)
                                        .raftRackBottomLine(RaftTheme.colors.lineHairline)
                                        .padding(start = RaftComputerBlockEdge, end = RaftComputerBlockEdge, top = 16.dp, bottom = 8.dp)
                                ) {
                                    RaftRackFoldTitle(title = title, count = count, collapsed = collapsed, onToggle = onToggle, modifier = Modifier.testTag(testTag))
                                }
                            } else {
                                RaftRackFoldTitle(
                                    title = title,
                                    count = count,
                                    collapsed = collapsed,
                                    onToggle = onToggle,
                                    modifier = Modifier.padding(horizontal = cardEdge).padding(top = top).testTag(testTag),
                                )
                            }
                        }
                    }
                    layout.groups.forEach { group ->
                        if (grouped && group.computers.isNotEmpty()) {
                            val collapsed = group.key in collapsedGroups
                            foldTitle(group.key, "${RaftComputersRackTags.GroupTitle}-${group.key}", group.title, group.computers.size, collapsed) {
                                val keys = if (folded.first == serverId) folded.second else emptySet()
                                folded = serverId to (if (group.key in keys) keys - group.key else keys + group.key)
                            }
                            if (!collapsed) computers(group.key, group.computers)
                        } else {
                            computers(group.key, group.computers)
                        }
                    }
                    if (layout.offline.isNotEmpty()) {
                        foldTitle("offline", RaftComputersRackTags.OfflineTitle, offlineTitle, layout.offline.size, !offlineOpen) {
                            offlineChoice = offlineScope to !offlineOpen
                        }
                        if (offlineOpen) computers("offline", layout.offline)
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(bottomContentPadding)) }
            }
        }
    }
}

/**
 * Web MobileComputersPanel while the machines load (no saved list to show first): the toolbar
 * already (a list page always shows its head and toolbar, slock #14159), then four card-shaped
 * placeholders (`ComputersSkeleton`: a 35dp block and two lines, 2/5 and 3/5 wide, in a card at
 * least 64dp tall, 10dp apart). No counts until the list is read (#14158).
 */
@Composable
private fun RaftComputersRackLoading(
    toolbar: RaftComputersRackToolbarState,
    onToolbarIntent: (RaftComputersRackToolbarIntent) -> Unit,
    animated: Boolean,
) {
    val toolbarModel = remember(toolbar) { raftComputersRackToolbarModel(toolbar, RaftRackRuntimeColumns(emptyList(), emptyList())) }
    Column(Modifier.fillMaxSize()) {
        RaftComputersRackToolbar(toolbar, toolbarModel, onToolbarIntent)
        val loading = stringResource(Res.string.settings_computers_loading)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics { contentDescription = loading }
                .testTag(RaftComputersRackTags.LoadingSkeleton),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(4) {
                RaftCard(variant = RaftCardVariant.Inset, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RaftSkeleton(Modifier.size(35.dp), animated = animated)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            RaftSkeleton(Modifier.fillMaxWidth(0.4f), variant = RaftSkeletonVariant.Line, animated = animated)
                            RaftSkeleton(Modifier.fillMaxWidth(0.6f), variant = RaftSkeletonVariant.Line, animated = animated)
                        }
                    }
                }
            }
        }
    }
}

/** Web ComputersRack desktop grid: cards at least 250 wide, 12 apart, 20 from the edges. */
internal val RaftComputerCardMinWidth = 250.dp
internal val RaftComputerCardGridGap = 12.dp
internal val RaftComputerCardGridEdge = 20.dp

/** How many card columns fit [width] (CSS `repeat(auto-fill, minmax(250px, 1fr))` in a `p-5` box); at least one. */
internal fun raftComputerCardColumns(width: Dp): Int {
    val inner = width - RaftComputerCardGridEdge * 2
    return ((inner + RaftComputerCardGridGap) / (RaftComputerCardMinWidth + RaftComputerCardGridGap)).toInt().coerceAtLeast(1)
}

/** The wide head's title: "Computers" with the counts beside it (web `ComputersRackHeader`). */
@Composable
internal fun RowScope.RaftComputersWideTitle(title: String, counts: String) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftText(
            title,
            style = RaftType.Headline,
            color = RaftTheme.colors.foreground,
            maxLines = 1,
        )
        RaftText(
            counts,
            modifier = Modifier.weight(1f, fill = false),
            style = RaftTheme.typography.monoMeta,
            color = RaftTheme.colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun RaftComputersAddButton(onClick: () -> Unit) {
    RaftButton(
        text = stringResource(Res.string.settings_computers_add_computer),
        onClick = onClick,
        variant = RaftButtonVariant.Primary,
        leadingIcon = { RaftComputersIcon(RaftLucideIconName.Plus) },
    )
}

/**
 * The page header's counts line: "4 computers · 3 online · 12 agents" (web `ComputersRackCounts`).
 * Beside Add Computer it may take two lines: each count stays whole (no-break spaces inside it and
 * before its "·"), so a line only breaks after a "·", and no count is cut off.
 */
@Composable
internal fun raftComputersCountsLine(counts: RaftComputersCounts): String =
    listOf(
        if (counts.computers == 1) {
            stringResource(Res.string.settings_computers_rack_computer_count_one, counts.computers)
        } else {
            stringResource(Res.string.settings_computers_rack_computer_count_other, counts.computers)
        },
        stringResource(Res.string.settings_computers_rack_online_count, counts.online),
        if (counts.agents == 1) {
            stringResource(Res.string.settings_computers_rack_agent_count_one, counts.agents)
        } else {
            stringResource(Res.string.settings_computers_rack_agent_count_other, counts.agents)
        },
    ).joinToString("\u00A0· ") { it.replace(' ', '\u00A0') }

/**
 * One computer as a web ComputersRack card (`view === "cards"`): the device icon with its light,
 * the name and the Low disk badge; the facts line under the name; then its agents on exactly one
 * line. The whole card opens the computer; each avatar opens its agent. An offline computer's card
 * sits on `fill-subtle` with its agents dimmed.
 */
@Composable
private fun RaftComputerRackCard(
    row: RaftComputerRowModel,
    agents: List<RaftComputerCardAgent>,
    agentStatusResolver: RaftAgentStatusResolver,
    onClick: () -> Unit,
    onOpenAgent: (RaftComputerCardAgent) -> Unit,
    modifier: Modifier = Modifier,
    usageLoads: Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad> = emptyMap(),
    runtimeCatalog: ServerRuntimeCatalog = BundledRuntimeCatalog,
    onOpenUsage: (RaftRackUsageColumn, String) -> Unit = { _, _ -> },
    action: RaftComputerRackAction? = null,
    onUpgrade: (String) -> Unit = {},
    onCopyCommand: (String) -> Unit = {},
    menu: RaftComputerCardMenu? = null,
    onCreateAgent: () -> Unit = {},
    onRestart: () -> Unit = {},
    /** What the list is sorted by: that metric's line leads the meters, bold (web `sortKey`). */
    sortKey: ComputersRackSortKey = ComputersRackSortKey.Name,
    nowMillis: Long = 0L,
    onDelete: () -> Unit = {},
    install: RaftComputerRackInstall? = null,
) {
    RaftCard(modifier = modifier.testTag("rack-row-${row.id}"), onClick = onClick, inactive = !row.online) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RaftComputerDeviceIcon(row)
            Spacer(Modifier.size(RaftComputerCardTitleGap))
            // The name and its badge stay together; the ⋯ (when offered) sits at the card's end.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                RaftText(
                    row.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold),
                    color = RaftTheme.colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.diskLow) {
                    Spacer(Modifier.size(8.dp))
                    RaftBadge(
                        stringResource(Res.string.settings_computers_disk_low),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Warning,
                    )
                }
            }
            if (menu != null) {
                Spacer(Modifier.size(8.dp))
                RaftComputerCardMenuButton(menu, onCreateAgent = onCreateAgent, onRestart = onRestart, onDelete = onDelete)
            }
        }
        RaftComputerCardFactsLine(
            row.cardFacts(),
            Modifier.padding(start = RaftComputerCardIconSize + RaftComputerCardTitleGap, top = 2.dp),
        )
        // Computer owner v1 (web ComputerOwnerLabel): "Owner: <name>" or "Public (no owner)".
        RaftComputerOwnerLine(row.owner, Modifier.padding(start = RaftComputerCardIconSize + RaftComputerCardTitleGap, top = 2.dp))
        Spacer(Modifier.height(12.dp))
        RaftComputerAgentStripRow(agents, dimmed = !row.online, agentStatusResolver = agentStatusResolver, onOpenAgent = onOpenAgent)
        // Online only: an offline computer's meters would be old (web `liveDiskStatus`), and it
        // reads no usage.
        if (row.online) {
            val columns = remember(row.runtimes, runtimeCatalog) { raftRackRuntimeColumns(row.runtimes, runtimeCatalog.runtimes.map { it.id }) }
            Spacer(Modifier.height(12.dp))
            RaftComputerRackMeters(row, columns, usageLoads, runtimeCatalog, onOpenUsage, sortKey, nowMillis)
            if (columns.other.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                RaftComputerRackOtherRuntimes(columns.other, runtimeCatalog)
            }
        }
        // The card's one next step ends its content (web: a card-level action, not a footer note).
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            RaftComputerRackActionRow(action, onUpgrade, onCopyCommand, install = install)
        }
    }
}

/**
 * Web `RackRowMenu`: the card's ⋯ with the Computer page's own actions for this computer. Create
 * agent first (the app's create entry wording and Bot icon), then Restart while it can take one.
 */
@Composable
private fun RaftComputerCardMenuButton(menu: RaftComputerCardMenu, onCreateAgent: () -> Unit, onRestart: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.common_more_actions)
    Box {
        RaftIconButton(
            onClick = { open = true },
            tooltip = label,
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Sm,
        ) {
            RaftComputersIcon(RaftLucideIconName.EllipsisVertical)
        }
        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }, alignEnd = true) {
            if (menu.createAgent) {
                RaftMenuItem(
                    text = stringResource(Res.string.agent_create_action_create),
                    leadingIcon = { MembersMenuIcon(RaftLucideIconName.Bot) },
                    onClick = {
                        open = false
                        onCreateAgent()
                    },
                )
            }
            if (menu.restart) {
                RaftMenuItem(
                    text = stringResource(Res.string.settings_computers_restart),
                    leadingIcon = { MembersMenuIcon(RaftLucideIconName.RotateCcw) },
                    onClick = {
                        open = false
                        onRestart()
                    },
                )
            }
            if (menu.delete) {
                RaftMenuItem(
                    text = stringResource(Res.string.settings_computers_delete_menu),
                    leadingIcon = { MembersMenuIcon(RaftLucideIconName.Trash2) },
                    modifier = Modifier.testTag(RaftComputersRackTags.MenuDelete),
                    onClick = {
                        open = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** What a card's manual upgrade and hints show: the install command for this computer's platform. */
internal class RaftComputerRackInstall(
    val commands: RaftComputerCommandSet?,
    val platform: RaftComputerCommandPlatform,
    val onOpenInstallGuide: () -> Unit,
)

/**
 * Web `RackRowAction` on a card: online, the step sits at the end ("Upgrade to v1.0.53" as an
 * outline button with its circled arrow, and a ⚠ beside it when upgrading from the web may fail
 * or failed; "Upgrade to v1.0.53" with the terminal mark when it is upgraded on the machine
 * itself; "Upgrading…" disabled with its spinner; the restart's progress line; or Raft Desktop's
 * note); offline, the restart command with its copy button across the card. The ⚠ and the manual
 * upgrade open a sheet with what to do and the install command (web: a popover).
 */
@Composable
private fun RaftComputerRackActionRow(
    action: RaftComputerRackAction,
    onUpgrade: (String) -> Unit,
    onCopyCommand: (String) -> Unit,
    /** A card ends its step at its end; a phone list block starts it at the start. */
    alignEnd: Boolean = true,
    install: RaftComputerRackInstall? = null,
) {
    val arrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    var sheet by remember(action) { mutableStateOf<RaftComputerRackSheet?>(null) }
    when (action) {
        is RaftComputerRackAction.OfflineRestart -> RaftComputerCommandLine(action.command, onCopyCommand)
        is RaftComputerRackAction.Upgrade ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, if (alignEnd) Alignment.End else Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
                action.hint?.let { hint ->
                    val label =
                        stringResource(
                            if (hint is RaftComputerRackHint.UpgradeFailed) Res.string.settings_computers_upgrade_failure_hint_label else Res.string.settings_computers_installer_repair_hint_label
                        )
                    RaftIconButton(
                        onClick = { sheet = RaftComputerRackSheet.Hint(hint) },
                        tooltip = label,
                        variant = RaftButtonVariant.Ghost,
                        modifier = Modifier.testTag(RaftComputersRackTags.UpgradeHint),
                    ) {
                        CompositionLocalProvider(LocalRaftContentColor provides RaftTheme.colors.warningStrong) {
                            RaftComputersIcon(RaftLucideIconName.TriangleAlert)
                        }
                    }
                }
                RaftButton(
                    text = stringResource(Res.string.settings_computers_upgrade_to_version, action.version),
                    onClick = { onUpgrade(action.version) },
                    leadingIcon = { RaftComputersIcon(RaftLucideIconName.CircleArrowUp) },
                )
            }
        is RaftComputerRackAction.ManualUpgrade ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
                // ▲ is the one-click upgrade that runs now; this one opens the command to run on that
                // machine, so it carries the terminal mark (web).
                RaftButton(
                    text = stringResource(Res.string.settings_computers_upgrade_to_version, action.version),
                    onClick = { sheet = RaftComputerRackSheet.Manual(action.version) },
                    leadingIcon = { RaftComputersIcon(RaftLucideIconName.Terminal) },
                    modifier = Modifier.testTag(RaftComputersRackTags.ManualUpgrade),
                )
            }
        RaftComputerRackAction.ManagedByDesktop ->
            RaftText(
                stringResource(Res.string.settings_computers_upgrade_status_managed_by_desktop),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
            )
        is RaftComputerRackAction.Upgrading ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
                // Web: the disabled button keeps its label beside the spinner; while the Computer
                // waits for this server's agents first, it says how many (slock #13506).
                RaftButton(
                    text =
                        when {
                            action.waitingAgents == 1 -> stringResource(Res.string.settings_computers_upgrading_draining_one, action.waitingAgents)
                            action.waitingAgents > 1 -> stringResource(Res.string.settings_computers_upgrading_draining_other, action.waitingAgents)
                            else -> stringResource(Res.string.settings_computers_upgrading)
                        },
                    onClick = {},
                    enabled = false,
                    leadingIcon = { RaftSpinner(size = RaftSpinnerSize.Sm) },
                )
            }
        is RaftComputerRackAction.Restarting ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
                RaftComputerRestartProgressLine(action.progress)
            }
    }
    sheet?.let { open ->
        RaftComputerRackInstallSheet(open, install, onCopyCommand, onDismiss = { sheet = null })
    }
}

/** What the card's sheet explains: the manual upgrade, or the hint beside Upgrade. */
private sealed interface RaftComputerRackSheet {
    data class Manual(val version: String) : RaftComputerRackSheet

    data class Hint(val hint: RaftComputerRackHint) : RaftComputerRackSheet
}

/**
 * Web `RackRowAction`'s popovers as a sheet: the sentence (the manual upgrade's, the installer
 * repair's, or the failed upgrade's steps naming its systemd unit), then the install command for
 * this computer's platform and the install guide.
 */
@Composable
private fun RaftComputerRackInstallSheet(
    sheet: RaftComputerRackSheet,
    install: RaftComputerRackInstall?,
    onCopyCommand: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val title: String
    val sentence: String
    when (sheet) {
        is RaftComputerRackSheet.Manual -> {
            title = stringResource(Res.string.settings_computers_upgrade_to_version, sheet.version)
            sentence = stringResource(Res.string.settings_computers_upgrade_status_manual, sheet.version)
        }
        is RaftComputerRackSheet.Hint ->
            when (val hint = sheet.hint) {
                is RaftComputerRackHint.Repair -> {
                    title = stringResource(Res.string.settings_computers_installer_repair_hint_label)
                    sentence = stringResource(Res.string.settings_computers_upgrade_status_installer_repair_hint, hint.version)
                }
                is RaftComputerRackHint.UpgradeFailed -> {
                    title = stringResource(Res.string.settings_computers_upgrade_failure_hint_label)
                    sentence = raftComputerUpgradeFailureHintText(hint.hint)
                }
            }
    }
    RaftDialog(
        onDismissRequest = onDismiss,
        title = title,
        closeLabel = stringResource(Res.string.common_close),
        modifier = Modifier.testTag(RaftComputersRackTags.InstallSheet),
    ) {
        RaftText(sentence, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
        RaftComputerManualInstallCommands(
            commands = install?.commands,
            platform = install?.platform ?: RaftComputerCommandPlatform.MacLinux,
            onCopy = onCopyCommand,
            onOpenInstallGuide = { install?.onOpenInstallGuide?.invoke() },
        )
    }
}

/**
 * Web `ComputerUpgradeFailureHintText`: stop the systemd unit, run the install command once, start
 * it again, naming the unit and its commands when the failure did (commands are the same in
 * every language).
 */
@Composable
internal fun raftComputerUpgradeFailureHintText(hint: SettingsComputerUpgradeFailureHintSnapshot): String {
    val killMode = hint.reason == "unsafe_kill_mode"
    val unit = hint.unit
    if (unit == null || (killMode && hint.killMode == null)) {
        return stringResource(
            if (killMode) Res.string.settings_computers_upgrade_failure_hint_unsafe_kill_mode_generic else Res.string.settings_computers_upgrade_failure_hint_installation_held_generic
        )
    }
    val systemctl =
        when (hint.scope) {
            "user" -> "systemctl --user"
            "system" -> "systemctl"
            else -> "systemctl [--user]"
        }
    val stop = "$systemctl stop $unit"
    val start = "$systemctl start $unit"
    return if (killMode) {
        stringResource(Res.string.settings_computers_upgrade_failure_hint_unsafe_kill_mode, unit, hint.killMode.orEmpty(), stop, start)
    } else {
        stringResource(Res.string.settings_computers_upgrade_failure_hint_installation_held, unit, stop, start)
    }
}

/** Web `ComputersRackViewToggle`: Cards / List (the default first, stdrc 11-05) with their icons, icons only when the labels don't fit. */
@Composable
internal fun RaftComputersRackViewToggle(view: ComputersRackView, onViewChange: (ComputersRackView) -> Unit) {
    val label = stringResource(Res.string.settings_computers_rack_view_label)
    RaftSegmentedControl(
        modifier = Modifier.semantics { contentDescription = label },
        iconsOnlyWhenLabelsDontFit = true,
    ) {
        RaftSegment(
            text = stringResource(Res.string.settings_computers_rack_view_cards),
            selected = view == ComputersRackView.Cards,
            onClick = { onViewChange(ComputersRackView.Cards) },
            icon = { RaftComputersIcon(RaftLucideIconName.LayoutGrid) },
        )
        RaftSegment(
            text = stringResource(Res.string.settings_computers_rack_view_list),
            selected = view == ComputersRackView.List,
            onClick = { onViewChange(ComputersRackView.List) },
            icon = { RaftComputersIcon(RaftLucideIconName.LayoutList) },
        )
    }
}

/** Web phone list block: `px-4 py-3`. */
private val RaftComputerBlockEdge = 16.dp

/** Web `phoneRuntimeGrid`: a usage cell at most 96dp, Other 40dp, 8dp apart. */
private val RaftRackPhoneUsageCellMax = 96.dp
private val RaftRackPhoneOtherCell = 40.dp
private val RaftRackPhoneCellGap = 8.dp

/**
 * Web `phoneRuntimeGrid` (`repeat(n, minmax(0, 96px))` then 40px Other, 8px apart): each usage
 * cell is 96dp where [available] has room, and they narrow together below that (a value too long
 * for its cell is cut with an ellipsis rather than run into the next). The header and every block
 * use this one width, so their columns line up.
 */
internal fun raftRackPhoneUsageCellWidth(available: Dp, usageCount: Int, hasOther: Boolean): Dp {
    if (usageCount <= 0) return 0.dp
    val gaps = RaftRackPhoneCellGap * (usageCount - 1 + if (hasOther) 1 else 0)
    val room = available - gaps - (if (hasOther) RaftRackPhoneOtherCell else 0.dp)
    return (room / usageCount).coerceIn(0.dp, RaftRackPhoneUsageCellMax)
}

/** A 1dp line along the bottom edge (web `border-b`). */
private fun Modifier.raftRackBottomLine(color: Color): Modifier =
    drawBehind {
        val stroke = 1.dp.toPx()
        drawRect(color, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
    }

/**
 * Web `RackListHeader` on a phone: sticky over the list, the usage columns (each runtime's mark
 * and short name) and Other, on the blocks' grid. Not sortable (the toolbar's Sort is).
 */
@Composable
private fun RaftComputersRackListHeader(columns: RaftRackRuntimeColumns, cellWidth: Dp, catalog: ServerRuntimeCatalog) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RaftTheme.colors.layerCard)
            .raftRackBottomLine(RaftTheme.colors.lineSeam)
            .height(28.dp)
            .padding(horizontal = RaftComputerBlockEdge)
            .testTag(RaftComputersRackTags.ListHeader),
        horizontalArrangement = Arrangement.spacedBy(RaftRackPhoneCellGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val muted = RaftTheme.colors.foregroundMuted
        columns.usage.forEach { column ->
            val name = catalog.entry(column.runtimeId)?.displayName ?: raftAgentRuntimeDisplayName(column.runtimeId)
            Row(
                Modifier.width(cellWidth).semantics(mergeDescendants = true) { contentDescription = name },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftRuntimeMark(column.runtimeId, color = muted)
                RaftText(
                    raftRackRuntimeShortName(column.runtimeId, catalog),
                    style = RaftTheme.typography.monoMeta,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (columns.other.isNotEmpty()) {
            RaftText(
                stringResource(Res.string.settings_computers_rack_col_other),
                modifier = Modifier.width(RaftRackPhoneOtherCell),
                style = RaftTheme.typography.monoMeta,
                color = RaftTheme.colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Web `ComputerRackRow` as a phone list block (`variant="phone" view="list"`): flat and full
 * width between seam lines (offline on `fill-subtle`). The device icon with its light, the name,
 * the agent count and Low disk, with the version (or Offline) at the right; the owner line; the
 * agents on one scrolling line; online, the runtime cells under the list's header (each usage
 * column's "x% used" over its meter, or "—", and Other's marks); offline, "Last seen …"; then
 * the one next step. No Disk / Memory and no ⋯: the Computer page, one tap away, has both.
 */
@Composable
private fun RaftComputerRackBlock(
    row: RaftComputerRowModel,
    agents: List<RaftComputerCardAgent>,
    agentStatusResolver: RaftAgentStatusResolver,
    onClick: () -> Unit,
    onOpenAgent: (RaftComputerCardAgent) -> Unit,
    fleetColumns: RaftRackRuntimeColumns,
    cellWidth: Dp,
    usageLoads: Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>,
    runtimeCatalog: ServerRuntimeCatalog,
    onOpenUsage: (RaftRackUsageColumn, String) -> Unit,
    action: RaftComputerRackAction?,
    onUpgrade: (String) -> Unit,
    onCopyCommand: (String) -> Unit,
    nowMillis: Long,
    install: RaftComputerRackInstall? = null,
) {
    val colors = RaftTheme.colors
    val surface = if (row.online) colors.layerCard else colors.fillSubtle.compositeOver(colors.layerCard)
    val hint = colors.foregroundHint
    CompositionLocalProvider(LocalRaftSurfaceColor provides surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(surface)
                .raftClickable(onClick = onClick, onClickLabel = row.name)
                .raftRackBottomLine(colors.lineSeam)
                .padding(horizontal = RaftComputerBlockEdge, vertical = 12.dp)
                .testTag("rack-row-${row.id}"),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftComputerDeviceIcon(row)
                    RaftText(
                        row.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The phone list has no header total: each block keeps its count.
                    RaftText(agents.size.toString(), style = RaftTheme.typography.monoMeta, color = hint, maxLines = 1)
                    if (row.diskLow) {
                        RaftBadge(
                            stringResource(Res.string.settings_computers_disk_low),
                            appearance = RaftBadgeAppearance.Soft,
                            variant = RaftBadgeVariant.Warning,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // The version (with what it may upgrade to), or Offline: the OS is on the Computer page.
                RaftComputerCardFactsLine(
                    when (val facts = row.cardFacts()) {
                        is RaftComputerCardFacts.Online -> facts.copy(osName = null)
                        is RaftComputerCardFacts.Offline -> RaftComputerCardFacts.Offline(lastSeenAt = null)
                    },
                    Modifier.testTag("rack-row-version-${row.id}"),
                )
            }
            RaftComputerOwnerLine(row.owner, Modifier.padding(top = 4.dp))
            if (agents.isNotEmpty()) {
                RaftComputerAgentStripRow(
                    agents,
                    dimmed = !row.online,
                    agentStatusResolver = agentStatusResolver,
                    onOpenAgent = onOpenAgent,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (row.online && (fleetColumns.usage.isNotEmpty() || fleetColumns.other.isNotEmpty())) {
                RaftComputerRackCells(row, fleetColumns, cellWidth, usageLoads, runtimeCatalog, onOpenUsage, nowMillis, Modifier.padding(top = 8.dp))
            }
            if (!row.online) {
                row.lastSeenAt?.let { lastSeen ->
                    RaftText(
                        stringResource(Res.string.settings_computers_rack_last_seen, raftRelativeTimestampLabel(lastSeen)),
                        modifier = Modifier.padding(top = 8.dp),
                        style = RaftTheme.typography.monoMeta,
                        color = hint,
                    )
                }
            }
            if (action != null) {
                Box(Modifier.padding(top = 8.dp)) { RaftComputerRackActionRow(action, onUpgrade, onCopyCommand, alignEnd = false, install = install) }
            }
        }
    }
}

/**
 * A phone block's runtime cells (web `RackUsageCell` / `RackOtherCell` on `phoneRuntimeGrid`):
 * under each of the list's usage columns, this computer's value over its meter, "—" when there
 * is nothing to read, or nothing when it doesn't have that runtime; then Other's marks.
 */
@Composable
private fun RaftComputerRackCells(
    row: RaftComputerRowModel,
    fleetColumns: RaftRackRuntimeColumns,
    cellWidth: Dp,
    usageLoads: Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>,
    runtimeCatalog: ServerRuntimeCatalog,
    onOpenUsage: (RaftRackUsageColumn, String) -> Unit,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val own = remember(row.runtimes, runtimeCatalog) { raftRackRuntimeColumns(row.runtimes, runtimeCatalog.runtimes.map { it.id }) }
    Row(modifier.fillMaxWidth().testTag("rack-metrics-${row.id}"), horizontalArrangement = Arrangement.spacedBy(RaftRackPhoneCellGap), verticalAlignment = Alignment.Top) {
        fleetColumns.usage.forEach { fleetColumn ->
            val column = own.usage.firstOrNull { it.provider == fleetColumn.provider }
            Box(Modifier.width(cellWidth)) {
                if (column != null) {
                    val line = raftRackUsageLine(raftRackUsageCell(column, usageLoads[column.provider]), runtimeCatalog, row.canViewRuntimeUsage, onOpenUsage, row.id, nowMillis)
                    val card = line.usageCard
                    if (card != null) card { RaftRackUsageCellContent(line, click = null) } else RaftRackUsageCellContent(line, click = line.onClick)
                }
            }
        }
        if (fleetColumns.other.isNotEmpty()) {
            val others = fleetColumns.other.filter { it in own.other }
            val names = others.map { runtimeCatalog.entry(it)?.displayName ?: raftAgentRuntimeDisplayName(it) }.joinToString(", ")
            Row(
                Modifier.width(RaftRackPhoneOtherCell).height(18.dp).clipToBounds().semantics { if (names.isNotEmpty()) contentDescription = names },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                others.forEach { RaftRuntimeMark(it, color = RaftTheme.colors.foregroundHint) }
            }
        }
    }
}

/** Web `RackUsageCell`'s face: the value (and the attention pixel) over the meter, or "—". */
@Composable
private fun RaftRackUsageCellContent(line: RaftRackLine, click: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (line.dimmed) Modifier.alpha(0.6f) else Modifier)
            .then(if (click != null) Modifier.raftClickable(onClick = click, onClickLabel = line.semantics, tier = RaftHoverTier.Subtle) else Modifier)
            .clearAndSetSemantics { contentDescription = line.semantics },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            RaftText(
                line.value,
                modifier = Modifier.weight(1f, fill = false),
                style = RaftTheme.typography.monoMeta,
                color = if (line.warn) RaftTheme.colors.warningStrong else if (line.bar != null) RaftTheme.colors.foregroundMuted else RaftTheme.colors.foregroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line.attention) RaftStatusDot(variant = RaftStatusVariant.Warning, size = RaftStatusSize.Sm)
        }
        line.bar?.invoke()
    }
}

/** Web rack card line's name column (`grid-cols-[76px_…]`). */
private val RaftRackLineLabelWidth = 76.dp
private val RaftRackLineGap = 8.dp

/**
 * One line of a card's meters: its name (with the runtime's mark for a usage line), its bar
 * (null: none), its value. [emphasized]: the line the cards are sorted by (web
 * `RackCardLine emphasized`), first and bold in ink.
 */
private class RaftRackLine(
    val label: String,
    val runtimeMark: String? = null,
    val bar: (@Composable () -> Unit)?,
    val value: String,
    val warn: Boolean,
    val attention: Boolean = false,
    val dimmed: Boolean = false,
    val semantics: String,
    val onClick: (() -> Unit)? = null,
    /** A usage line wears its usage card (hover, a click pins); its click is then the card's. */
    val usageCard: (@Composable (trigger: @Composable () -> Unit) -> Unit)? = null,
    val emphasized: Boolean = false,
) {
    fun emphasize(): RaftRackLine =
        RaftRackLine(label, runtimeMark, bar, value, warn, attention, dimmed, semantics, onClick, usageCard, emphasized = true)
}

/** Web `RackCardLine`'s value: warning first, then ink and bold on the sorted line, else hint. */
@Composable
private fun RaftRackLine.valueStyle(): TextStyle =
    RaftTheme.typography.monoMeta.let { if (emphasized) it.copy(fontWeight = FontWeight.Bold) else it }

/**
 * Web rack card meters (`rack-card-meters`): Disk and Memory ("62% used" over a Progress bar,
 * "—" when not reported), then a separator and a line per usage provider. The lines share one
 * grid as on web: a 76dp name column, the bar, and a value column as wide as the widest value.
 *
 * The line the list is sorted by leads, bold (web `leadUsage` / `leadResource`): under
 * "{runtime} remaining" that runtime's line first with a separator under it (when this computer
 * has the runtime), under Memory used Memory before Disk, under Disk used Disk in bold.
 */
@Composable
private fun RaftComputerRackMeters(
    row: RaftComputerRowModel,
    columns: RaftRackRuntimeColumns,
    usageLoads: Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>,
    runtimeCatalog: ServerRuntimeCatalog,
    onOpenUsage: (RaftRackUsageColumn, String) -> Unit,
    sortKey: ComputersRackSortKey,
    nowMillis: Long,
) {
    val diskLabel = stringResource(Res.string.agent_migration_target_disk)
    val memoryLabel = stringResource(Res.string.agent_migration_target_memory)
    val disk = raftRackResourceLine(diskLabel, row.disk).let { if (sortKey == ComputersRackSortKey.Disk) it.emphasize() else it }
    val memory = raftRackResourceLine(memoryLabel, row.memory).let { if (sortKey == ComputersRackSortKey.Memory) it.emphasize() else it }
    val leadProvider = (sortKey as? ComputersRackSortKey.Remaining)?.provider
    val usage =
        columns.usage.map { column ->
            val line = raftRackUsageLine(raftRackUsageCell(column, usageLoads[column.provider]), runtimeCatalog, row.canViewRuntimeUsage, onOpenUsage, row.id, nowMillis)
            if (column.provider == leadProvider) line.emphasize() else line
        }
    val lead = usage.firstOrNull { it.emphasized }
    val rest = usage.filter { !it.emphasized }
    val resources = if (sortKey == ComputersRackSortKey.Memory) listOf(memory, disk) else listOf(disk, memory)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lines = resources + usage
    val valueWidth =
        with(density) {
            lines.maxOf { line ->
                val text = measurer.measure(line.value, style = line.valueStyle(), maxLines = 1, softWrap = false).size.width.toDp()
                text + if (line.attention) RaftStatusSize.Sm.edge + 4.dp else 0.dp
            } + 1.dp
        }
    Column(Modifier.testTag("${RaftComputersRackTags.CardMeters}-${row.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lead != null) {
            RaftRackLineRow(lead, valueWidth)
            RaftSeparator()
        }
        resources.forEach { RaftRackLineRow(it, valueWidth) }
        if (rest.isNotEmpty()) {
            RaftSeparator()
            rest.forEach { RaftRackLineRow(it, valueWidth) }
        }
    }
}

@Composable
private fun RaftRackLineRow(line: RaftRackLine, valueWidth: Dp) {
    val card = line.usageCard
    if (card != null) {
        card { RaftRackLineRowContent(line, valueWidth, click = null) }
    } else {
        RaftRackLineRowContent(line, valueWidth, click = line.onClick)
    }
}

@Composable
private fun RaftRackLineRowContent(line: RaftRackLine, valueWidth: Dp, click: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (line.dimmed) Modifier.alpha(0.6f) else Modifier)
            .then(if (click != null) Modifier.raftClickable(onClick = click, onClickLabel = line.semantics, tier = RaftHoverTier.Subtle) else Modifier)
            .clearAndSetSemantics { contentDescription = line.semantics },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RaftRackLineLabelWidth)) { RaftRackLineLabel(line) }
        Spacer(Modifier.width(RaftRackLineGap))
        Box(Modifier.weight(1f)) { line.bar?.invoke() }
        Spacer(Modifier.width(RaftRackLineGap))
        Row(Modifier.width(valueWidth), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (line.attention) RaftStatusDot(variant = RaftStatusVariant.Warning, size = RaftStatusSize.Sm)
            RaftText(
                line.value,
                style = line.valueStyle(),
                color =
                    when {
                        line.warn -> RaftTheme.colors.warningStrong
                        line.emphasized -> RaftTheme.colors.foreground
                        else -> RaftTheme.colors.foregroundHint
                    },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Web `LINE_LABEL_CLASS` (footnote, muted; bold in ink on the sorted line), after the runtime's mark. */
@Composable
private fun RaftRackLineLabel(line: RaftRackLine) {
    val ink = if (line.emphasized) RaftTheme.colors.foreground else RaftTheme.colors.foregroundMuted
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        line.runtimeMark?.let { RaftRuntimeMark(it, color = ink) }
        RaftText(
            line.label,
            style = RaftTheme.typography.footnote.let { if (line.emphasized) it.copy(fontWeight = FontWeight.Bold) else it },
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Web `RackResource` as a card line: a Progress (orange when it warns), or "—" when not reported. */
@Composable
private fun raftRackResourceLine(label: String, meter: RaftComputerResourceMeter?): RaftRackLine {
    if (meter == null) return RaftRackLine(label = label, bar = null, value = "—", warn = false, semantics = "$label —")
    val value = stringResource(Res.string.settings_computers_rack_percent_used, "${meter.percentUsed}%")
    return RaftRackLine(
        label = label,
        bar = {
            RaftProgressBar(
                value = meter.percentUsed / 100f,
                variant = if (meter.warn) RaftProgressBarVariant.Warning else RaftProgressBarVariant.Primary,
                size = RaftProgressBarSize.Sm,
                contentDescription = null,
            )
        },
        value = value,
        warn = meter.warn,
        semantics = "$label $value",
    )
}

/**
 * Web `RackUsageCardLine`: the runtime's mark and short name, then a Meter and "x% used" (warning
 * from 80%); used up, when it is back ("Back in 3h", slock #14058) or "Used up" when that isn't
 * known; or "—"; the attention pixel when an account needs it; a stale read dimmed. A tap opens
 * the provider's usage sheet when the viewer may see the usage.
 */
@Composable
private fun raftRackUsageLine(
    cell: RaftRackUsageCell,
    runtimeCatalog: ServerRuntimeCatalog,
    canView: Boolean,
    onOpenUsage: (RaftRackUsageColumn, String) -> Unit,
    machineId: String,
    nowMillis: Long,
): RaftRackLine {
    val runtimeId = cell.column.runtimeId
    val name = runtimeCatalog.entry(runtimeId)?.displayName ?: raftAgentRuntimeDisplayName(runtimeId)
    val label = raftRackRuntimeShortName(runtimeId, runtimeCatalog)
    val onClick = if (canView) ({ onOpenUsage(cell.column, name) }) else null
    val cardHost = LocalRaftRackUsageCard.current
    val usageCard: (@Composable (@Composable () -> Unit) -> Unit)? =
        if (canView && cardHost != null) {
            { trigger -> cardHost.card(machineId, cell.column, name, trigger) }
        } else {
            null
        }
    return when (cell) {
        is RaftRackUsageCell.Unavailable ->
            RaftRackLine(
                label = label,
                runtimeMark = runtimeId,
                bar = null,
                value = "—",
                warn = false,
                attention = cell.attention,
                semantics = stringResource(Res.string.settings_computers_rack_usage_unavailable, name),
                onClick = onClick,
                usageCard = usageCard,
            )
        is RaftRackUsageCell.Value -> {
            val words = raftRackUsageWords(cell, nowMillis)
            val value = words.value
            RaftRackLine(
                label = label,
                runtimeMark = runtimeId,
                bar = {
                    RaftMeter(
                        used = if (cell.usedUp) 100f else cell.used.toFloat(),
                        valueText = value,
                        size = RaftMeterSize.Sm,
                        warnAt = RAFT_RACK_USAGE_WARN_PERCENT / 100f,
                        showValue = false,
                    )
                },
                value = value,
                warn = cell.warn,
                attention = cell.attention,
                dimmed = cell.stale,
                semantics =
                    stringResource(
                        if (cell.stale) Res.string.settings_computers_rack_usage_meter_stale else Res.string.settings_computers_rack_usage_meter,
                        name,
                        words.spoken,
                    ),
                onClick = onClick,
                usageCard = usageCard,
            )
        }
    }
}

/** A runtime's short name on a card line or the list's header ("Claude", "Codex"): the first word of its name. */
internal fun raftRackRuntimeShortName(runtimeId: String, runtimeCatalog: ServerRuntimeCatalog): String {
    val name = runtimeCatalog.entry(runtimeId)?.displayName ?: raftAgentRuntimeDisplayName(runtimeId)
    return name.substringBefore(' ').ifBlank { runtimeId }
}

/** A usage value's words: [value] as shown, [spoken] for its name (the reset in full). */
private data class RaftRackUsageWords(val value: String, val spoken: String)

/**
 * Web `usedText` / `cellLabel` (slock #14058): "62% used"; used up, when it is back ("Back in
 * 3h", "3h 后重置") and, for its name, the plan and "used up, resets in 3 hours"; "Used up" when
 * the reset isn't known or has passed. Counted from [nowMillis] on each composition.
 */
@Composable
private fun raftRackUsageWords(cell: RaftRackUsageCell.Value, nowMillis: Long): RaftRackUsageWords {
    if (!cell.usedUp) {
        val value = stringResource(Res.string.settings_computers_rack_percent_used, "${cell.used}%")
        return RaftRackUsageWords(value, value)
    }
    val reset = raftRackReset(cell.resetsAt, nowMillis)
    if (reset == null) {
        val value = stringResource(Res.string.settings_computers_rack_used_up)
        return RaftRackUsageWords(value, value)
    }
    val short =
        when (reset.unit) {
            RaftRelativeTimeUnit.Minute -> stringResource(Res.string.settings_computers_rack_resets_in_minutes, reset.count)
            RaftRelativeTimeUnit.Hour -> stringResource(Res.string.settings_computers_rack_resets_in_hours, reset.count)
            RaftRelativeTimeUnit.Day -> stringResource(Res.string.settings_computers_rack_resets_in_days, reset.count)
        }
    val full = stringResource(Res.string.settings_computers_rack_used_up_resets, RaftRelativeTimeLabel.Relative(reset.count, reset.unit).localized())
    val plan = cell.plan ?: stringResource(Res.string.settings_computers_runtime_usage_account_fallback)
    return RaftRackUsageWords(short, "$plan · $full")
}

/** Web `RackOtherCardLine`: "Other runtimes", then each runtime's mark and name (with its status). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RaftComputerRackOtherRuntimes(runtimeIds: List<String>, runtimeCatalog: ServerRuntimeCatalog) {
    val hint = RaftTheme.colors.foregroundHint
    val deprecated = stringResource(Res.string.runtime_suffix_deprecated)
    val experimental = stringResource(Res.string.runtime_suffix_experimental)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftText(stringResource(Res.string.settings_computers_rack_other_runtimes), style = RaftTheme.typography.footnote, color = hint, maxLines = 1)
        runtimeIds.forEach { runtimeId ->
            val entry = runtimeCatalog.entry(runtimeId)
            val name = entry?.displayName ?: raftAgentRuntimeDisplayName(runtimeId)
            val suffix =
                when {
                    entry?.deprecated == true -> deprecated
                    entry?.experimental == true -> experimental
                    else -> ""
                }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftRuntimeMark(runtimeId, color = hint)
                RaftText(name + suffix, style = RaftTheme.typography.footnote, color = hint, maxLines = 1)
            }
        }
    }
}

private val RaftComputerCardIconSize = 20.dp
private val RaftComputerCardTitleGap = 12.dp
private val RaftComputerCardAvatarSize = RaftAvatarSize.Sm
private val RaftComputerCardAvatarGap = 4.dp

/** The device shape with the row's light on its corner (web `rack-row-icon` + `ComputerRowStatusDot`). */
@Composable
private fun RaftComputerDeviceIcon(row: RaftComputerRowModel) {
    Box(Modifier.size(RaftComputerCardIconSize)) {
        CompositionLocalProvider(LocalRaftContentColor provides RaftTheme.colors.foregroundMuted) {
            RaftComputersIcon(
                if (row.kind == RaftComputerKind.Computer) RaftLucideIconName.Monitor else RaftLucideIconName.Server,
                size = RaftComputerCardIconSize,
            )
        }
        RaftStatusDot(
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp),
            presence =
                when (row.dot) {
                    RaftComputerDot.Online -> RaftPresence.Online
                    RaftComputerDot.Offline -> RaftPresence.Offline
                    RaftComputerDot.Upgrade -> null
                },
            color = if (row.dot == RaftComputerDot.Upgrade) RaftUiPalette.attention else null,
        )
    }
}

/**
 * The facts line (web card `CardDescription`, Space Mono `foreground-hint`). Online it is one line
 * that only the OS gives way on: "Ubuntu 24.04 · v1.0.49 → v1.0.53", the arrow in bold ink, the
 * version floor's chip in bold `warning-strong`. Offline: "Offline · Last seen 2 days ago.".
 */
@Composable
private fun RaftComputerCardFactsLine(facts: RaftComputerCardFacts, modifier: Modifier = Modifier) {
    val style = RaftTheme.typography.monoMeta
    val hint = RaftTheme.colors.foregroundHint
    when (facts) {
        is RaftComputerCardFacts.Offline -> {
            val offline = stringResource(Res.string.settings_computers_status_offline)
            val text =
                facts.lastSeenAt?.let { "$offline · " + stringResource(Res.string.settings_computers_rack_last_seen, raftRelativeTimestampLabel(it)) }
                    ?: offline
            RaftText(text, modifier = modifier, style = style, color = hint)
        }
        is RaftComputerCardFacts.Online -> {
            val version =
                if (facts.kind == RaftComputerKind.Computer && facts.version != null) {
                    stringResource(Res.string.settings_computers_rack_version, facts.version.removePrefix("v"))
                } else {
                    raftComputerKindVersion(facts.kind, facts.version)
                }
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                if (facts.osName != null) {
                    RaftText(
                        facts.osName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = style,
                        color = hint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RaftText(if (version != null) " · $version" else "", style = style, color = hint, maxLines = 1, softWrap = false)
                } else if (version != null) {
                    RaftText(version, style = style, color = hint, maxLines = 1, softWrap = false)
                }
                when (val notice = facts.notice) {
                    is RaftComputerVersionNotice.Available ->
                        RaftText(
                            " → " + stringResource(Res.string.settings_computers_rack_version, notice.version.removePrefix("v")),
                            style = style.copy(fontWeight = FontWeight.Bold),
                            color = RaftTheme.colors.foreground,
                            maxLines = 1,
                            softWrap = false,
                        )
                    is RaftComputerVersionNotice.BelowFloor ->
                        RaftText(
                            " " +
                                stringResource(
                                    if (notice.reinstall) Res.string.settings_computers_needs_reinstall else Res.string.settings_computers_needs_upgrade
                                ),
                            style = style.copy(fontWeight = FontWeight.Bold),
                            color = RaftTheme.colors.warningStrong,
                            maxLines = 1,
                            softWrap = false,
                        )
                    null -> Unit
                }
            }
        }
    }
}

/**
 * Web `RackSlotStrip`: the computer's agents on exactly one line, so every card's sections keep
 * their height; more than fit scroll sideways, and a side with more beyond it fades out (raft-ui
 * `mask-fade-x`, 24dp). The strip reaches 4dp past its line on every side (web `-m-1 p-1`), so the
 * activity lights are not cut and the first avatar stays on the edge. A computer with none keeps
 * the line's height (on a card; a list block leaves the line out).
 */
@Composable
private fun RaftComputerAgentStripRow(
    agents: List<RaftComputerCardAgent>,
    dimmed: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpenAgent: (RaftComputerCardAgent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slot = RaftComputerCardAvatarSize.edge
    if (agents.isEmpty()) {
        Spacer(modifier.fillMaxWidth().height(slot))
        return
    }
    val scroll = rememberScrollState()
    Row(
        modifier
            .fillMaxWidth()
            .raftRackStripOutset(RaftComputerStripInset)
            .raftMaskFadeX(scroll)
            .horizontalScroll(scroll)
            .padding(RaftComputerStripInset),
        horizontalArrangement = Arrangement.spacedBy(RaftComputerCardAvatarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        agents.forEach { agent ->
            key(agent.id) { RaftComputerCardAgentSlot(agent, dimmed, agentStatusResolver, onClick = { onOpenAgent(agent) }) }
        }
    }
}

private val RaftComputerStripInset = 4.dp

/** Lays the strip out [outset] bigger on every side than its slot in the card (CSS `-m-1`). */
private fun Modifier.raftRackStripOutset(outset: Dp): Modifier =
    layout { measurable, constraints ->
        val edge = outset.roundToPx()
        val placeable = measurable.measure(constraints.offset(edge * 2, edge * 2))
        layout((placeable.width - edge * 2).coerceAtLeast(0), (placeable.height - edge * 2).coerceAtLeast(0)) {
            placeable.place(-edge, -edge)
        }
    }

/** One agent (web `AgentRackSlot`): its avatar with the activity light; a tap opens its profile. */
@Composable
private fun RaftComputerCardAgentSlot(
    agent: RaftComputerCardAgent,
    dimmed: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    onClick: () -> Unit,
) {
    val live =
        projectHomeAgentStatusIndicatorState(
            showPresence = true,
            memberId = agent.id,
            canonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, agent.id),
        )
    val size = RaftComputerCardAvatarSize
    RaftAvatarWithStatus(
        statusColor = live?.color,
        avatarSize = size.edge,
        statusPulsing = live?.pulsing == true,
        cutoutColor = LocalRaftSurfaceColor.current,
        modifier =
            Modifier
                .then(if (dimmed) Modifier.raftGrayscale(outset = 6.dp) else Modifier)
                .clickable(role = Role.Button, onClickLabel = agent.name, onClick = onClick)
                .semantics { contentDescription = agent.name },
    ) {
        // web AgentRackSlot -> AgentAvatar: an agent without a face, or whose image fails, wears the robot.
        RaftAppAvatarSticker(
            kind = RaftAvatarKind.Agent,
            avatarWireValue = agent.avatarWireValue,
            label = agent.name,
            size = size,
            cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, agent.id),
        )
    }
}

// endregion

// region Detail

@Composable
private fun RaftSettingsComputerDetailScreen(pager: Pager, target: RaftComputerPageTarget, pageVisible: Boolean) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-computer-${target.machineId}") {
            SharedSettingsViewModel(environment.dataSource) { bridge.log("settings_computer_detail $it") }
        }
    val settingsState by settingsViewModel.state.collectAsState()
    val firstFrame = rememberRaftComputersFirstFrame(bridge, environment.storeScope)
    val cachedDetail =
        remember(firstFrame, target) {
            (target.serverId ?: firstFrame.serverId)?.let { serverId ->
                firstFrame.computersStore.cachedSettingsComputerDetail(serverId, target.machineId)
            }
        }
    LaunchedEffect(target) {
        bridge.log("settings_computers_open target=detail")
        settingsViewModel.dispatch(
            SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.ComputerDetail, target.machineId)
        )
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    fun closePage() = pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    val deleted = raftComputerDeleteCompleted(settingsState, target.machineId)
    LaunchedEffect(deleted) {
        if (deleted) {
            bridge.log("settings_computers_deleted close_page=true")
            closePage()
        }
    }
    // The target server is checked against the shell's current server, which may change while open.
    val session by rememberRaftComputersSession(environment.authStore, firstFrame.serverId)
    val model =
        when (val read = session) {
            RaftComputersSessionRead.Pending -> RaftComputerDetailModel.Loading
            is RaftComputersSessionRead.Read ->
                raftComputerDetailModel(
                    state =
                        raftComputersFirstFrameState(
                            settingsState.computerDetail,
                            cachedDetail?.takeIf { firstFrame.serverId == read.currentServerId },
                        ),
                    machineId = target.machineId,
                    targetServerId = target.serverId,
                    currentServerId = read.currentServerId,
                )
        }
    val currentServerId = (session as? RaftComputersSessionRead.Read)?.currentServerId
    RaftComputersRealtimeEffect(
        pager = pager,
        storeScope = environment.storeScope,
        // Only the machine's own server: a target on another server renders Unavailable anyway.
        serverId = currentServerId?.takeIf { target.serverId == null || target.serverId == it },
        visible = pageVisible,
        dispatch = settingsViewModel::dispatch,
    )
    RaftLiveCatchUpEffect(
        serverId = currentServerId?.takeIf { target.serverId == null || target.serverId == it },
        visible = pageVisible,
    ) {
        settingsViewModel.dispatch(SharedSettingsIntent.CatchUpComputers)
    }
    val refreshing = settingsState.isRefreshing(SharedSettingsDestination.ComputerDetail)
    val refreshErrorCode =
        raftComputersRefreshErrorCode(
            hasContent = model is RaftComputerDetailModel.Content,
            routeErrorCode = settingsState.routeErrorCode,
            refreshing = refreshing,
        )
    fun refresh() = settingsViewModel.dispatch(SharedSettingsIntent.Refresh)
    // Transfer owner offers the server's people: the members store's roster, no new API.
    val ownerMembersServerId = currentServerId?.let(::ServerId)
    val ownerMembersEntry by
        remember(environment.serverMembersStore, ownerMembersServerId) {
            ownerMembersServerId?.let { environment.serverMembersStore.serverMembersEntryFlow(it) } ?: kotlinx.coroutines.flow.flowOf(null)
        }.collectAsState(ownerMembersServerId?.let { environment.serverMembersStore.currentServerMembersEntry(it) })
    val agentStatusResolver =
        remember(environment.serverMembersStore, currentServerId) {
            raftScopedAgentStatusResolver(environment.serverMembersStore, currentServerId)
        }
    // The runtime chips name the runtimes of this computer's own server: its cached catalog on a
    // cold start, then the server's; never the catalog another server read last.
    val runtimeCatalog =
        rememberServerRuntimeCatalog(
            remember(bridge, environment.storeScope) {
                RaftSharedStoreRegistry.get(bridge).serverRuntimeCatalogRepository(environment.storeScope)
            },
            target.serverId ?: currentServerId,
        )
    SettingsDetailShell(
        title = (model as? RaftComputerDetailModel.Content)?.computer?.name
            ?: stringResource(Res.string.settings_computers_detail_computer),
        subtitle = "",
        onBack = ::closePage,
    ) {
        RaftStorePullToRefreshBox(refreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        RaftComputersTheme {
            RaftSettingsComputerDetailContent(
                model = model,
                actionState = settingsState.actionState,
                refreshErrorCode = refreshErrorCode,
                bottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg,
                onRetry = ::refresh,
                onIntent = settingsViewModel::dispatch,
                agentStatusResolver = agentStatusResolver,
                computerControl = settingsState.computerControl,
                runtimeCatalog = runtimeCatalog,
                ownerMembers = ownerMembersEntry?.members.orEmpty(),
                onOpenAgent = { agent ->
                    val serverId = currentServerId
                    val pageData =
                        RaftMembersPayload(instanceId = "settings-computer-agent").toMemberDetailPageData(
                            MemberDetailIdentity(MemberId(agent.id), MemberKind.Agent, serverId?.let(::ServerId))
                        )
                    if (pageData == null) {
                        bridge.log("settings_computers_open_agent_skip reason=invalid_agent")
                    } else {
                        pager.openRaftPage(
                            pageName = MEMBER_DETAIL_PAGE,
                            pageData = pageData,
                            storeScopeOverride = environment.storeScope,
                            onMissingScope = { bridge.log("settings_computers_open_agent_skip reason=missing_shell_scope") },
                        )
                    }
                },
                onOpenCreator = { creator ->
                    val serverId = currentServerId
                    val pageData =
                        RaftMembersPayload(instanceId = "settings-computer-creator").toMemberDetailPageData(
                            MemberDetailIdentity(MemberId(creator.id), MemberKind.Human, serverId?.let(::ServerId))
                        )
                    if (pageData == null) {
                        bridge.log("settings_computers_open_creator_skip reason=invalid_creator")
                    } else {
                        pager.openRaftPage(
                            pageName = MEMBER_DETAIL_PAGE,
                            pageData = pageData,
                            storeScopeOverride = environment.storeScope,
                            onMissingScope = { bridge.log("settings_computers_open_creator_skip reason=missing_shell_scope") },
                        )
                    }
                },
            )
        }
        }
    }
}

@Composable
internal fun RaftSettingsComputerDetailContent(
    model: RaftComputerDetailModel,
    actionState: SharedSettingsActionState?,
    refreshErrorCode: String? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onRetry: () -> Unit,
    onIntent: (SharedSettingsIntent) -> Unit,
    onOpenAgent: (SettingsComputerAgentSnapshot) -> Unit,
    /** Opens the profile of a managed Computer's creator. */
    onOpenCreator: (RaftComputerCreatorModel) -> Unit = {},
    /** Live agent activity for the agent rows (the members store); empty in fixtures. */
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    /** Restart / upgrade progress of the open Computer (the settings store's `computerControl`). */
    computerControl: SettingsComputerControlState = SettingsComputerControlState(),
    /** The computer's server's runtime catalog ([rememberServerRuntimeCatalog]): the runtime chips. */
    runtimeCatalog: ServerRuntimeCatalog = BundledRuntimeCatalog,
    /** The server's members (the members store's roster), for the Transfer owner dialog's people. */
    ownerMembers: List<MemberProfile> = emptyList(),
) {
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        when (model) {
            RaftComputerDetailModel.Loading ->
                RaftComputersStateBlock(title = stringResource(Res.string.settings_computers_detail_loading), loading = true)
            RaftComputerDetailModel.Unavailable ->
                RaftComputersStateBlock(
                    title = stringResource(Res.string.settings_computers_detail_unavailable),
                    iconName = RaftLucideIconName.Monitor,
                )
            is RaftComputerDetailModel.Failed ->
                if (model.failure.noAccess) {
                    RaftComputersStateBlock(
                        title = stringResource(Res.string.settings_computers_detail_no_access),
                        detail = raftComputerFailureMessage(model.failure),
                        iconName = RaftLucideIconName.Lock,
                    )
                } else {
                    RaftComputersStateBlock(
                        title = stringResource(Res.string.settings_computers_detail_error_load),
                        detail = raftComputerFailureMessage(model.failure),
                        iconName = RaftLucideIconName.TriangleAlert,
                        actionLabel = stringResource(Res.string.common_retry).takeIf { model.failure.retryable },
                        onAction = onRetry,
                    )
                }
            is RaftComputerDetailModel.Content ->
                RaftComputerDetailBody(
                    model = model,
                    actionState = actionState,
                    computerControl = computerControl,
                    refreshErrorCode = refreshErrorCode,
                    bottomContentPadding = bottomContentPadding,
                    agentStatusResolver = agentStatusResolver,
                    onIntent = onIntent,
                    onOpenAgent = onOpenAgent,
                    onOpenCreator = onOpenCreator,
                    runtimeCatalog = runtimeCatalog,
                    ownerMembers = ownerMembers,
                )
        }
    }
}

@Composable
private fun RaftComputerDetailBody(
    model: RaftComputerDetailModel.Content,
    actionState: SharedSettingsActionState?,
    computerControl: SettingsComputerControlState,
    refreshErrorCode: String?,
    bottomContentPadding: Dp,
    agentStatusResolver: RaftAgentStatusResolver,
    onIntent: (SharedSettingsIntent) -> Unit,
    onOpenAgent: (SettingsComputerAgentSnapshot) -> Unit,
    onOpenCreator: (RaftComputerCreatorModel) -> Unit,
    runtimeCatalog: ServerRuntimeCatalog,
    ownerMembers: List<MemberProfile>,
) {
    val computer = model.computer
    val snapshot = model.snapshot
    val machineId = computer.id
    val canEdit = model.canEdit
    val canRemove = model.canRemove
    val currentAction = actionState?.takeIf { it.itemId == machineId }
    var confirmDelete by remember(machineId) { mutableStateOf(false) }
    // Computer owner v1: the Transfer owner dialog, or a Make it mine in flight (its failure is a toast).
    var transferOwnerOpen by remember(machineId) { mutableStateOf(false) }
    var claimingOwner by remember(machineId) { mutableStateOf(false) }
    var transferSubmitted by remember(machineId) { mutableStateOf(false) }
    val ownerAction = currentAction?.takeIf { it.action == COMPUTER_ACTION_OWNER }
    val ownerSaving = ownerAction?.inProgress == true
    val ownerFailed = ownerAction != null && !ownerAction.inProgress && ownerAction.hasComputerActionError()
    val toastHost = LocalRaftToastHostState.current
    val makeMineFailed = stringResource(Res.string.settings_computers_owner_make_mine_failed)
    LaunchedEffect(ownerSaving, ownerFailed) {
        if (ownerSaving) return@LaunchedEffect
        // Web: a save closes the dialog; a failed one keeps it open saying so.
        if (transferOwnerOpen && transferSubmitted && ownerAction != null && !ownerFailed) transferOwnerOpen = false
        if (claimingOwner) {
            claimingOwner = false
            if (ownerFailed) toastHost?.show(makeMineFailed, intent = RaftToastIntent.Error)
        }
    }
    val nowMillis = currentRaftTimeContextProvider?.current?.nowEpochMillis ?: defaultNowMillis()
    val upgrade = raftComputerUpgradeModel(snapshot.computer, computerControl, nowMillis)
    val controls =
        RaftComputerControlsContext(
            computer = snapshot.computer,
            upgrade = upgrade,
            restart = computerControl.restart,
            canManage = snapshot.canManageMachines,
            commandContext = snapshot.commandContext,
            onRestart = { onIntent(SharedSettingsIntent.RestartComputer(machineId)) },
            onUpgrade = { version -> onIntent(SharedSettingsIntent.UpgradeComputer(machineId, version)) },
            onCopy = { command -> onIntent(SharedSettingsIntent.CopyComputerCommand(command)) },
            onOpenInstallGuide = { onIntent(SharedSettingsIntent.OpenComputerInstallGuide) },
        )
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") { RaftComputerDetailHeader(computer) }
        item(key = "version-notice") { RaftComputerVersionNotice(controls) }
        raftComputerMemoryLimitNotApplied(snapshot.computer)?.let { scopes ->
            item(key = "memory-limit-not-applied") { RaftComputerMemoryLimitNotAppliedBanner(scopes) }
        }
        refreshErrorCode?.let { code -> item(key = "refresh-error") { RaftComputersRefreshErrorBanner(code) } }
        currentAction
            // The owner's failures are the dialog's line and Make it mine's toast (web).
            ?.takeIf { !it.inProgress && it.hasComputerActionError() && it.action != COMPUTER_ACTION_OWNER }
            ?.let { failed ->
                item(key = "action-error") { RaftComputerActionErrorBanner(failed) }
            }
        // Web MachineDetailPanel order: Name, Description, Info, Agents, Workspaces, Actions.
        item(key = "name") {
            RaftComputerEditableSection(
                key = machineId,
                title = stringResource(Res.string.settings_computers_section_name),
                editLabel = stringResource(Res.string.settings_computers_edit_name),
                canEdit = canEdit,
                value = computer.name,
                emptyText = null,
                label = stringResource(Res.string.settings_computers_label_name),
                placeholder = stringResource(Res.string.settings_computers_placeholder_name),
                multiline = false,
                allowBlank = false,
                saveState = currentAction?.takeIf { it.action == COMPUTER_ACTION_RENAME },
                onSave = { onIntent(SharedSettingsIntent.RenameComputer(SettingsComputerRenameRequest(machineId, it))) },
            )
        }
        item(key = "description") {
            RaftComputerEditableSection(
                key = machineId,
                title = stringResource(Res.string.settings_computers_section_description),
                editLabel = stringResource(Res.string.settings_computers_edit_description),
                canEdit = canEdit,
                value = computer.description.orEmpty(),
                emptyText = stringResource(Res.string.settings_computers_state_no_description),
                label = stringResource(Res.string.settings_computers_label_description),
                placeholder = stringResource(Res.string.settings_computers_placeholder_description),
                multiline = true,
                allowBlank = true,
                maxLength = RAFT_COMPUTER_DESCRIPTION_MAX_LENGTH,
                saveState = currentAction?.takeIf { it.action == COMPUTER_ACTION_DESCRIPTION },
                onSave = {
                    onIntent(SharedSettingsIntent.UpdateComputerDescription(SettingsComputerDescriptionUpdateRequest(machineId, it)))
                },
            )
        }
        item(key = "info") {
            RaftSection(title = stringResource(Res.string.settings_computers_section_info)) {
                RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                    Item(stringResource(Res.string.settings_computers_label_os), snapshot.computer.platform?.takeIf { it.isNotBlank() })
                    Item(stringResource(Res.string.settings_computers_label_version), raftComputerVersionLine(computer), mono = true)
                    // Web: the runtimes as chips, cyan-soft when detected, muted with why not otherwise.
                    Item(stringResource(Res.string.settings_computers_label_runtimes)) {
                        // Usage runtimes open their account usage (web RuntimeAccountUsageGateChip).
                        ComputerRuntimeUsageChips(
                            raftComputerRuntimeChips(computer.runtimes, runtimeCatalog),
                            machineId,
                            enabled = canEdit,
                            runtimeVersions = snapshot.computer.runtimeVersions,
                        ) { chip ->
                            RaftComputerRuntimeBadge(chip)
                        }
                    }
                    // Web: the date it was created ("Oct 22, 2026"), as on an agent's page.
                    Item(stringResource(Res.string.settings_computers_label_created), raftComputerCreatedDate(model.createdAt), mono = true)
                    // Web: managed Computers name who attached them beside Created; raw daemons don't.
                    if (model.showsCreator) {
                        Item(stringResource(Res.string.settings_computers_label_creator)) {
                            val creator = model.creator
                            if (creator != null) {
                                RaftComputerCreatorLink(creator, onClick = { onOpenCreator(creator) })
                            } else {
                                RaftText(
                                    stringResource(Res.string.settings_computers_state_no_creator),
                                    style = RaftTheme.typography.callout,
                                    color = RaftTheme.colors.foregroundHint,
                                )
                            }
                        }
                    }
                    // Computer owner v1 (web ComputerOwnerField): who Raft records as its owner, or Public.
                    Item(stringResource(Res.string.settings_computers_label_owner)) {
                        RaftComputerOwnerField(
                            owner = model.owner,
                            canTransfer = model.canTransferOwner,
                            canClaim = model.canClaimOwner,
                            claiming = claimingOwner && ownerSaving,
                            onOpenOwner = onOpenCreator,
                            onTransfer = {
                                transferSubmitted = false
                                transferOwnerOpen = true
                            },
                            onClaim = {
                                val me = snapshot.currentUserId
                                if (me != null && !ownerSaving) {
                                    claimingOwner = true
                                    onIntent(SharedSettingsIntent.SetComputerOwner(SettingsComputerOwnerRequest(machineId, me)))
                                }
                            },
                        )
                    }
                }
                // Web: an offline Computer's version row links the notes of the version it can move to.
                upgrade.releaseNotes?.takeIf { !snapshot.computer.online }?.let { notes ->
                    Spacer(Modifier.height(8.dp))
                    RaftComputerReleaseNotesLink(notes)
                }
            }
        }
        item(key = "connection") { RaftComputerConnectionSection(controls) }
        item(key = "agents") { RaftComputerAgentsSection(snapshot, agentStatusResolver, onOpenAgent) }
        item(key = "workspaces") {
            RaftComputerWorkspacesSection(
                snapshot = snapshot,
                scanning =
                    actionState?.let { it.action == COMPUTER_ACTION_SCAN_WORKSPACES && it.itemId == machineId && it.inProgress } == true,
                onScan = { onIntent(SharedSettingsIntent.ScanComputerWorkspaces(machineId)) },
            )
        }
        // Like the web, key rotation is not offered here.
        if (snapshot.canManageMachines) {
            item(key = "actions") {
                RaftSection(title = stringResource(Res.string.settings_computers_section_actions)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Remote restart / upgrade: managed Computers only, and only online (an offline
                        // Computer's way back is the connection section's recovery commands).
                        if (snapshot.computer.isComputer && snapshot.computer.online) RaftComputerServiceCard(controls)
                        if (snapshot.computer.isComputer) RaftComputerRecoveryGuideCard(controls)
                        if (canRemove) {
                            val deleting =
                                actionState?.let { it.action == COMPUTER_ACTION_DELETE && it.itemId == machineId && it.inProgress } == true
                            RaftCard(
                                title = stringResource(Res.string.settings_computers_delete_title),
                                description = stringResource(Res.string.settings_computers_delete_detail),
                            ) {
                                RaftButton(
                                    text = stringResource(Res.string.settings_computers_delete_action),
                                    onClick = { confirmDelete = true },
                                    variant = RaftButtonVariant.Danger,
                                    loading = deleting,
                                    loadingLabel = stringResource(Res.string.settings_computers_delete_busy),
                                    leadingIcon = { RaftComputersIcon(RaftLucideIconName.Trash2) },
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(bottomContentPadding)) }
    }
    if (transferOwnerOpen) {
        val ownerUserId = snapshot.computer.ownerUserId
        RaftTransferComputerOwnerDialog(
            computerName = computer.name,
            ownerUserId = ownerUserId,
            candidates = remember(ownerMembers, ownerUserId, snapshot.currentUserId) {
                raftComputerOwnerCandidates(ownerMembers, ownerUserId, snapshot.currentUserId)
            },
            saving = ownerSaving && !claimingOwner,
            failed = ownerFailed && transferSubmitted && !claimingOwner,
            onTransfer = { next ->
                transferSubmitted = true
                onIntent(SharedSettingsIntent.SetComputerOwner(SettingsComputerOwnerRequest(machineId, next)))
            },
            onDismiss = { transferOwnerOpen = false },
        )
    }
    if (confirmDelete) {
        RaftComputerDeleteDialog(
            name = computer.name,
            agentCount = model.assignedAgentCount,
            onConfirm = {
                confirmDelete = false
                onIntent(SharedSettingsIntent.DeleteComputer(machineId))
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Web `DeleteComputerDialog`, from the Computer page and a card's ⋯: a computer with agents on it
 * gets the "cannot delete" notice (the server would refuse it), otherwise the destructive confirm
 * naming it. Other people's agents on one's own computer (revoke the key, notify or force unbind)
 * are not here yet: the server's refusal shows as the page's error, with its trace ID.
 */
@Composable
internal fun RaftComputerDeleteDialog(name: String, agentCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (agentCount > 0) {
        RaftDialog(
            onDismissRequest = onDismiss,
            title = stringResource(Res.string.settings_computers_error_title_cannot_delete),
            description =
                if (agentCount == 1) {
                    stringResource(Res.string.settings_computers_cannot_delete_message_one, agentCount)
                } else {
                    stringResource(Res.string.settings_computers_cannot_delete_message_many, agentCount)
                },
            closeLabel = stringResource(Res.string.common_close),
            footer = {
                RaftButton(
                    text = stringResource(Res.string.common_ok),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                )
            },
        )
    } else {
        RaftDialog(
            onDismissRequest = onDismiss,
            title = stringResource(Res.string.settings_computers_delete_title),
            description = stringResource(Res.string.settings_computers_delete_confirm_named, name),
            dismissOnClickOutside = false,
            closeLabel = stringResource(Res.string.common_close),
            footer = {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
                RaftButton(
                    text = stringResource(Res.string.settings_computers_delete_action),
                    onClick = onConfirm,
                    variant = RaftButtonVariant.Danger,
                    size = RaftButtonSize.Lg,
                    modifier = Modifier.testTag(RaftComputersRackTags.DeleteConfirm),
                )
            },
        )
    }
}

@Composable
private fun RaftComputerRuntimeBadge(chip: RaftComputerRuntimeChip) {
    val suffix =
        when (chip.availability) {
            RaftComputerRuntimeAvailability.Detected -> ""
            RaftComputerRuntimeAvailability.NotInstalled -> stringResource(Res.string.agent_create_suffix_not_installed)
            RaftComputerRuntimeAvailability.UpdateComputer -> stringResource(Res.string.settings_computers_runtime_suffix_update_computer)
        }
    val experimental = if (chip.experimental) stringResource(Res.string.runtime_suffix_experimental) else ""
    RaftBadge(
        text = chip.name + experimental + suffix,
        // Long press copies the runtime name without the "(not installed)" style suffix.
        modifier = Modifier.raftCopyable(chip.name),
        appearance = RaftBadgeAppearance.Soft,
        variant =
            if (chip.availability == RaftComputerRuntimeAvailability.Detected) RaftBadgeVariant.Information else RaftBadgeVariant.Muted,
    )
}

/**
 * Web MachineDetailPanel creator link: the person's 19dp avatar, their name in semibold and
 * `@name` in mono hint ink, 6dp apart, as one frameless button that opens their profile. The
 * 6dp side padding is pulled back out so the avatar lines up with the other values.
 */
@Composable
internal fun RaftComputerCreatorLink(creator: RaftComputerCreatorModel, onClick: () -> Unit) {
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Row(
        modifier =
            Modifier
                .offset(x = (-6).dp)
                .clip(shape)
                .raftClickable(onClick = onClick, shape = shape, tier = RaftHoverTier.None)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftComputerCreatorAvatar(creator)
        RaftText(
            creator.displayName,
            modifier = Modifier.weight(1f, fill = false),
            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RaftText(
            stringResource(Res.string.settings_computers_creator_handle, creator.name),
            style = RaftTheme.typography.monoMeta,
            color = RaftTheme.colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A person's avatar: their uploaded photo, else (also when it fails) the placeholder person. */
@Composable
private fun RaftComputerCreatorAvatar(creator: RaftComputerCreatorModel) {
    RaftAppAvatarSticker(
        kind = RaftAvatarKind.Human,
        avatarWireValue = creator.avatarWireValue,
        label = "",
        size = RaftAvatarSize.Xs,
        cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Human, creator.id),
    )
}

/** A created timestamp as a date in the viewer's zone, or null when there is none. */
@Composable
private fun raftComputerCreatedDate(createdAt: String?): String? {
    val value = createdAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val context =
        currentRaftTimeContextProvider?.current
            ?: RaftTimeContext(localOffsetMinutes = ProcessRaftDatetimeFoundationOwner.localOffsetMinutesAt(defaultNowMillis(), 0))
    return value.membersLocalizedCreatedDateOrNull(context)
}

@Composable
private fun RaftComputerDetailHeader(computer: RaftComputerRowModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftComputerTile(computer, size = RaftIconTileSize.Lg)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RaftText(
                computer.name,
                style = RaftTheme.typography.headline,
                color = RaftTheme.colors.foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.raftCopyable(computer.name),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftComputerStatus(computer.online)
                if (computer.updateAvailable) RaftComputerUpdateBadge()
            }
            val meta = listOfNotNull(raftComputerVersionLine(computer), computer.hostDetail).joinToString(" · ")
            if (meta.isNotBlank()) {
                RaftText(meta, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
            }
            computer.lastSeenAt?.let {
                RaftText(raftComputerLastSeen(it), style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundHint)
            }
        }
    }
}

/**
 * A Name / Description section as web MachineDetailPanel draws it: the value as text with a small
 * pencil after the title (only when [canEdit]); the pencil opens the editor ([RaftComputerEditableText]),
 * which goes back to text on Cancel or once its save succeeds. An empty value shows [emptyText] in hint.
 */
@Composable
private fun RaftComputerEditableSection(
    key: String,
    title: String,
    editLabel: String,
    canEdit: Boolean,
    value: String,
    emptyText: String?,
    label: String,
    placeholder: String,
    multiline: Boolean,
    allowBlank: Boolean,
    saveState: SharedSettingsActionState?,
    maxLength: Int? = null,
    onSave: (String) -> Unit,
) {
    val saving = saveState?.inProgress == true
    val failed = saveState?.let { !it.inProgress && it.hasComputerActionError() } == true
    var editing by remember(key) { mutableStateOf(false) }
    var wasSaving by remember(key) { mutableStateOf(saving) }
    LaunchedEffect(saving, failed) {
        if (raftComputerEditorClosesAfterSave(wasSaving = wasSaving, saving = saving, failed = failed)) editing = false
        wasSaving = saving
    }
    val showEditor = canEdit && editing
    RaftBackHandler(enabled = showEditor && !saving, label = "settings_computer_edit_field") { editing = false }
    RaftSection(
        title = title,
        titleAction =
            if (canEdit && !editing) {
                {
                    RaftIconButton(
                        onClick = { editing = true },
                        tooltip = editLabel,
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Md,
                    ) {
                        RaftComputersIcon(RaftLucideIconName.Pencil, size = 14.dp)
                    }
                }
            } else {
                null
            },
    ) {
        if (showEditor) {
            RaftComputerEditableText(
                key = key,
                initial = value,
                label = label,
                placeholder = placeholder,
                multiline = multiline,
                allowBlank = allowBlank,
                saving = saving,
                maxLength = maxLength,
                onSave = onSave,
                onCancel = { editing = false },
            )
        } else {
            val shown = value.takeIf { it.isNotBlank() }
            RaftText(
                shown ?: emptyText.orEmpty(),
                style = RaftTheme.typography.callout,
                color = if (shown == null) RaftTheme.colors.foregroundHint else RaftTheme.colors.foreground,
                // Long press copies the saved value (the name, the description), not the empty hint.
                modifier = Modifier.raftCopyable(shown),
            )
        }
    }
}

@Composable
private fun RaftComputerEditableText(
    key: String,
    initial: String,
    label: String,
    placeholder: String,
    multiline: Boolean,
    allowBlank: Boolean,
    saving: Boolean,
    maxLength: Int? = null,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember(key) { mutableStateOf(initial) }
    // The saved value the field last adopted. A background reload (realtime, refresh) moves
    // [initial]; the field follows it only while the user has no unsaved edits.
    var baseline by remember(key) { mutableStateOf(initial) }
    LaunchedEffect(key, initial) {
        if (initial != baseline) {
            val next = raftComputerEditableTextAfterReload(current = value, baseline = baseline, incoming = initial)
            if (next != value) value = next
            baseline = initial
        }
    }
    val trimmed = value.trim()
    val changed = trimmed != initial.trim()
    val tooLong = maxLength != null && trimmed.length > maxLength
    val onValueChange: (String) -> Unit = { next -> value = if (maxLength != null) next.take(maxLength) else next }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftField(label = label) {
            if (multiline) {
                RaftTextarea(value = value, onValueChange = onValueChange, placeholder = placeholder, enabled = !saving)
            } else {
                RaftTextField(value = value, onValueChange = onValueChange, placeholder = placeholder, enabled = !saving)
            }
        }
        // Web: Save (accent) and Cancel, with the description's `n/500` counter at the end.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftButton(
                text = stringResource(Res.string.common_save),
                // An unchanged value has nothing to save: Save just closes the editor.
                onClick = { if (changed) onSave(trimmed) else onCancel() },
                variant = RaftButtonVariant.Primary,
                enabled = (allowBlank || trimmed.isNotBlank()) && !tooLong,
                loading = saving,
                loadingLabel = stringResource(Res.string.settings_computers_action_saving),
            )
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onCancel,
                enabled = !saving,
            )
            if (maxLength != null) {
                Spacer(Modifier.weight(1f))
                RaftText(
                    "${value.length}/$maxLength",
                    style = RaftTheme.typography.monoMeta,
                    color = if (tooLong) RaftTheme.colors.dangerStrong else RaftTheme.colors.foregroundHint,
                )
            }
        }
    }
}

@Composable
private fun RaftComputerAgentsSection(
    snapshot: SettingsComputerDetailSnapshot,
    agentStatusResolver: RaftAgentStatusResolver,
    onOpenAgent: (SettingsComputerAgentSnapshot) -> Unit,
) {
    val agents = snapshot.agents
    RaftSection(
        title = stringResource(Res.string.settings_computers_section_agents),
        count = agents.size.takeIf { it > 0 }?.toString(),
    ) {
        val error = snapshot.agentsError
        when {
            error != null ->
                RaftComputersInlineNotice(
                    title = stringResource(Res.string.settings_computers_error_load_agents),
                    detail = raftComputerErrorCodeMessage(error.computerErrorCode, error.screenError, error.permissionBlocker),
                )
            agents.isEmpty() ->
                RaftComputersInlineNotice(
                    title = stringResource(Res.string.settings_computers_empty_agents),
                    detail = stringResource(Res.string.settings_computers_empty_agents_detail),
                )
            else ->
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    agents.forEachIndexed { index, agent ->
                        RaftComputerAgentRow(
                            agent = agent,
                            divider = index > 0,
                            agentStatusResolver = agentStatusResolver,
                            onClick = { onOpenAgent(agent) },
                        )
                    }
                }
        }
    }
}

/**
 * An agent on this computer, as web `MachineAgentRow` draws it: avatar, name, one
 * "runtime · model" line, and the activity dot with its short text on the right. The row opens
 * the agent; there is no description line and no chevron.
 */
@Composable
private fun RaftComputerAgentRow(
    agent: SettingsComputerAgentSnapshot,
    divider: Boolean,
    agentStatusResolver: RaftAgentStatusResolver,
    onClick: () -> Unit,
) {
    val live = raftComputerAgentStatusIndicator(agent, rememberRaftAgentStatusIndicatorState(agentStatusResolver, agent.id))
    RaftListRow(
        title = agent.name,
        onClick = onClick,
        divider = divider,
        subtitle = raftComputerAgentIdentityLabel(agent.runtime, agent.model),
        leading = { RaftComputerAgentAvatar(agent) },
        trailing = if (live != null) { { RaftComputerAgentStatus(live) } } else null,
    )
}

/**
 * SSOT audit: the row's status is the live activity from the members store only, like every
 * Home surface ([projectHomeAgentStatusIndicatorState]). Until that store has observed the
 * agent the row is neutral (no dot, no text): the agents-list `status` is the lifecycle column
 * (active / inactive), not liveness, and must not paint Online / Offline.
 */
internal fun raftComputerAgentStatusIndicator(
    agent: SettingsComputerAgentSnapshot,
    live: RaftAgentStatusIndicatorState?,
): RaftAgentStatusIndicatorState? =
    projectHomeAgentStatusIndicatorState(showPresence = true, memberId = agent.id, canonicalState = live)

/** The live activity (web `StatusDot activity` + `formatActivityText`). */
@Composable
private fun RaftComputerAgentStatus(live: RaftAgentStatusIndicatorState) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftStatusIndicator(color = live.color, size = 10.dp, pulsing = live.pulsing)
        val text = live.localizedMemberStatusLabel()
        RaftText(
            text,
            modifier = Modifier.widthIn(max = 120.dp),
            style = RaftTheme.typography.monoMeta,
            color = RaftTheme.colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An agent on this computer: its own avatar (pixel avatar or photo) in the agent sticker,
 * as the Members list draws it; an agent with none, or whose image fails, wears the default
 * pixel robot, as web `AgentAvatar` does ([RaftAvatarKind.Agent]).
 */
@Composable
private fun RaftComputerAgentAvatar(agent: SettingsComputerAgentSnapshot) {
    RaftAppAvatarSticker(
        kind = RaftAvatarKind.Agent,
        avatarWireValue = agent.raftComputerAgentAvatarWireValue(),
        label = agent.name,
        size = RaftAvatarSize.Md,
        cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, agent.id),
    )
}

@Composable
private fun RaftComputerWorkspacesSection(
    snapshot: SettingsComputerDetailSnapshot,
    scanning: Boolean,
    onScan: () -> Unit,
) {
    RaftSection(
        title = stringResource(Res.string.settings_computers_section_workspaces),
        count = snapshot.workspaces.size.takeIf { snapshot.workspacesScanned && it > 0 }?.toString(),
        // Web `SectionHeader action`: a small outline Scan / Rescan button at the end of the heading.
        action = {
            RaftButton(
                text =
                    if (snapshot.workspacesScanned) {
                        stringResource(Res.string.settings_computers_action_rescan)
                    } else {
                        stringResource(Res.string.settings_computers_action_scan)
                    },
                onClick = onScan,
                loading = scanning,
                loadingLabel = stringResource(Res.string.settings_computers_action_scanning),
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.RefreshCw) },
            )
        },
    ) {
        val error = snapshot.workspacesError
        when {
            !snapshot.workspacesScanned ->
                // Web shows the scan prompt only while no scan is running.
                if (!scanning) {
                    RaftComputersInlineNotice(
                        title = stringResource(Res.string.settings_computers_workspace_scan_title),
                        detail = stringResource(Res.string.settings_computers_workspace_scan_detail),
                    )
                } else {
                    Unit
                }
            error != null ->
                RaftComputersInlineNotice(
                    title = stringResource(Res.string.settings_computers_error_load_workspaces),
                    detail = raftComputerErrorCodeMessage(error.computerErrorCode, error.screenError, error.permissionBlocker),
                )
            snapshot.workspaces.isEmpty() ->
                RaftComputersInlineNotice(
                    title = stringResource(Res.string.settings_computers_empty_workspaces),
                    detail = stringResource(Res.string.settings_computers_empty_workspaces_detail),
                )
            else ->
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    snapshot.workspaces.forEachIndexed { index, workspace ->
                        RaftComputerWorkspaceRow(workspace, divider = index > 0)
                    }
                }
        }
    }
}

@Composable
private fun RaftComputerWorkspaceRow(workspace: SettingsComputerWorkspaceSnapshot, divider: Boolean) {
    val files =
        workspace.fileCount?.let { count ->
            if (count == 1) {
                stringResource(Res.string.settings_computers_file_count_one, count)
            } else {
                stringResource(Res.string.settings_computers_file_count_many, count)
            }
        }
    val modified =
        workspace.lastActiveLabel
            ?.let { raftRelativeTimestampLabel(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(Res.string.settings_computers_workspace_modified, it) }
    RaftListRow(
        title = workspace.name,
        divider = divider,
        subtitle = workspace.path,
        description = listOfNotNull(raftComputerWorkspaceSize(workspace.totalSizeBytes), files, modified).joinToString(" · ").ifBlank { null },
        leading = {
            RaftIconTile { RaftComputersIcon(RaftLucideIconName.Folder, size = RaftIconTileSize.Md.icon) }
        },
        trailing = {
            when (workspace.statusLabel) {
                "active" ->
                    RaftBadge(
                        stringResource(Res.string.settings_computers_workspace_status_active),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Success,
                    )
                "orphan" ->
                    RaftBadge(
                        stringResource(Res.string.settings_computers_workspace_status_orphan),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Warning,
                    )
                else -> Unit
            }
        },
    )
}

// endregion

// region Shared parts

/** Hosts the raft-ui design system on the app's resolved light/dark appearance. */
@Composable
internal fun RaftComputersTheme(content: @Composable () -> Unit) {
    RaftTheme(appearance = if (RaftAppThemeState.isDark) RaftAppearance.Dark else RaftAppearance.Light, raftTheme = RaftAppThemeState.raftTheme, content = content)
}

/** A Lucide icon in the enclosing design-system slot's ink. */
@Composable
internal fun RaftComputersIcon(name: RaftLucideIconName, size: Dp = 16.dp) {
    val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(name, modifier = Modifier.size(size), color = raftArgbHex(ink))
}

@Composable
private fun RaftComputerTile(row: RaftComputerRowModel, size: RaftIconTileSize = RaftIconTileSize.Md) {
    RaftIconTile(size = size) {
        RaftComputersIcon(
            if (row.kind == RaftComputerKind.Computer) RaftLucideIconName.Monitor else RaftLucideIconName.Server,
            size = size.icon,
        )
    }
}

@Composable
private fun RaftComputerStatus(online: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftStatusDot(presence = if (online) RaftPresence.Online else RaftPresence.Offline)
        RaftListRowValue(
            if (online) {
                stringResource(Res.string.settings_computers_status_connected)
            } else {
                stringResource(Res.string.settings_computers_status_offline)
            },
            maxWidth = 120.dp,
        )
    }
}

@Composable
private fun RaftComputerUpdateBadge() {
    RaftBadge(
        stringResource(Res.string.settings_computers_update_available),
        appearance = RaftBadgeAppearance.Soft,
        variant = RaftBadgeVariant.Warning,
    )
}

/** A refresh that failed while the page still shows its last content. */
@Composable
private fun RaftComputersRefreshErrorBanner(code: String) {
    RaftBanner(
        description = raftComputerErrorCodeMessage(code, null, null),
        status = RaftBannerStatus.Warning,
        title = stringResource(Res.string.settings_computers_error_refresh_title),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The centred loading / empty / error block of a page with nothing else to show. */
@Composable
private fun RaftComputersStateBlock(
    title: String,
    detail: String? = null,
    iconName: RaftLucideIconName? = null,
    loading: Boolean = false,
    actionLabel: String? = null,
    actionIcon: RaftLucideIconName? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            RaftSpinner(size = RaftSpinnerSize.Md, contentDescription = title)
        } else if (iconName != null) {
            RaftIconTile(size = RaftIconTileSize.Lg) { RaftComputersIcon(iconName, size = RaftIconTileSize.Lg.icon) }
        }
        RaftText(
            title,
            style = RaftTheme.typography.headline,
            color = RaftTheme.colors.foreground,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            RaftText(
                detail,
                style = RaftTheme.typography.callout,
                color = RaftTheme.colors.foregroundMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null) {
            RaftButton(
                text = actionLabel,
                onClick = onAction,
                variant = if (actionIcon != null) RaftButtonVariant.Primary else RaftButtonVariant.Default,
                leadingIcon =
                    if (actionIcon != null) {
                        { RaftComputersIcon(actionIcon) }
                    } else {
                        null
                    },
            )
        }
    }
}

/** An empty or failed section of the detail page: a title line and its explanation. */
@Composable
private fun RaftComputersInlineNotice(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(title, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
        RaftText(detail, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundHint)
    }
}

@Composable
private fun raftComputerVersionLine(row: RaftComputerRowModel): String? = raftComputerKindVersion(row.kind, row.version)

/** "computer v1.2.0" / "daemon v0.64.1", or null without a version. */
@Composable
private fun raftComputerKindVersion(kindOf: RaftComputerKind, version: String?): String? {
    val kind =
        if (kindOf == RaftComputerKind.Computer) {
            stringResource(Res.string.settings_computers_kind_computer)
        } else {
            stringResource(Res.string.settings_computers_kind_daemon)
        }
    return version?.let { stringResource(Res.string.settings_computers_run_version, kind, it.removePrefix("v")) }
}

@Composable
private fun raftComputerLastSeen(timestamp: String): String =
    stringResource(Res.string.settings_computers_last_seen, raftRelativeTimestampLabel(timestamp))

@Composable
private fun raftComputerFailureMessage(failure: RaftComputersFailure): String =
    raftComputerErrorCodeMessage(failure.code, failure.screenError, failure.permissionBlocker)

@Composable
private fun raftComputerErrorCodeMessage(
    code: String?,
    screenError: build.raft.app.data.ScreenError?,
    permissionBlocker: build.raft.app.data.PermissionBlocker?,
): String =
    when (raftComputerErrorCopy(code)) {
        RaftComputerErrorCopy.Offline -> stringResource(Res.string.settings_computers_error_offline)
        RaftComputerErrorCopy.Replica -> stringResource(Res.string.settings_computers_error_replica)
        RaftComputerErrorCopy.HasAgents -> stringResource(Res.string.settings_computers_error_has_agents)
        RaftComputerErrorCopy.BadRequest -> stringResource(Res.string.settings_computers_error_bad_request)
        RaftComputerErrorCopy.Forbidden -> stringResource(Res.string.settings_computers_error_forbidden)
        RaftComputerErrorCopy.SessionExpired -> stringResource(Res.string.settings_computers_error_auth)
        RaftComputerErrorCopy.ServerUnavailable -> stringResource(Res.string.settings_computers_error_server_unavailable)
        RaftComputerErrorCopy.Generic -> {
            val strings = localizedErrorPresentationStrings()
            screenError?.userFacingMessage(strings)
                ?: permissionBlocker?.userFacingMessage(strings)
                ?: code?.let { stringResource(Res.string.settings_computers_error_code, it) }
                ?: stringResource(Res.string.settings_computers_error_unavailable_detail)
        }
    }

/** A failed computer action (web: the dialog's error with `TraceIdNote`): what went wrong, then its trace ID. */
@Composable
private fun RaftComputerActionErrorBanner(failed: SharedSettingsActionState) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(RaftComputersRackTags.ActionError), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(
            description = raftComputerActionErrorMessage(failed),
            status = RaftBannerStatus.Warning,
            title = raftComputerActionErrorTitle(failed),
        )
        RaftTraceIdNote(failed.screenError?.traceId ?: failed.permissionBlocker?.traceId)
    }
}

@Composable
private fun raftComputerActionErrorMessage(action: SharedSettingsActionState): String =
    raftComputerErrorCodeMessage(action.computerErrorCode, action.screenError, action.permissionBlocker)

@Composable
private fun raftComputerActionErrorTitle(action: SharedSettingsActionState): String =
    when (raftComputerErrorCopy(action.computerErrorCode)) {
        RaftComputerErrorCopy.HasAgents -> stringResource(Res.string.settings_computers_error_title_cannot_delete)
        RaftComputerErrorCopy.BadRequest -> stringResource(Res.string.settings_computers_error_title_request_rejected)
        RaftComputerErrorCopy.Offline, RaftComputerErrorCopy.Replica -> stringResource(Res.string.settings_computers_error_title_offline)
        RaftComputerErrorCopy.Forbidden -> stringResource(Res.string.settings_computers_error_title_permission)
        RaftComputerErrorCopy.SessionExpired -> stringResource(Res.string.settings_computers_error_title_session)
        RaftComputerErrorCopy.ServerUnavailable, RaftComputerErrorCopy.Generic ->
            stringResource(Res.string.settings_computers_error_title_action)
    }

// endregion

/** Draws a rack usage line's usage card round [trigger] (the page knows the viewer and server). */
internal class RaftRackUsageCard(
    val card: @Composable (machineId: String, column: RaftRackUsageColumn, label: String, trigger: @Composable () -> Unit) -> Unit,
)

internal val LocalRaftRackUsageCard = staticCompositionLocalOf<RaftRackUsageCard?> { null }

/**
 * A Computers card's usage line with its usage card (web rack `UsageCardTrigger`): it opens on
 * hover and pins on a click. The controller reads only when the card opens (the page already
 * read every line for the meters), so a page of Computers asks for nothing more at rest.
 */
@Composable
private fun RaftRackUsagePreviewCard(
    label: String,
    key: RuntimeAccountUsageKey,
    access: MembersRuntimeAccountUsageAccess,
    runtimeVersion: String?,
    trigger: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller =
        remember(key, access.store) {
            ComputerRuntimeUsageController(key = key, store = access.store, scope = scope, log = access.log, onIdentityChanged = access.onIdentityChanged)
        }
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    ComputerRuntimeUsagePreviewCard(runtimeLabel = label, controller = controller, runtimeVersion = runtimeVersion, trigger = trigger)
}
