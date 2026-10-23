package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import build.raft.app.data.COMPUTER_ACTION_SCAN_WORKSPACES
import build.raft.app.data.RaftAuthSessionEventSource
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftSharedStoreRegistry
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
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowValue
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.raftAvatarInitials
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.launch

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

internal data class RaftComputerPageTarget(val machineId: String, val serverId: String?)

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
    fun refresh() = settingsViewModel.dispatch(SharedSettingsIntent.RefreshComputers)
    SettingsDetailShell(
        title = stringResource(MR.strings.settings_computers_title),
        subtitle = "",
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
    ) {
        // Mobile refreshes by pull only (no header refresh button): same store refresh as Retry.
        RaftStorePullToRefreshBox(refreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        RaftComputersTheme {
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
            )
        }
        }
    }
    settingsState.addComputerDialog?.let { dialog ->
        AddComputerDialog(state = dialog, onIntent = settingsViewModel::dispatch)
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
) {
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        when (model) {
            RaftComputersListModel.Loading ->
                RaftComputersStateBlock(
                    title = stringResource(MR.strings.settings_computers_loading),
                    detail = stringResource(MR.strings.settings_computers_loading_detail),
                    loading = true,
                )
            is RaftComputersListModel.Failed ->
                if (model.failure.noAccess) {
                    RaftComputersStateBlock(
                        title = stringResource(MR.strings.settings_computers_no_access),
                        detail = stringResource(MR.strings.settings_computers_no_access_detail),
                        iconName = RaftLucideIconName.Lock,
                    )
                } else {
                    RaftComputersStateBlock(
                        title = stringResource(MR.strings.settings_computers_error_load),
                        detail = raftComputerFailureMessage(model.failure),
                        iconName = RaftLucideIconName.TriangleAlert,
                        actionLabel = stringResource(MR.strings.common_retry).takeIf { model.failure.retryable },
                        onAction = onRetry,
                    )
                }
            is RaftComputersListModel.Empty ->
                RaftComputersStateBlock(
                    title = stringResource(MR.strings.settings_computers_empty),
                    detail = stringResource(MR.strings.settings_computers_empty_detail),
                    iconName = RaftLucideIconName.Monitor,
                    actionLabel = stringResource(MR.strings.settings_computers_add_computer).takeIf { model.canAddComputer },
                    actionIcon = RaftLucideIconName.Plus,
                    onAction = onAddComputer,
                )
            is RaftComputersListModel.Content ->
                LazyColumn(Modifier.fillMaxSize()) {
                    refreshErrorCode?.let { code -> item(key = "refresh-error") { RaftComputersRefreshErrorBanner(code) } }
                    item(key = "summary") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RaftText(
                                stringResource(MR.strings.settings_computers_online_count, model.onlineCount, model.rows.size),
                                modifier = Modifier.weight(1f),
                                style = RaftTheme.typography.footnote,
                                color = RaftTheme.colors.foregroundHint,
                            )
                            if (model.canAddComputer) {
                                RaftButton(
                                    text = stringResource(MR.strings.settings_computers_add_computer),
                                    onClick = onAddComputer,
                                    variant = RaftButtonVariant.Primary,
                                    leadingIcon = { RaftComputersIcon(RaftLucideIconName.Plus) },
                                )
                            }
                        }
                    }
                    item(key = "computers") {
                        RaftListRowGroup(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            model.rows.forEachIndexed { index, row ->
                                RaftComputerListRow(row = row, divider = index > 0, onClick = { onOpenComputer(row.id) })
                            }
                        }
                    }
                    item(key = "bottom") { Spacer(Modifier.height(bottomContentPadding)) }
                }
        }
    }
}

