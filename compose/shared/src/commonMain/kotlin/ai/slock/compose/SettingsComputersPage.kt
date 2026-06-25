package ai.slock.compose

import ai.slock.compose.base.Utils
import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalCard
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.SettingsComputerAgentSnapshot
import ai.slock.compose.data.SettingsComputerDetailSnapshot
import ai.slock.compose.data.SettingsComputerRenameRequest
import ai.slock.compose.data.SettingsComputerSnapshot
import ai.slock.compose.data.SettingsComputerWorkspaceSnapshot
import ai.slock.compose.data.SettingsComputersSnapshot
import ai.slock.compose.data.SharedSettingsActionState
import ai.slock.compose.data.SharedSettingsDestination
import ai.slock.compose.data.SharedSettingsIntent
import ai.slock.compose.data.SharedSettingsState
import ai.slock.compose.data.SharedSettingsViewModel
import ai.slock.compose.data.isRefreshing
import ai.slock.compose.SlockColor
import ai.slock.compose.SlockType
import ai.slock.compose.SlockLucideIconName
import ai.slock.compose.SlockLucideIcon
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.SlockTimeFormat24Hour
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable

const val SETTINGS_COMPUTERS_PAGE = "compose_settings_computers"
const val SETTINGS_COMPUTER_DETAIL_PAGE = "compose_settings_computer_detail"

@Page(SETTINGS_COMPUTERS_PAGE, supportInLocal = true)
internal class ComposeSettingsComputersPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsComputersScreen() }
    }
}

@Page(SETTINGS_COMPUTER_DETAIL_PAGE, supportInLocal = true)
internal class ComposeSettingsComputerDetailPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsComputerDetailScreen() }
    }
}

@Serializable
internal data class KuiklySettingsComputersPayload(
    val instanceId: String = "",
    val status: String = "content",
    val computers: List<KuiklySettingsComputerItem> = emptyList(),
    val canAddComputer: Boolean = false,
    val addComputerUnavailableReason: String? = null,
    val latestDaemonVersion: String? = null,
    val emptyTitle: String = "No computers yet",
    val emptyDetail: String = "Connect a desktop daemon to see it here."
)

@Serializable
internal data class KuiklySettingsComputerDetailPayload(
    val instanceId: String = "",
    val computer: KuiklySettingsComputerItem = KuiklySettingsComputerItem(),
    val workspaces: List<KuiklySettingsWorkspaceItem> = emptyList(),
    val agents: List<KuiklySettingsAgentItem> = emptyList(),
    val canOpenAgents: Boolean = false,
    val canManageMachines: Boolean = false,
    val workspacesErrorCode: String? = null,
    val workspacesRetryable: Boolean = false,
    val agentsErrorCode: String? = null,
    val agentsRetryable: Boolean = false
)

@Serializable
internal data class KuiklySettingsComputerItem(
    val id: String = "",
    val name: String = "Computer",
    val daemonVersion: String = "",
    val online: Boolean = false,
    val hostname: String? = null,
    val platform: String? = null,
    val lastSeenLabel: String? = null,
    val runtimes: List<String> = emptyList(),
    val createdAt: String? = null,
    val apiKeyPrefix: String? = null,
    val statusVersion: Long = 0L
)

@Serializable
internal data class KuiklySettingsWorkspaceItem(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val statusLabel: String? = null,
    val lastActiveLabel: String? = null
)

@Serializable
internal data class KuiklySettingsAgentItem(
    val id: String = "",
    val name: String = "",
    val statusLabel: String? = null,
    val ownerLabel: String? = null,
    val displayName: String? = null,
    val handle: String? = null,
    val description: String? = null,
    val presenceLabel: String? = null,
    val roleLabel: String? = null
)

