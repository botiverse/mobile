package build.raft.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import build.raft.app.data.COMPUTER_ACTION_DESCRIPTION
import build.raft.app.data.COMPUTER_ACTION_RENAME
import build.raft.app.data.COMPUTER_ACTION_SCAN_WORKSPACES
import build.raft.app.data.SettingsComputerAgentSnapshot
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
import build.raft.app.model.MemberId
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
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftField
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
        setAuthenticatedContent { RaftSettingsComputersRoute() }
    }
}

@Composable
internal fun RaftSettingsComputersRoute() {
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
        RaftSettingsComputersScreen(pager)
    } else {
        RaftSettingsComputerDetailScreen(pager, target)
    }
}

internal data class RaftComputerPageTarget(val machineId: String, val serverId: String?)

private sealed interface RaftComputersSessionRead {
    data object Pending : RaftComputersSessionRead

    data class Read(val currentServerId: String?) : RaftComputersSessionRead
}

// region List

@Composable
private fun RaftSettingsComputersScreen(pager: Pager) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-computers") {
            SharedSettingsViewModel(environment.dataSource) { bridge.log("settings_computers $it") }
        }
    val settingsState by settingsViewModel.state.collectAsState()
    val navigationScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        bridge.log("settings_computers_open target=list")
        settingsViewModel.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Computers))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    LaunchedEffect(settingsState.effect) {
        // A finished Add Computer dialog lands on the new machine, as the web dialog does.
        val effect = settingsState.effect as? SharedSettingsEffect.OpenComputer ?: return@LaunchedEffect
        val serverId = environment.authStore.readSession()?.currentServerId
        settingsComputerRouteIdentity(serverId, effect.machineId)?.let { pager.openSettingsComputerPage(environment.storeScope, it) }
    }
    val model = raftComputersListModel(settingsState.computers)
    val refreshing = settingsState.isRefreshing(SharedSettingsDestination.Computers)
    fun refresh() = settingsViewModel.dispatch(SharedSettingsIntent.RefreshComputers)
    SettingsDetailShell(
        title = stringResource(MR.strings.settings_computers_title),
        subtitle = "",
        onBack = { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() },
        trailingContent = { RaftComputersRefreshAction(refreshing = refreshing, onRefresh = ::refresh) },
    ) {
        RaftComputersTheme {
            RaftSettingsComputersContent(
                model = model,
                bottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg,
                onRetry = ::refresh,
                onAddComputer = { settingsViewModel.dispatch(SharedSettingsIntent.ShowAddComputerDialog) },
                onOpenComputer = { machineId ->
                    navigationScope.launch {
                        val serverId = environment.authStore.readSession()?.currentServerId
                        val identity = settingsComputerRouteIdentity(serverId, machineId)
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
    settingsState.addComputerDialog?.let { dialog ->
        AddComputerDialog(state = dialog, onIntent = settingsViewModel::dispatch)
    }
}

@Composable
internal fun RaftSettingsComputersContent(
    model: RaftComputersListModel,
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
private fun RaftSettingsComputerDetailScreen(pager: Pager, target: RaftComputerPageTarget) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-computer-${target.machineId}") {
            SharedSettingsViewModel(environment.dataSource) { bridge.log("settings_computer_detail $it") }
        }
    val settingsState by settingsViewModel.state.collectAsState()
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
    // The session is read once per page: the target server is checked against its current server.
    val session by produceState<RaftComputersSessionRead>(RaftComputersSessionRead.Pending, environment.authStore) {
        value = RaftComputersSessionRead.Read(environment.authStore.readSession()?.currentServerId)
    }
    val model =
        when (val read = session) {
            RaftComputersSessionRead.Pending -> RaftComputerDetailModel.Loading
            is RaftComputersSessionRead.Read ->
                raftComputerDetailModel(
                    state = settingsState.computerDetail,
                    machineId = target.machineId,
                    targetServerId = target.serverId,
                    currentServerId = read.currentServerId,
                )
        }
    val currentServerId = (session as? RaftComputersSessionRead.Read)?.currentServerId
    val refreshing = settingsState.isRefreshing(SharedSettingsDestination.ComputerDetail)
    fun refresh() = settingsViewModel.dispatch(SharedSettingsIntent.Refresh)
    SettingsDetailShell(
        title = (model as? RaftComputerDetailModel.Content)?.computer?.name
            ?: stringResource(MR.strings.settings_computers_detail_computer),
        subtitle = "",
        onBack = ::closePage,
        trailingContent = { RaftComputersRefreshAction(refreshing = refreshing, onRefresh = ::refresh) },
    ) {
        RaftComputersTheme {
            RaftSettingsComputerDetailContent(
                model = model,
                actionState = settingsState.actionState,
                bottomContentPadding = raftBottomSafeAreaPadding() + RaftAppSpacing.Lg,
                onRetry = ::refresh,
                onIntent = settingsViewModel::dispatch,
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
            )
        }
    }
}

@Composable
internal fun RaftSettingsComputerDetailContent(
    model: RaftComputerDetailModel,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onRetry: () -> Unit,
    onIntent: (SharedSettingsIntent) -> Unit,
    onOpenAgent: (SettingsComputerAgentSnapshot) -> Unit,
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
                RaftComputerDetailBody(model, actionState, bottomContentPadding, onIntent, onOpenAgent)
        }
    }
}

@Composable
private fun RaftComputerDetailBody(
    model: RaftComputerDetailModel.Content,
    actionState: SharedSettingsActionState?,
    bottomContentPadding: Dp,
    onIntent: (SharedSettingsIntent) -> Unit,
    onOpenAgent: (SettingsComputerAgentSnapshot) -> Unit,
) {
    val computer = model.computer
    val snapshot = model.snapshot
    val machineId = computer.id
    val canManage = snapshot.canManageMachines
    val currentAction = actionState?.takeIf { it.itemId == machineId }
    var confirmDelete by remember(machineId) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") { RaftComputerDetailHeader(computer) }
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
        item(key = "info") {
            RaftSection(title = stringResource(MR.strings.settings_computers_section_info)) {
                RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                    Item(stringResource(MR.strings.settings_computers_label_os), snapshot.computer.platform?.takeIf { it.isNotBlank() })
                    Item(stringResource(MR.strings.settings_computers_label_version), raftComputerVersionLine(computer), mono = true)
                    Item(
                        stringResource(MR.strings.settings_computers_label_created),
                        model.createdAt?.let { raftRelativeTimestampLabel(it) }?.takeIf { it.isNotBlank() },
                    )
                    Item(
                        stringResource(MR.strings.settings_computers_label_runtimes),
                        computer.runtimes.joinToString(", ").takeIf { it.isNotBlank() },
                        mono = true,
                    )
                }
            }
        }
        item(key = "name") {
            RaftSection(title = stringResource(MR.strings.settings_computers_section_name)) {
                if (canManage) {
                    RaftComputerEditableText(
                        key = machineId,
                        initial = computer.name,
                        label = stringResource(MR.strings.settings_computers_label_name),
                        placeholder = stringResource(MR.strings.settings_computers_placeholder_name),
                        multiline = false,
                        allowBlank = false,
                        saving = actionState?.let { it.action == COMPUTER_ACTION_RENAME && it.itemId == machineId && it.inProgress } == true,
                        onSave = { onIntent(SharedSettingsIntent.RenameComputer(SettingsComputerRenameRequest(machineId, it))) },
                    )
                } else {
                    RaftText(computer.name, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
                }
            }
        }
        item(key = "description") {
            RaftSection(title = stringResource(MR.strings.settings_computers_section_description)) {
                if (canManage) {
                    RaftComputerEditableText(
                        key = machineId,
                        initial = computer.description.orEmpty(),
                        label = stringResource(MR.strings.settings_computers_label_description),
                        placeholder = stringResource(MR.strings.settings_computers_placeholder_description),
                        multiline = true,
                        allowBlank = true,
                        saving =
                            actionState?.let { it.action == COMPUTER_ACTION_DESCRIPTION && it.itemId == machineId && it.inProgress } == true,
                        onSave = {
                            onIntent(
                                SharedSettingsIntent.UpdateComputerDescription(SettingsComputerDescriptionUpdateRequest(machineId, it))
                            )
                        },
                    )
                } else {
                    RaftText(
                        computer.description ?: stringResource(MR.strings.settings_computers_state_no_description),
                        style = RaftTheme.typography.callout,
                        color = if (computer.description == null) RaftTheme.colors.foregroundHint else RaftTheme.colors.foreground,
                    )
                }
            }
        }
        item(key = "agents") { RaftComputerAgentsSection(snapshot, onOpenAgent) }
        item(key = "workspaces") {
            RaftComputerWorkspacesSection(
                snapshot = snapshot,
                scanning =
                    actionState?.let { it.action == COMPUTER_ACTION_SCAN_WORKSPACES && it.itemId == machineId && it.inProgress } == true,
                onScan = { onIntent(SharedSettingsIntent.ScanComputerWorkspaces(machineId)) },
            )
        }
        if (canManage) {
            item(key = "actions") {
                RaftSection(title = stringResource(MR.strings.settings_computers_section_actions)) {
                    val deleting = actionState?.let { it.action == COMPUTER_ACTION_DELETE && it.itemId == machineId && it.inProgress } == true
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RaftText(
                            stringResource(MR.strings.settings_computers_delete_title),
                            style = RaftTheme.typography.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            color = RaftTheme.colors.foreground,
                        )
                        RaftText(
                            stringResource(MR.strings.settings_computers_delete_detail),
                            style = RaftTheme.typography.footnote,
                            color = RaftTheme.colors.foregroundMuted,
                        )
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
        item(key = "bottom") { Spacer(Modifier.height(bottomContentPadding)) }
    }
    if (confirmDelete) {
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

@Composable
private fun RaftComputerEditableText(
    key: String,
    initial: String,
    label: String,
    placeholder: String,
    multiline: Boolean,
    allowBlank: Boolean,
    saving: Boolean,
    onSave: (String) -> Unit,
) {
    var value by remember(key, initial) { mutableStateOf(initial) }
    val trimmed = value.trim()
    val changed = trimmed != initial.trim()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftField(label = label) {
            if (multiline) {
                RaftTextarea(value = value, onValueChange = { value = it }, placeholder = placeholder, enabled = !saving)
            } else {
                RaftTextField(value = value, onValueChange = { value = it }, placeholder = placeholder, enabled = !saving)
            }
        }
        if (changed || saving) {
            RaftButton(
                text = stringResource(MR.strings.common_save),
                onClick = { onSave(trimmed) },
                enabled = allowBlank || trimmed.isNotBlank(),
                loading = saving,
                loadingLabel = stringResource(MR.strings.settings_computers_action_saving),
            )
        }
    }
}

@Composable
private fun RaftComputerAgentsSection(
    snapshot: SettingsComputerDetailSnapshot,
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
                        RaftListRow(
                            title = agent.name,
                            onClick = { onOpenAgent(agent) },
                            divider = index > 0,
                            subtitle = agent.runtime?.takeIf { it.isNotBlank() },
                            description = agent.description?.takeIf { it.isNotBlank() },
                            leading = {
                                RaftAvatar(
                                    type = RaftAvatarType.Agent,
                                    size = RaftAvatarSize.Md,
                                    initials = raftAvatarInitials(agent.name, RaftAvatarSize.Md),
                                )
                            },
                            trailing = { RaftListRowChevron() },
                        )
                    }
                }
        }
    }
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
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val error = snapshot.workspacesError
            when {
                !snapshot.workspacesScanned ->
                    RaftComputersInlineNotice(
                        title = stringResource(MR.strings.settings_computers_workspace_scan_title),
                        detail = stringResource(MR.strings.settings_computers_workspace_scan_detail),
                    )
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
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.Search) },
            )
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
private fun RaftComputersTheme(content: @Composable () -> Unit) {
    RaftTheme(appearance = if (RaftAppThemeState.isDark) RaftAppearance.Dark else RaftAppearance.Light, content = content)
}

/** A Lucide icon in the enclosing design-system slot's ink. */
@Composable
private fun RaftComputersIcon(name: RaftLucideIconName, size: Dp = 16.dp) {
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

@Composable
private fun RaftComputersRefreshAction(refreshing: Boolean, onRefresh: () -> Unit) {
    Box(modifier = Modifier.size(30.dp)) {
        RaftPanelAction(onClick = { if (!refreshing) onRefresh() }, modifier = Modifier.size(28.dp)) {
            RaftLucideIcon(
                RaftLucideIconName.RefreshCw,
                modifier = Modifier.size(16.dp),
                color = raftArgbHex(RaftColor.TextMuted),
                spinning = refreshing,
            )
        }
    }
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