@Composable
private fun RaftComputerListRow(row: RaftComputerRowModel, divider: Boolean, onClick: () -> Unit) {
    RaftListRow(
        title = row.name,
        onClick = onClick,
        divider = divider,
        subtitle = raftComputerVersionLine(row),
        description = row.hostDetail ?: row.description ?: row.lastSeenAt?.let { raftComputerLastSeen(it) },
        leading = { RaftComputerTile(row) },
        badge =
            if (row.updateAvailable) {
                { RaftComputerUpdateBadge() }
            } else {
                null
            },
        trailing = {
            RaftComputerStatus(row.online)
            RaftListRowChevron()
        },
    )
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
    val agentStatusResolver =
        remember(environment.serverMembersStore, currentServerId) {
            raftScopedAgentStatusResolver(environment.serverMembersStore, currentServerId)
        }
    SettingsDetailShell(
        title = (model as? RaftComputerDetailModel.Content)?.computer?.name
            ?: stringResource(MR.strings.settings_computers_detail_computer),
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
) {
    Box(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        when (model) {
            RaftComputerDetailModel.Loading ->
                RaftComputersStateBlock(title = stringResource(MR.strings.settings_computers_detail_loading), loading = true)
            RaftComputerDetailModel.Unavailable ->
                RaftComputersStateBlock(
                    title = stringResource(MR.strings.settings_computers_detail_unavailable),
                    iconName = RaftLucideIconName.Monitor,
                )
            is RaftComputerDetailModel.Failed ->
                if (model.failure.noAccess) {
                    RaftComputersStateBlock(
                        title = stringResource(MR.strings.settings_computers_detail_no_access),
                        detail = raftComputerFailureMessage(model.failure),
                        iconName = RaftLucideIconName.Lock,
                    )
                } else {
                    RaftComputersStateBlock(
                        title = stringResource(MR.strings.settings_computers_detail_error_load),
                        detail = raftComputerFailureMessage(model.failure),
                        iconName = RaftLucideIconName.TriangleAlert,
                        actionLabel = stringResource(MR.strings.common_retry).takeIf { model.failure.retryable },
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
) {
    val computer = model.computer
    val snapshot = model.snapshot
    val machineId = computer.id
    val canEdit = model.canEdit
    val canRemove = model.canRemove
    val currentAction = actionState?.takeIf { it.itemId == machineId }
    var confirmDelete by remember(machineId) { mutableStateOf(false) }
    val nowMillis = LocalRaftTimeContextProvider.current?.current?.nowEpochMillis ?: defaultNowMillis()
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
            ?.takeIf { !it.inProgress && it.hasComputerActionError() }
            ?.let { failed ->
                item(key = "action-error") {
                    RaftBanner(
                        description = raftComputerActionErrorMessage(failed),
                        status = RaftBannerStatus.Warning,
                        title = raftComputerActionErrorTitle(failed),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        // Web MachineDetailPanel order: Name, Description, Info, Agents, Workspaces, Actions.
        item(key = "name") {
            RaftComputerEditableSection(
                key = machineId,
                title = stringResource(MR.strings.settings_computers_section_name),
                editLabel = stringResource(MR.strings.settings_computers_edit_name),
                canEdit = canEdit,
                value = computer.name,
                emptyText = null,
                label = stringResource(MR.strings.settings_computers_label_name),
                placeholder = stringResource(MR.strings.settings_computers_placeholder_name),
                multiline = false,
                allowBlank = false,
                saveState = currentAction?.takeIf { it.action == COMPUTER_ACTION_RENAME },
                onSave = { onIntent(SharedSettingsIntent.RenameComputer(SettingsComputerRenameRequest(machineId, it))) },
            )
        }
        item(key = "description") {
            RaftComputerEditableSection(
                key = machineId,
                title = stringResource(MR.strings.settings_computers_section_description),
                editLabel = stringResource(MR.strings.settings_computers_edit_description),
                canEdit = canEdit,
                value = computer.description.orEmpty(),
                emptyText = stringResource(MR.strings.settings_computers_state_no_description),
                label = stringResource(MR.strings.settings_computers_label_description),
                placeholder = stringResource(MR.strings.settings_computers_placeholder_description),
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
            RaftSection(title = stringResource(MR.strings.settings_computers_section_info)) {
                RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                    Item(stringResource(MR.strings.settings_computers_label_os), snapshot.computer.platform?.takeIf { it.isNotBlank() })
                    Item(stringResource(MR.strings.settings_computers_label_version), raftComputerVersionLine(computer), mono = true)
                    // Web: the runtimes as chips, cyan-soft when detected, muted with why not otherwise.
                    Item(stringResource(MR.strings.settings_computers_label_runtimes)) {
                        // Usage runtimes open their account usage (web RuntimeAccountUsageGateChip).
                        ComputerRuntimeUsageChips(raftComputerRuntimeChips(computer.runtimes), machineId, enabled = canEdit) { chip ->
                            RaftComputerRuntimeBadge(chip)
                        }
                    }
                    // Web: the date it was created ("Oct 22, 2026"), as on an agent's page.
                    Item(stringResource(MR.strings.settings_computers_label_created), raftComputerCreatedDate(model.createdAt), mono = true)
                    // Web: managed Computers name who attached them beside Created; raw daemons don't.
                    if (model.showsCreator) {
                        Item(stringResource(MR.strings.settings_computers_label_creator)) {
                            val creator = model.creator
                            if (creator != null) {
                                RaftComputerCreatorLink(creator, onClick = { onOpenCreator(creator) })
                            } else {
                                RaftText(
                                    stringResource(MR.strings.settings_computers_state_no_creator),
                                    style = RaftTheme.typography.callout,
                                    color = RaftTheme.colors.foregroundHint,
                                )
                            }
                        }
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
                RaftSection(title = stringResource(MR.strings.settings_computers_section_actions)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Remote restart / upgrade: managed Computers only, and only online (an offline
                        // Computer's way back is the connection section's recovery commands).
                        if (snapshot.computer.isComputer && snapshot.computer.online) RaftComputerServiceCard(controls)
                        if (snapshot.computer.isComputer) RaftComputerRecoveryGuideCard(controls)
                        if (canRemove) {
                            val deleting =
                                actionState?.let { it.action == COMPUTER_ACTION_DELETE && it.itemId == machineId && it.inProgress } == true
                            RaftCard(
                                title = stringResource(MR.strings.settings_computers_delete_title),
                                description = stringResource(MR.strings.settings_computers_delete_detail),
                            ) {
                                RaftButton(
                                    text = stringResource(MR.strings.settings_computers_delete_action),
                                    onClick = { confirmDelete = true },
                                    variant = RaftButtonVariant.Danger,
                                    loading = deleting,
                                    loadingLabel = stringResource(MR.strings.settings_computers_delete_busy),
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
    if (confirmDelete && model.assignedAgentCount > 0) {
        // Web MachineDetailPanel: a machine with agents gets a notice, not a destructive confirm.
        val count = model.assignedAgentCount
        RaftDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(MR.strings.settings_computers_error_title_cannot_delete),
            description =
                if (count == 1) {
                    stringResource(MR.strings.settings_computers_cannot_delete_message_one, count)
                } else {
                    stringResource(MR.strings.settings_computers_cannot_delete_message_many, count)
                },
            closeLabel = stringResource(MR.strings.common_close),
            footer = {
                RaftButton(
                    text = stringResource(MR.strings.common_ok),
                    onClick = { confirmDelete = false },
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Lg,
                )
            },
        )
    } else if (confirmDelete) {
        RaftDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(MR.strings.settings_computers_delete_title),
            description = stringResource(MR.strings.settings_computers_delete_detail),
            closeLabel = stringResource(MR.strings.common_close),
            footer = {
                RaftButton(
                    text = stringResource(MR.strings.common_cancel),
                    onClick = { confirmDelete = false },
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
                RaftButton(
                    text = stringResource(MR.strings.settings_computers_delete_action),
                    onClick = {
                        confirmDelete = false
                        onIntent(SharedSettingsIntent.DeleteComputer(machineId))
                    },
                    variant = RaftButtonVariant.Danger,
                    size = RaftButtonSize.Lg,
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
            RaftComputerRuntimeAvailability.NotInstalled -> stringResource(MR.strings.agent_create_suffix_not_installed)
            RaftComputerRuntimeAvailability.UpdateComputer -> stringResource(MR.strings.settings_computers_runtime_suffix_update_computer)
        }
    RaftBadge(
        text = chip.name + suffix,
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
private fun RaftComputerCreatorLink(creator: RaftComputerCreatorModel, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Row(
        modifier =
            Modifier
                .offset(x = (-6).dp)
                .clip(shape)
                .background(if (pressed) RaftTheme.colors.fillMedium else Color.Transparent, shape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
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
            stringResource(MR.strings.settings_computers_creator_handle, creator.name),
            style = RaftTheme.typography.monoMeta,
            color = RaftTheme.colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A person's avatar: their photo or Gravatar, else the placeholder person (web GravatarAvatar). */
@Composable
private fun RaftComputerCreatorAvatar(creator: RaftComputerCreatorModel) {
    val size = RaftAvatarSize.Xs
    val avatarWireValue = creator.avatarWireValue
    RaftAvatar(
        type = RaftAvatarType.Human,
        size = size,
        image =
            if (avatarWireValue != null) {
                {
                    RaftAppAvatar(
                        avatarWireValue = avatarWireValue,
                        label = "",
                        // The face fills the inside of the sticker edge.
                        avatarSize = size.edge - RaftStroke.Control * 2,
                        borderWidth = 0.dp,
                        cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Human, creator.id),
                    )
                }
            } else {
                null
            },
    )
}

/** A created timestamp as a date in the viewer's zone, or null when there is none. */
@Composable
private fun raftComputerCreatedDate(createdAt: String?): String? {
    val value = createdAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val context =
        LocalRaftTimeContextProvider.current?.current
            ?: RaftTimeContext(localOffsetMinutes = ProcessRaftDatetimeFoundationOwner.localOffsetMinutesAt(defaultNowMillis(), 0))
    return value.membersAgentCreatedDate(context).takeIf { it.isNotBlank() }
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
                text = stringResource(MR.strings.common_save),
                // An unchanged value has nothing to save: Save just closes the editor.
                onClick = { if (changed) onSave(trimmed) else onCancel() },
                variant = RaftButtonVariant.Primary,
                enabled = (allowBlank || trimmed.isNotBlank()) && !tooLong,
                loading = saving,
                loadingLabel = stringResource(MR.strings.settings_computers_action_saving),
            )
            RaftButton(
                text = stringResource(MR.strings.common_cancel),
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
        title = stringResource(MR.strings.settings_computers_section_agents),
        count = agents.size.takeIf { it > 0 }?.toString(),
    ) {
        val error = snapshot.agentsError
        when {
            error != null ->
                RaftComputersInlineNotice(
                    title = stringResource(MR.strings.settings_computers_error_load_agents),
                    detail = raftComputerErrorCodeMessage(error.computerErrorCode, error.screenError, error.permissionBlocker),
                )
            agents.isEmpty() ->
                RaftComputersInlineNotice(
                    title = stringResource(MR.strings.settings_computers_empty_agents),
                    detail = stringResource(MR.strings.settings_computers_empty_agents_detail),
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
 * as the Members list draws it; the initials only while it has none (artin 2026-10-21).
 */
@Composable
private fun RaftComputerAgentAvatar(agent: SettingsComputerAgentSnapshot) {
    val size = RaftAvatarSize.Md
    val avatarUrl = agent.avatarUrl?.takeIf { it.isNotBlank() }
    RaftAvatar(
        type = RaftAvatarType.Agent,
        size = size,
        initials = raftAvatarInitials(agent.name, size),
        image =
            if (avatarUrl != null) {
                {
                    RaftAppAvatar(
                        avatarWireValue = avatarUrl,
                        label = agent.name,
                        // The face fills the inside of the 1.5dp sticker edge.
                        avatarSize = size.edge - RaftStroke.Control * 2,
                        borderWidth = 0.dp,
                        cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, agent.id),
                        fallbackFill = raftAvatarFallbackFill(MemberKind.Agent),
                    )
                }
            } else {
                null
            },
    )
}

@Composable
private fun RaftComputerWorkspacesSection(
    snapshot: SettingsComputerDetailSnapshot,
    scanning: Boolean,
    onScan: () -> Unit,
) {
    RaftSection(
        title = stringResource(MR.strings.settings_computers_section_workspaces),
        count = snapshot.workspaces.size.takeIf { snapshot.workspacesScanned && it > 0 }?.toString(),
        // Web `SectionHeader action`: a small outline Scan / Rescan button at the end of the heading.
        action = {
            RaftButton(
                text =
                    if (snapshot.workspacesScanned) {
                        stringResource(MR.strings.settings_computers_action_rescan)
                    } else {
                        stringResource(MR.strings.settings_computers_action_scan)
                    },
                onClick = onScan,
                loading = scanning,
                loadingLabel = stringResource(MR.strings.settings_computers_action_scanning),
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
                        title = stringResource(MR.strings.settings_computers_workspace_scan_title),
                        detail = stringResource(MR.strings.settings_computers_workspace_scan_detail),
                    )
                } else {
                    Unit
                }
            error != null ->
                RaftComputersInlineNotice(
                    title = stringResource(MR.strings.settings_computers_error_load_workspaces),
                    detail = raftComputerErrorCodeMessage(error.computerErrorCode, error.screenError, error.permissionBlocker),
                )
            snapshot.workspaces.isEmpty() ->
                RaftComputersInlineNotice(
                    title = stringResource(MR.strings.settings_computers_empty_workspaces),
                    detail = stringResource(MR.strings.settings_computers_empty_workspaces_detail),
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
                stringResource(MR.strings.settings_computers_file_count_one, count)
            } else {
                stringResource(MR.strings.settings_computers_file_count_many, count)
            }
        }
    val modified =
        workspace.lastActiveLabel
            ?.let { raftRelativeTimestampLabel(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(MR.strings.settings_computers_workspace_modified, it) }
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
                        stringResource(MR.strings.settings_computers_workspace_status_active),
                        appearance = RaftBadgeAppearance.Soft,
                        variant = RaftBadgeVariant.Success,
                    )
                "orphan" ->
                    RaftBadge(
                        stringResource(MR.strings.settings_computers_workspace_status_orphan),
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
    RaftTheme(appearance = if (RaftAppThemeState.isDark) RaftAppearance.Dark else RaftAppearance.Light, content = content)
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
                stringResource(MR.strings.settings_computers_status_connected)
            } else {
                stringResource(MR.strings.settings_computers_status_offline)
            },
            maxWidth = 120.dp,
        )
    }
}

@Composable
private fun RaftComputerUpdateBadge() {
    RaftBadge(
        stringResource(MR.strings.settings_computers_update_available),
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
        title = stringResource(MR.strings.settings_computers_error_refresh_title),
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
private fun raftComputerVersionLine(row: RaftComputerRowModel): String? {
    val kind =
        if (row.kind == RaftComputerKind.Computer) {
            stringResource(MR.strings.settings_computers_kind_computer)
        } else {
            stringResource(MR.strings.settings_computers_kind_daemon)
        }
    return row.version?.let { stringResource(MR.strings.settings_computers_run_version, kind, it.removePrefix("v")) }
}

@Composable
private fun raftComputerLastSeen(timestamp: String): String =
    stringResource(MR.strings.settings_computers_last_seen, raftRelativeTimestampLabel(timestamp))

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
        RaftComputerErrorCopy.Offline -> stringResource(MR.strings.settings_computers_error_offline)
        RaftComputerErrorCopy.Replica -> stringResource(MR.strings.settings_computers_error_replica)
        RaftComputerErrorCopy.HasAgents -> stringResource(MR.strings.settings_computers_error_has_agents)
        RaftComputerErrorCopy.BadRequest -> stringResource(MR.strings.settings_computers_error_bad_request)
        RaftComputerErrorCopy.Forbidden -> stringResource(MR.strings.settings_computers_error_forbidden)
        RaftComputerErrorCopy.SessionExpired -> stringResource(MR.strings.settings_computers_error_auth)
        RaftComputerErrorCopy.ServerUnavailable -> stringResource(MR.strings.settings_computers_error_server_unavailable)
        RaftComputerErrorCopy.Generic -> {
            val strings = localizedErrorPresentationStrings()
            screenError?.userFacingMessage(strings)
                ?: permissionBlocker?.userFacingMessage(strings)
                ?: code?.let { stringResource(MR.strings.settings_computers_error_code, it) }
                ?: stringResource(MR.strings.settings_computers_error_unavailable_detail)
        }
    }

@Composable
private fun raftComputerActionErrorMessage(action: SharedSettingsActionState): String =
    raftComputerErrorCodeMessage(action.computerErrorCode, action.screenError, action.permissionBlocker)

@Composable
private fun raftComputerActionErrorTitle(action: SharedSettingsActionState): String =
    when (raftComputerErrorCopy(action.computerErrorCode)) {
        RaftComputerErrorCopy.HasAgents -> stringResource(MR.strings.settings_computers_error_title_cannot_delete)
        RaftComputerErrorCopy.BadRequest -> stringResource(MR.strings.settings_computers_error_title_request_rejected)
        RaftComputerErrorCopy.Offline, RaftComputerErrorCopy.Replica -> stringResource(MR.strings.settings_computers_error_title_offline)
        RaftComputerErrorCopy.Forbidden -> stringResource(MR.strings.settings_computers_error_title_permission)
        RaftComputerErrorCopy.SessionExpired -> stringResource(MR.strings.settings_computers_error_title_session)
        RaftComputerErrorCopy.ServerUnavailable, RaftComputerErrorCopy.Generic ->
            stringResource(MR.strings.settings_computers_error_title_action)
    }

// endregion