@Composable
private fun SettingsComputersScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val launchPayload = rememberComputersPayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.toString(),
        pager.pageData.params.optString("instanceId")
    )
    val timeContext = remember(pager.pageData.params.toString()) {
        pager.pageData.params.settingsStandaloneTimeContext()
    }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-computers") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        bridge.log("settings_computers_open instanceId=${launchPayload.instanceId}")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenDestination(SharedSettingsDestination.Computers))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    val computersPayload =
        settingsState.computers.toComputersPayload(
            refreshing = settingsState.isRefreshing(SharedSettingsDestination.Computers),
            timeContext = timeContext
        )
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = "Computers",
            subtitle = "Connected machines",
            iconName = SlockLucideIconName.Monitor,
            backgroundColor = SlockColor.SurfaceWhite,
            trailingContent =
                if (computersPayload.canAddComputer) {
                    {
                        SlockBrutalIconButton(
                            iconName = SlockLucideIconName.Plus,
                            onClick = { settingsViewModel.dispatch(SharedSettingsIntent.ShowAddComputerDialog) },
                            size = 36.dp,
                            shadowOffset = 3.dp
                        )
                    }
                } else {
                    null
                }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SettingsComputersContent(computersPayload) { action, itemId ->
                when (action) {
                    "openComputer" -> itemId?.takeIf { it.isNotBlank() }?.let { pager.openSettingsComputerDetailPage(it, timeContext) }
                    else -> bridge.settingsEvent(launchPayload.instanceId, action, itemId)
                }
            }
        }
    }
    settingsState.addComputerDialog?.let { dialogState ->
        AddComputerDialog(state = dialogState, onIntent = settingsViewModel::dispatch)
    }
}

@Composable
private fun SettingsComputerDetailScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val launchPayload = rememberComputerDetailPayload(
        pager.pageData.params.optString("payload"),
        pager.pageData.params.toString(),
        pager.pageData.params.optString("instanceId")
    )
    val computerId = launchPayload.computer.id
    val timeContext = remember(pager.pageData.params.toString()) {
        pager.pageData.params.settingsStandaloneTimeContext()
    }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-computer-detail-${computerId.ifBlank { "missing" }}") {
            SharedSettingsViewModel(environment.dataSource)
        }
    val settingsState by settingsViewModel.state.collectAsState()
    LaunchedEffect(computerId) {
        bridge.log("settings_computer_detail_open instanceId=${launchPayload.instanceId} computerId=$computerId")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenComputer(computerId))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    SettingsComputerDetailRouteShell(
        title = settingsState.computerDetail.currentComputerTitle(),
        onBack = { pager.closeSettingsComputerDetailPage() }
    ) {
        SettingsComputerDetailContent(
            payload = settingsState.computerDetail.toComputerDetailPayload(computerId, timeContext),
            actionState = settingsState.actionState?.toComputerActionState(),
            bottomContentPadding = SlockSpacing.Lg
        ) { action, itemId ->
            handleSettingsComputerDetailAction(
                action = action,
                itemId = itemId,
                computerId = computerId,
                settingsState = settingsState,
                dispatch = settingsViewModel::dispatch,
                fallbackEvent = { fallbackAction, fallbackItem ->
                    bridge.settingsEvent(launchPayload.instanceId, fallbackAction, fallbackItem)
                }
            )
        }
    }
}

@Composable
private fun rememberComputersPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsComputersPayload =
    androidx.compose.runtime.remember(payload, fallbackPageData, instanceId) {
        parseSettingsComputersPayload(payload, fallbackPageData, instanceId)
    }

@Composable
private fun rememberComputerDetailPayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): KuiklySettingsComputerDetailPayload =
    androidx.compose.runtime.remember(payload, fallbackPageData, instanceId) {
        parseSettingsComputerDetailPayload(payload, fallbackPageData, instanceId)
    }

@Composable
private fun SettingsComputerDetailRouteShell(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
    ) {
        SlockTopPageHeader(
            title = title,
            subtitle = "Machine details",
            leadingContent = {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.ArrowLeft,
                    onClick = onBack,
                    size = 34.dp,
                    shadowOffset = 2.dp
                )
            }
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            content()
        }
    }
}

private fun handleSettingsComputerDetailAction(
    action: String,
    itemId: String?,
    computerId: String,
    settingsState: SharedSettingsState,
    dispatch: (SharedSettingsIntent) -> Unit,
    fallbackEvent: (String, String?) -> Unit
) {
    when (action) {
        "retryWorkspaces", "retryAgents" ->
            computerId.takeIf { it.isNotBlank() }?.let { dispatch(SharedSettingsIntent.OpenComputer(it)) }
        "renameComputer" -> itemId?.takeIf { it.isNotBlank() }?.let { payload ->
            val parts = payload.split("|", limit = 2)
            val machineId = parts[0]
            val name = parts.getOrElse(1) { "" }
            dispatch(SharedSettingsIntent.RenameComputer(SettingsComputerRenameRequest(machineId = machineId, name = name)))
        }
        "deleteComputer" -> itemId?.takeIf { it.isNotBlank() }?.let { machineId ->
            dispatch(SharedSettingsIntent.DeleteComputer(machineId))
        }
        "rotateComputerKey" -> itemId?.takeIf { it.isNotBlank() }?.let { machineId ->
            dispatch(SharedSettingsIntent.RotateComputerKey(machineId))
        }
        "retryComputerAction" -> {
            val lastAction = settingsState.actionState?.action
            val lastItemId = settingsState.actionState?.itemId
            when (lastAction) {
                "renameComputer" -> lastItemId?.let { payload ->
                    val parts = payload.split("|", limit = 2)
                    dispatch(
                        SharedSettingsIntent.RenameComputer(
                            SettingsComputerRenameRequest(
                                machineId = parts[0],
                                name = parts.getOrElse(1) { "" }
                            )
                        )
                    )
                }
                "deleteComputer" -> lastItemId?.let { dispatch(SharedSettingsIntent.DeleteComputer(it)) }
                "rotateComputerKey" -> lastItemId?.let { dispatch(SharedSettingsIntent.RotateComputerKey(it)) }
                else -> Unit
            }
        }
        else -> fallbackEvent(action, itemId)
    }
}

@Composable
internal fun SettingsComputersContent(
    payload: KuiklySettingsComputersPayload,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onEvent: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        payload.latestDaemonVersion?.takeIf { it.isNotBlank() }?.let { version ->
            item { SettingsInfoStrip("Latest daemon", version) }
        }
        if (payload.computers.isEmpty()) {
            item { SettingsEmptyMessage(payload.emptyTitle, payload.emptyDetail) }
        } else {
            items(payload.computers.size, key = { index -> "computer-$index-${payload.computers[index].id}" }) { index ->
                SettingsComputerCard(payload.computers[index]) {
                    onEvent("openComputer", payload.computers[index].id)
                }
            }
        }
        payload.addComputerUnavailableReason?.takeIf { it.isNotBlank() }?.let { reason ->
            item { SettingsInfoStrip("Add computer", reason) }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
internal fun SettingsComputerDetailContent(
    payload: KuiklySettingsComputerDetailPayload,
    actionState: KuiklySettingsComputerActionState? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onEvent: (String, String?) -> Unit
) {
    val computerId = payload.computer.id
    val computerName = payload.computer.name
    val renameBusy = actionState?.action == "renameComputer" && actionState.inProgress && actionState.itemId == computerId
    val deleteBusy = actionState?.action == "deleteComputer" && actionState.inProgress && actionState.itemId == computerId
    val rotateBusy = actionState?.action == "rotateComputerKey" && actionState.inProgress && actionState.itemId == computerId
    val anyBusy = renameBusy || deleteBusy || rotateBusy
    var showRenameModal by remember(computerId) { mutableStateOf(false) }
    var renameDraft by remember(computerId) { mutableStateOf(computerName) }
    fun openRenameModal() {
        renameDraft = computerName
        showRenameModal = true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SettingsComputerProfileCard(payload.computer) }
        item {
            SettingsComputerNameSection(
                name = computerName,
                canEdit = payload.canManageMachines,
                enabled = !anyBusy,
                onEdit = { openRenameModal() }
            )
        }
        item { SettingsSectionHeader(iconName = SlockLucideIconName.Info, title = "Info") }
        item {
            SettingsDetailPanel {
                SettingsDetailPanelRow("Platform", payload.computer.platform ?: "Unknown")
                SettingsDetailPanelRow("Hostname", payload.computer.hostname ?: payload.computer.name)
                SettingsDetailPanelRow("Last seen", payload.computer.lastSeenLabel ?: "Unknown")
                SettingsDetailPanelRow("Created", payload.computer.createdAt ?: "Unknown")
                if (payload.computer.runtimes.isNotEmpty()) {
                    SettingsRuntimePillRow("Detected runtimes", payload.computer.runtimes)
                }
                if (!payload.computer.apiKeyPrefix.isNullOrBlank()) {
                    SettingsDetailPanelRow("API key prefix", payload.computer.apiKeyPrefix)
                }
            }
        }
        item { SettingsSectionHeader(iconName = SlockLucideIconName.FolderOpen, title = "Agent Workspaces", count = payload.workspaces.size) }
        payload.workspacesErrorCode?.let { error ->
            item { SettingsInlineError("Unable to load workspaces", error, payload.workspacesRetryable) { onEvent("retryWorkspaces", null) } }
        } ?: if (payload.workspaces.isEmpty()) {
            item { SettingsEmptyMessage("No workspaces", "Workspaces opened by this computer will show up here.") }
        } else {
            items(payload.workspaces.size, key = { index -> "workspace-$index-${payload.workspaces[index].id}" }) { index ->
                SettingsWorkspaceCard(payload.workspaces[index])
            }
        }
        item { SettingsSectionHeader(iconName = SlockLucideIconName.Bot, title = "Agents on this computer", count = payload.agents.size) }
        payload.agentsErrorCode?.let { error ->
            item { SettingsInlineError("Unable to load agents", error, payload.agentsRetryable) { onEvent("retryAgents", null) } }
        } ?: if (payload.agents.isEmpty()) {
            item { SettingsEmptyMessage("No agents", "Agents running on this computer will show up here.") }
        } else {
            items(payload.agents.size, key = { index -> "agent-$index-${payload.agents[index].id}" }) { index ->
                SettingsAgentCard(
                    item = payload.agents[index],
                    onClick =
                        if (payload.canOpenAgents) {
                            { onEvent("openAgent", payload.agents[index].id) }
                        } else {
                            null
                        }
                )
            }
        }
        if (payload.canManageMachines) {
            item { SettingsSectionHeader(iconName = SlockLucideIconName.Settings, title = "Actions") }
            item {
                // #78: rename / delete / rotate-key UI surface.
                // Each button dispatches via onEvent(...) which the host
                // translates to SharedSettingsIntent.RenameComputer /
                // .DeleteComputer / .RotateComputerKey. The actionState
                // disables buttons + swaps the label while the request is
                // in flight. Errors surface via the existing
                // settingsComputerErrorCopy mapping (see #74 commit
                // cf01a0a4) for codes like computer_has_agents / 409.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsActionRow(
                        title = "Rename computer",
                        description = "Change this computer's display name.",
                        actionLabel = if (renameBusy) "Renaming..." else "Rename",
                        enabled = !anyBusy,
                        onClick = { openRenameModal() }
                    )
                    SettingsActionRow(
                        title = "Rotate API key",
                        description = "Generate a new key for the daemon to reconnect. The old key stops working immediately.",
                        actionLabel = if (rotateBusy) "Rotating..." else "Rotate key",
                        enabled = !anyBusy,
                        onClick = { onEvent("rotateComputerKey", computerId) }
                    )
                    SettingsActionRow(
                        title = "Delete computer",
                        description = "Permanently remove this computer. All agents must be deleted first.",
                        actionLabel = if (deleteBusy) "Deleting..." else "Delete",
                        enabled = !anyBusy,
                        destructive = true,
                        onClick = { onEvent("deleteComputer", computerId) }
                    )
                    actionState?.errorCode?.takeIf { it.isNotBlank() && actionState.itemId == computerId }?.let { code ->
                        SettingsInlineError(
                            title = settingsComputerErrorTitle(code),
                            errorCode = code,
                            retryable = false
                        ) { onEvent("retryComputerAction", actionState.action) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
    if (payload.canManageMachines && showRenameModal) {
        SlockUnifiedDialog(
            title = "Rename Computer",
            onDismiss = { if (!renameBusy) showRenameModal = false },
            dismissEnabled = !renameBusy
        ) {
            Text(
                "Enter a new name for ",
                style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            Text(
                computerName,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
            SlockBrutalTextField(
                label = "New name",
                value = renameDraft,
                placeholder = computerName,
                onValueChange = { renameDraft = it },
                required = true,
                enabled = !renameBusy
            )
            SlockModalFullWidthActionButton(
                label = if (renameBusy) "Renaming..." else "Rename",
                icon = SlockLucideIconName.Save,
                onClick = {
                    onEvent("renameComputer", "$computerId|${renameDraft.trim()}")
                    if (!renameBusy) showRenameModal = false
                },
                enabled = renameDraft.trim().isNotBlank() && renameDraft.trim() != computerName && !renameBusy,
                color = SlockColor.AccentPink
            )
        }
    }
}

@Serializable
internal data class KuiklySettingsComputerActionState(
    val action: String = "",
    val itemId: String? = null,
    val inProgress: Boolean = false,
    val errorCode: String? = null
)

internal fun ScreenLoadState<SettingsComputersSnapshot>.toComputersPayload(
    refreshing: Boolean,
    timeContext: SlockTimeContext?
): KuiklySettingsComputersPayload =
    when (this) {
        is ScreenLoadState.Content ->
            data.toComputersPayload(refreshing = refreshing, timeContext = timeContext)
        is ScreenLoadState.Error ->
            KuiklySettingsComputersPayload(
                instanceId = "shared-shell-settings-computers",
                status = "error",
                addComputerUnavailableReason = error.code,
                emptyTitle = "Unable to load computers",
                emptyDetail = if (error.retryable) "Pull to refresh or try again." else "Settings cannot load connected machines right now."
            )
        is ScreenLoadState.NoPermission ->
            KuiklySettingsComputersPayload(
                instanceId = "shared-shell-settings-computers",
                status = "blocked",
                addComputerUnavailableReason = blocker.code,
                emptyTitle = "No access to computers",
                emptyDetail = "You do not have permission to view connected machines."
            )
        is ScreenLoadState.Empty ->
            KuiklySettingsComputersPayload(
                instanceId = "shared-shell-settings-computers",
                status = "empty",
                emptyTitle = "No computers yet",
                emptyDetail = "Connect a desktop daemon to see it here."
            )
        ScreenLoadState.Loading ->
            KuiklySettingsComputersPayload(
                instanceId = "shared-shell-settings-computers",
                status = "loading",
                emptyTitle = "Loading computers",
                emptyDetail = "Fetching connected machines from this server."
            )
    }

private fun SettingsComputersSnapshot.toComputersPayload(
    refreshing: Boolean,
    timeContext: SlockTimeContext?
): KuiklySettingsComputersPayload =
    KuiklySettingsComputersPayload(
        instanceId = "shared-shell-settings-computers",
        status = if (refreshing) "refreshing" else "content",
        computers = computers.map { it.toComputerItem(timeContext) },
        canAddComputer = canAddComputer,
        addComputerUnavailableReason = addComputerUnavailableReason,
        latestDaemonVersion = latestDaemonVersion
    )

internal fun ScreenLoadState<SettingsComputerDetailSnapshot>.toComputerDetailPayload(
    selectedComputerId: String?,
    timeContext: SlockTimeContext?
): KuiklySettingsComputerDetailPayload =
    when (this) {
        is ScreenLoadState.Content ->
            data.toComputerDetailPayload(timeContext)
        is ScreenLoadState.Error ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                name = "Unable to load computer",
                workspacesErrorCode = error.code,
                workspacesRetryable = error.retryable,
                agentsErrorCode = error.code,
                agentsRetryable = error.retryable
            )
        is ScreenLoadState.NoPermission ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                name = "No access to computer",
                workspacesErrorCode = blocker.code,
                workspacesRetryable = false,
                agentsErrorCode = blocker.code,
                agentsRetryable = false
            )
        is ScreenLoadState.Empty ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                name = "Computer unavailable"
            )
        ScreenLoadState.Loading ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                name = "Loading computer"
            )
    }

private fun ScreenLoadState<SettingsComputerDetailSnapshot>.currentComputerTitle(): String =
    when (this) {
        is ScreenLoadState.Content -> data.computer.name.takeIf { it.isNotBlank() } ?: "Computer"
        is ScreenLoadState.Error -> "Unable to load computer"
        is ScreenLoadState.NoPermission -> "No access to computer"
        is ScreenLoadState.Empty -> "Computer unavailable"
        ScreenLoadState.Loading -> "Computer"
    }

private fun SettingsComputerDetailSnapshot.toComputerDetailPayload(
    timeContext: SlockTimeContext?
): KuiklySettingsComputerDetailPayload =
    KuiklySettingsComputerDetailPayload(
        instanceId = "shared-shell-settings-computer-${computer.id}",
        computer = computer.toComputerItem(timeContext),
        workspaces = workspaces.map { it.toWorkspaceItem(timeContext) },
        agents = agents.map { it.toAgentItem() },
        canManageMachines = canManageMachines,
        workspacesErrorCode = workspacesError?.code,
        workspacesRetryable = workspacesError?.retryable ?: false,
        agentsErrorCode = agentsError?.code,
        agentsRetryable = agentsError?.retryable ?: false
    )

private fun loadingComputerDetailPayload(
    selectedComputerId: String?,
    name: String,
    workspacesErrorCode: String? = null,
    workspacesRetryable: Boolean = false,
    agentsErrorCode: String? = null,
    agentsRetryable: Boolean = false
): KuiklySettingsComputerDetailPayload =
    KuiklySettingsComputerDetailPayload(
        instanceId = "shared-shell-settings-computer-${selectedComputerId.orEmpty()}",
        computer = KuiklySettingsComputerItem(id = selectedComputerId.orEmpty(), name = name),
        workspacesErrorCode = workspacesErrorCode,
        workspacesRetryable = workspacesRetryable,
        agentsErrorCode = agentsErrorCode,
        agentsRetryable = agentsRetryable
    )

internal fun SettingsComputerSnapshot.toComputerItem(
    timeContext: SlockTimeContext?
): KuiklySettingsComputerItem =
    KuiklySettingsComputerItem(
        id = id,
        name = name,
        daemonVersion = daemonVersion,
        online = online,
        hostname = hostname,
        platform = platform,
        lastSeenLabel = formatSettingsTimestampLabel(lastSeenLabel, timeContext),
        runtimes = runtimes,
        createdAt = formatSettingsTimestampLabel(createdAt, timeContext),
        apiKeyPrefix = apiKeyPrefix,
        statusVersion = statusVersion
    )

internal fun SharedSettingsActionState.toComputerActionState(): KuiklySettingsComputerActionState =
    KuiklySettingsComputerActionState(
        action = action,
        itemId = itemId,
        inProgress = inProgress,
        errorCode = errorCode
    )

private fun SettingsComputerWorkspaceSnapshot.toWorkspaceItem(
    timeContext: SlockTimeContext?
): KuiklySettingsWorkspaceItem =
    KuiklySettingsWorkspaceItem(
        id = id,
        name = name,
        path = path,
        statusLabel = statusLabel,
        lastActiveLabel = formatSettingsTimestampLabel(lastActiveLabel, timeContext)
    )

private fun SettingsComputerAgentSnapshot.toAgentItem(): KuiklySettingsAgentItem {
    val profile = memberProfile
    return KuiklySettingsAgentItem(
        id = id,
        name = name,
        statusLabel = statusLabel,
        ownerLabel = ownerLabel,
        displayName = profile?.displayName,
        handle = profile?.handle,
        description = profile?.description,
        presenceLabel = profile?.presence?.name,
        roleLabel = profile?.roleDescription
    )
}

internal fun JSONObject.settingsStandaloneTimeContext(): SlockTimeContext? {
    val offset = optString("localOffsetMinutes").toIntOrNull() ?: return null
    val today = optString("todayEpochDay").toLongOrNull()
    val now = optString("nowEpochMillis").toLongOrNull()
    return SlockTimeContext(offset, today, now).withTimeFormat(optString("timeFormat").takeIf { it.isNotBlank() })
}

internal fun Pager.openSettingsComputerDetailPage(
    computerId: String,
    timeContext: SlockTimeContext?
) {
    val id = computerId.takeIf { it.isNotBlank() } ?: return
    val pageData =
        settingsComputerDetailPageData(
            computerId = id,
            timeContext = timeContext
        )
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SETTINGS_COMPUTER_DETAIL_PAGE, pageData)
}

internal fun settingsComputerDetailPageData(
    computerId: String,
    timeContext: SlockTimeContext?
): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_COMPUTER_DETAIL_PAGE)
        put("instanceId", "settings-computer-detail-$computerId")
        put("computerId", computerId)
        put("machineId", computerId)
        put("localOffsetMinutes", timeContext?.localOffsetMinutes ?: 0)
        timeContext?.todayEpochDay?.let { put("todayEpochDay", it) }
        timeContext?.nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeContext?.timeFormat ?: SlockTimeFormat24Hour)
    }

private fun Pager.closeSettingsComputerDetailPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
}

private fun settingsComputerErrorTitle(code: String): String = when (code) {
    "computer_has_agents" -> "Cannot delete computer"
    "computer_bad_request" -> "Computer request rejected"
    "computer_offline_replay" -> "Computer offline"
    "machine_offline_replay" -> "Computer offline"
    "machine_offline" -> "Computer offline"
    "server_forbidden" -> "Permission denied"
    "auth_required" -> "Session expired"
    else -> "Action failed"
}

@Composable
private fun SettingsActionRow(
    title: String,
    description: String,
    actionLabel: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(
            description,
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        SlockBrutalActionButton(
            label = actionLabel,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = if (destructive) SlockColor.AccentRed else SlockColor.BrandYellow,
            minWidth = 0.dp,
            minHeight = 40.dp,
            shadowOffset = 2.dp,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsComputerCard(item: KuiklySettingsComputerItem, onClick: (() -> Unit)?) {
    // #77: align the Settings Computers list card with React's
    // `MobileComputersPanel.tsx` (107 行) and `Sidebar.tsx` line 1248-1290 list
    // pattern — each card shows name + status dot + daemon version line.
    // host/platform/createdAt/lastHeartbeat/runtimes are intentionally NOT
    // rendered here; they live on the ComputerDetail page (see #74 commit
    // `cf01a0a4`) and `MachineDetailPanel.tsx` Info / Detected Runtimes
    // sections. This matches the user's "align to React" + "do not fabricate
    // server-side fields" guard rails.
    SlockBrutalCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shadowOffset = 4.dp,
        contentPadding = 14.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconBox(SlockLucideIconName.Monitor, SlockColor.BrandYellow)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    OnlineDot(item.online)
                }
                val daemonVersion = item.daemonVersion.takeIf { it.isNotBlank() }
                Text(
                    text = if (daemonVersion != null) "daemon v$daemonVersion" else "daemon offline",
                    style = SlockType.Input.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = if (daemonVersion != null) SlockColor.TextMutedGray else SlockColor.EmptyStateGray,
                        fontStyle = if (daemonVersion != null) null else FontStyle.Italic
                    ),
                    color = if (daemonVersion != null) SlockColor.TextMutedGray else SlockColor.EmptyStateGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SettingsWorkspaceCard(item: KuiklySettingsWorkspaceItem) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(item.name, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.path, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        listOf(item.statusLabel, item.lastActiveLabel).filterNotNull().filter { it.isNotBlank() }.joinToString("  ").takeIf { it.isNotBlank() }?.let {
            Text(it, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        }
    }
}

@Composable
private fun SettingsAgentCard(item: KuiklySettingsAgentItem, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsAgentAvatar(item)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.displayName?.takeIf { it.isNotBlank() } ?: item.name, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                item.statusLabel?.takeIf { it.isNotBlank() }?.let { SmallPill(it) }
            }
            listOf(item.handle, item.ownerLabel, item.presenceLabel).filterNotNull().filter { it.isNotBlank() }.joinToString("  ").takeIf { it.isNotBlank() }?.let {
                Text(it, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettingsComputerProfileCard(item: KuiklySettingsComputerItem) {
    SlockBrutalCard(
        shadowOffset = 4.dp,
        contentPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LargeIconBox(SlockLucideIconName.Monitor, SlockColor.BrandYellow)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.name,
                    style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OnlineDot(item.online)
                    Text(
                        if (item.online) "Connected" else "Offline",
                        style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray
                    )
                }
                item.hostname?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsComputerNameSection(
    name: String,
    canEdit: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit
) {
    SettingsDetailPanel {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "NAME",
                    style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f)
                )
                if (canEdit) {
                    Box(
                        modifier = Modifier.size(28.dp).clickable(enabled = enabled, onClick = onEdit),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockLucideIcon(
                            SlockLucideIconName.Pencil,
                            modifier = Modifier.size(14.dp),
                            color = if (enabled) SlockColor.TextMutedGrayArgb else "#33000000"
                        )
                    }
                }
            }
            Text(
                name,
                style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    iconName: SlockLucideIconName,
    title: String,
    count: Int? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(14.dp), color = SlockColor.TextBlackArgb)
        Text(
            title.uppercase(),
            style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
        count?.let { SmallPill(it.toString()) }
    }
}

@Composable
private fun SettingsDetailPanel(content: @Composable () -> Unit) {
    SlockBrutalCard(
        shadowOffset = 3.dp,
        contentPadding = 0.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsDetailPanelRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.width(108.dp)
        )
        Text(
            value,
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsRuntimePillRow(label: String, runtimes: List<String>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            runtimes.take(4).forEach { runtime ->
                SmallPill(runtime)
            }
            if (runtimes.size > 4) {
                SmallPill("+${runtimes.size - 4}")
            }
        }
    }
}

@Composable
private fun SettingsInlineError(title: String, errorCode: String, retryable: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(settingsComputerErrorCopy(errorCode), style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        if (retryable) {
            SettingsPrimaryButton("Retry", onRetry)
        }
    }
}

private fun settingsComputerErrorCopy(code: String): String = when (code) {
    "machine_offline" -> "This computer is offline. The daemon is not connected to the server, so workspaces and agents can't be loaded right now."
    "machine_offline_replay" -> "This computer is being served by a different server replica. Try again in a moment."
    "computer_has_agents" -> "This computer still has agents assigned. Remove all agents before deleting the computer."
    "computer_bad_request" -> "The request was rejected by the server. Check the name / value and try again."
    "server_forbidden" -> "You don't have permission to manage this computer."
    "auth_required" -> "Your session expired. Sign in again to continue."
    "server_unavailable" -> "The server is temporarily unavailable. Try again in a moment."
    else -> "Error: $code"
}

@Composable
private fun SettingsEmptyMessage(title: String, detail: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        }
    }
}

@Composable
private fun SettingsInfoStrip(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        Text(value, style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingsPrimaryButton(text: String, onClick: () -> Unit) {
    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = SlockColor.BrandYellow,
        minWidth = 0.dp,
        minHeight = 44.dp,
        shadowOffset = 2.dp,
        textStyle = SlockType.Button.copy(color = SlockColor.TextBlack)
    )
}

@Composable
private fun IconBox(iconName: SlockLucideIconName, color: Color) {
    Box(
        modifier = Modifier.size(42.dp).background(color).border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(name = iconName, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LargeIconBox(iconName: SlockLucideIconName, color: Color) {
    Box(
        modifier = Modifier.size(64.dp).background(color).border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(name = iconName, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun OnlineDot(online: Boolean) {
    SlockStatusIndicator(color = if (online) SlockColor.AccentCyan else SlockColor.EmptyStateGray)
}

@Composable
private fun SettingsAgentAvatar(item: KuiklySettingsAgentItem) {
    SlockAvatarWithStatus(
        statusColor = item.settingsAgentStatusColor(),
        avatarSize = 42.dp,
        statusSize = 10.dp
    ) {
        IconBox(SlockLucideIconName.Bot, SlockColor.AccentCyan)
    }
}

private fun KuiklySettingsAgentItem.settingsAgentStatusColor(): Color =
    when ((statusLabel ?: presenceLabel).orEmpty().trim().lowercase()) {
        "online", "active", "connected", "working", "running", "thinking" -> SlockColor.AccentLime
        "idle", "away", "busy" -> SlockColor.BrandYellow
        "error", "failed", "failure" -> SlockColor.AccentRed
        "offline", "disconnected", "not connected", "inactive" -> SlockColor.EmptyStateGray
        else -> SlockColor.BrandYellow
    }

@Composable
private fun SmallPill(text: String) {
    Box(
        modifier = Modifier.background(SlockColor.BrandYellow).border(1.dp, SlockColor.BorderBlack).padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = SlockType.Input.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1)
    }
}
