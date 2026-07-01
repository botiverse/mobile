package build.raft.app

import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalCard
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerRenameRequest
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerWorkspaceSnapshot
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.isRefreshing
import build.raft.app.SlockColor
import build.raft.app.SlockType
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockLucideIcon
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.SlockTimeFormat24Hour
import build.raft.app.model.Presence
import build.raft.app.model.RUNTIMES
import build.raft.app.model.avatarWireValueForAvatarSlot
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
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
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
internal class ComposeSettingsComputersPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsComputersScreen() }
    }
}

@Page(SETTINGS_COMPUTER_DETAIL_PAGE, supportInLocal = true)
internal class ComposeSettingsComputerDetailPage : build.raft.app.base.BasePager() {
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
    val latestComputerVersion: String? = null,
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
    val computerVersion: String? = null,
    val isComputer: Boolean = false,
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
    val runtimeLabel: String? = null,
    val avatarWireValue: String? = null,
    val displayName: String? = null,
    val handle: String? = null,
    val description: String? = null,
    val presenceLabel: String? = null,
    val roleLabel: String? = null,
    val activityLabel: String? = null
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
    val visualFixture = pager.pageData.params.optString("visualFixture")
    val timeContext = remember(pager.pageData.params.toString()) {
        pager.pageData.params.settingsStandaloneTimeContext()
    }
    if (visualFixture == "computers") {
        Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
            SlockTopPageHeader(
                title = "Computers",
                subtitle = "Connected machines",
                iconName = SlockLucideIconName.Monitor,
                backgroundColor = SlockColor.SurfaceWhite,
                trailingContent = {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.Plus,
                        onClick = {},
                        size = 36.dp,
                        shadowOffset = 3.dp
                    )
                }
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SettingsComputersContent(visualSettingsComputersPayload()) { _, _ -> }
            }
        }
        return
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
                    else -> bridge.log("settings_computers_unsupported_action action=$action itemId=${itemId.orEmpty()}")
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
    if (pager.pageData.params.optString("visualFixture") == "computer_detail") {
        val visualPayload = remember(timeContext) { visualSettingsComputerDetailPayload(timeContext) }
        SettingsComputerDetailVisualRouteShell(
            title = visualPayload.computer.name,
            onBack = {}
        ) {
            SettingsComputerDetailVisualContent(
                payload = visualPayload,
                actionState = null,
                agentStatusResolver = EmptySlockAgentStatusResolver,
                productionMode = false,
                onEvent = { _, _ -> }
            )
        }
        return
    }
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    var currentServerId by remember(environment.authDataSource) { mutableStateOf<String?>(null) }
    LaunchedEffect(environment.authDataSource) {
        currentServerId =
            when (val session = environment.authDataSource.currentSession()) {
                is SharedAuthResult.Success -> session.data.currentServerId
                is SharedAuthResult.Failure,
                SharedAuthResult.Unauthenticated -> null
            }
    }
    val agentStatusResolver =
        remember(environment.serverMembersStore, currentServerId) {
            slockAgentStatusResolver(environment.serverMembersStore, currentServerId)
        }
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
            agentStatusResolver = agentStatusResolver,
            bottomContentPadding = SlockSpacing.Lg
        ) { action, itemId ->
            handleSettingsComputerDetailAction(
                action = action,
                itemId = itemId,
                computerId = computerId,
                settingsState = settingsState,
                dispatch = settingsViewModel::dispatch,
                fallbackEvent = { fallbackAction, fallbackItem ->
                    bridge.log("settings_computer_detail_unsupported_action action=$fallbackAction itemId=${fallbackItem.orEmpty()}")
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

@Composable
private fun SettingsComputerDetailVisualRouteShell(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(SlockColor.SurfaceWhite)
                    .padding(start = 20.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockBrutalIconButton(
                iconName = SlockLucideIconName.ArrowLeft,
                onClick = onBack,
                size = 30.dp,
                shadowOffset = 2.dp
            )
            Box(
                modifier = Modifier.size(34.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(name = SlockLucideIconName.Monitor, modifier = Modifier.size(18.dp))
            }
            Text(
                title,
                style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
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
        if (payload.computers.isEmpty()) {
            item { SettingsEmptyMessage(payload.emptyTitle, payload.emptyDetail) }
        } else {
            items(payload.computers.size, key = { index -> "computer-$index-${payload.computers[index].id}" }) { index ->
                SettingsComputerCard(
                    item = payload.computers[index],
                    latestComputerVersion = payload.latestComputerVersion
                ) {
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
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onEvent: (String, String?) -> Unit
) {
    val computerId = payload.computer.id
    val computerName = payload.computer.name
    val renameBusy = actionState?.action == "renameComputer" && actionState.inProgress && actionState.itemId == computerId
    val deleteBusy = actionState?.action == "deleteComputer" && actionState.inProgress && actionState.itemId == computerId
    val rotateBusy = actionState?.action == "rotateComputerKey" && actionState.inProgress && actionState.itemId == computerId
    val anyBusy = renameBusy || deleteBusy || rotateBusy
    var renameDraft by remember(computerId) { mutableStateOf(computerName) }
    var editingName by remember(computerId) { mutableStateOf(false) }
    fun startRename() {
        renameDraft = computerName
        editingName = true
    }
    fun cancelRename() {
        renameDraft = computerName
        editingName = false
    }
    fun saveRename() {
        val nextName = renameDraft.trim()
        if (nextName.isNotBlank() && nextName != computerName) {
            onEvent("renameComputer", "$computerId|$nextName")
        }
        if (!renameBusy) editingName = false
    }

    SettingsComputerDetailVisualContent(
        payload = payload,
        actionState = actionState,
        agentStatusResolver = agentStatusResolver,
        productionMode = true,
        editingName = editingName,
        renameDraft = renameDraft,
        renameBusy = renameBusy,
        anyBusy = anyBusy,
        rotateBusy = rotateBusy,
        deleteBusy = deleteBusy,
        bottomContentPadding = bottomContentPadding,
        onRenameDraftChange = { renameDraft = it.replace('\n', ' ').replace('\r', ' ') },
        onStartRename = { startRename() },
        onCancelRename = { cancelRename() },
        onSaveRename = { saveRename() },
        onEvent = onEvent
    )
}

@Composable
private fun SettingsComputerDetailVisualContent(
    payload: KuiklySettingsComputerDetailPayload,
    actionState: KuiklySettingsComputerActionState? = null,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    productionMode: Boolean,
    editingName: Boolean = false,
    renameDraft: String = payload.computer.name,
    renameBusy: Boolean = false,
    anyBusy: Boolean = false,
    rotateBusy: Boolean = false,
    deleteBusy: Boolean = false,
    bottomContentPadding: Dp = 24.dp,
    onRenameDraftChange: (String) -> Unit = {},
    onStartRename: () -> Unit = {},
    onCancelRename: () -> Unit = {},
    onSaveRename: () -> Unit = {},
    onEvent: (String, String?) -> Unit
) {
    val computerId = payload.computer.id
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item { Spacer(Modifier.height(18.dp)) }
        item { VisualComputerProfile(payload.computer) }
        item { VisualDivider() }
        item {
            VisualComputerNameSection(
                name = payload.computer.name,
                canEdit = payload.canManageMachines,
                enabled = !anyBusy,
                editing = editingName,
                draft = renameDraft,
                busy = renameBusy,
                onDraftChange = onRenameDraftChange,
                onStartEdit = onStartRename,
                onCancel = onCancelRename,
                onSave = onSaveRename
            )
        }
        item { VisualDivider() }
        item { VisualComputerInfoSection(payload.computer) }
        item { VisualDivider() }
        item { Spacer(Modifier.height(20.dp)) }
        item { VisualSectionHeader("Agents on this computer", payload.agents.size) }
        if (payload.canManageMachines) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VisualSmallActionButton(
                        label = "Select",
                        icon = SlockLucideIconName.Check,
                        onClick = { onEvent("selectAgentsForComputer", computerId) },
                        backgroundColor = SlockColor.SurfaceWhite,
                        faceWidth = 72.dp
                    )
                    VisualSmallActionButton(
                        label = "Create",
                        icon = SlockLucideIconName.Plus,
                        onClick = { onEvent("createAgentForComputer", computerId) },
                        backgroundColor = SlockColor.AccentPink,
                        faceWidth = 72.dp
                    )
                }
            }
        }
        payload.agentsErrorCode?.let { error ->
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SettingsInlineError("Unable to load agents", error, payload.agentsRetryable) { onEvent("retryAgents", null) }
                }
            }
        } ?: if (payload.agents.isEmpty()) {
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SettingsEmptyMessage("No agents", "Agents running on this computer will show up here.")
                }
            }
        } else {
            items(payload.agents.size, key = { index -> "visual-agent-$index-${payload.agents[index].id}" }) { index ->
                val agent = payload.agents[index]
                val status =
                    if (productionMode) {
                        rememberSlockAgentStatusIndicatorState(agentStatusResolver, agent.id)
                            ?: agent.settingsAgentFallbackStatusState()
                    } else {
                        null
                    }
                VisualAgentRow(
                    item = agent,
                    status = status,
                    onClick =
                        if (payload.canOpenAgents) {
                            { onEvent("openAgent", agent.id) }
                        } else {
                            null
                        }
                )
            }
        }
        if (productionMode) {
            item { VisualDivider() }
            item { Spacer(Modifier.height(16.dp)) }
            item { VisualSectionHeader("Agent Workspaces", payload.workspaces.size) }
            payload.workspacesErrorCode?.let { error ->
                item {
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        SettingsInlineError("Unable to load workspaces", error, payload.workspacesRetryable) { onEvent("retryWorkspaces", null) }
                    }
                }
            } ?: if (payload.workspaces.isEmpty()) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        SettingsEmptyMessage("No workspaces", "Workspaces opened by this computer will show up here.")
                    }
                }
            } else {
                items(payload.workspaces.size, key = { index -> "visual-workspace-$index-${payload.workspaces[index].id}" }) { index ->
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        VisualWorkspaceRow(payload.workspaces[index])
                    }
                }
            }
            if (payload.canManageMachines) {
                item { VisualDivider() }
                item { Spacer(Modifier.height(16.dp)) }
                item { VisualSectionHeader("Actions") }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VisualActionPanel(
                            title = "Rotate API key",
                            description = "Generate a new key for the daemon to reconnect. The old key stops working immediately.",
                            label = if (rotateBusy) "Rotating..." else "Rotate key",
                            icon = SlockLucideIconName.RefreshCw,
                            backgroundColor = SlockColor.BrandYellow,
                            enabled = !anyBusy,
                            onClick = { onEvent("rotateComputerKey", computerId) }
                        )
                        VisualActionPanel(
                            title = "Delete Computer",
                            description = "Permanently remove this computer. All agents must be deleted first.",
                            label = if (deleteBusy) "Deleting..." else "Delete Computer",
                            icon = SlockLucideIconName.Trash2,
                            backgroundColor = SlockColor.AccentRed,
                            enabled = !anyBusy,
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
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun VisualComputerProfile(item: KuiklySettingsComputerItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp, end = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LargeIconBox(SlockLucideIconName.Monitor, SlockColor.BrandYellow)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                item.name,
                style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun VisualComputerNameSection(
    name: String,
    canEdit: Boolean,
    enabled: Boolean,
    editing: Boolean,
    draft: String,
    busy: Boolean,
    onDraftChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "NAME",
                style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
            if (canEdit) {
                Box(
                    modifier = Modifier.size(22.dp).clickable(enabled = enabled && !editing, onClick = onStartEdit),
                    contentAlignment = Alignment.Center
                ) {
                    SlockLucideIcon(
                        SlockLucideIconName.Pencil,
                        modifier = Modifier.size(12.dp),
                        color = if (enabled && !editing) SlockColor.TextMutedGrayArgb else "#33000000"
                    )
                }
            }
        }
        if (editing && canEdit) {
            SlockBrutalTextField(
                label = "Computer name",
                value = draft,
                placeholder = name,
                onValueChange = onDraftChange,
                required = true,
                enabled = !busy
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VisualSmallActionButton(
                    label = if (busy) "Saving" else "Save",
                    icon = SlockLucideIconName.Save,
                    onClick = onSave,
                    backgroundColor = SlockColor.AccentPink,
                    faceWidth = 64.dp,
                    enabled = !busy && draft.trim().isNotBlank()
                )
                VisualSmallActionButton(
                    label = "Cancel",
                    icon = SlockLucideIconName.X,
                    onClick = onCancel,
                    backgroundColor = SlockColor.SurfaceWhite,
                    faceWidth = 72.dp,
                    enabled = !busy
                )
            }
        } else {
            Text(
                name,
                style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VisualComputerInfoSection(item: KuiklySettingsComputerItem) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Text(
            "INFO",
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        VisualInfoValue("OS", item.platform ?: "Unknown")
        VisualInfoValue("Computer Version", item.daemonVersion.takeIf { it.isNotBlank() }?.let { "v$it" } ?: "Unknown")
        VisualRuntimeRows(item.runtimes)
        VisualInfoValue("Created", item.createdAt ?: "Unknown")
    }
}

@Composable
private fun VisualInfoValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            label,
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        Text(
            value,
            style = SlockType.Input.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VisualRuntimeRows(installedRuntimes: List<String>) {
    val runtimeRows = settingsComputerRuntimePillRows(installedRuntimes)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "Detected Runtimes",
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        runtimeRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { runtime ->
                    if (runtime.detected) {
                        VisualInstalledRuntimePill(runtime.label)
                    } else {
                        MissingRuntimePill(runtime.missingLabel)
                    }
                }
            }
        }
    }
}

internal data class SettingsComputerRuntimePill(
    val id: String,
    val label: String,
    val detected: Boolean,
    val supported: Boolean
) {
    val missingLabel: String = "$label (${if (supported) "not installed" else "coming soon"})"
}

internal fun settingsComputerRuntimePills(installedRuntimes: List<String>): List<SettingsComputerRuntimePill> {
    val detectedIds =
        installedRuntimes
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { runtime ->
                RUNTIMES.firstOrNull { it.id.equals(runtime, ignoreCase = true) }
                    ?: RUNTIMES.firstOrNull { it.displayName.equals(runtime, ignoreCase = true) }
            }
            .map { it.id }
            .toSet()
    return RUNTIMES.map { runtime ->
        SettingsComputerRuntimePill(
            id = runtime.id,
            label = runtime.displayName,
            detected = runtime.id in detectedIds,
            supported = runtime.supported
        )
    }
}

private fun settingsComputerRuntimePillRows(installedRuntimes: List<String>): List<List<SettingsComputerRuntimePill>> =
    settingsComputerRuntimePills(installedRuntimes).chunked(2)

@Composable
private fun VisualSectionHeader(title: String, count: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title.uppercase(),
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        count?.let {
            Text(
                it.toString(),
                style = SlockType.Input.copy(fontSize = 10.sp, lineHeight = 12.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
private fun VisualSmallActionButton(
    label: String,
    icon: SlockLucideIconName,
    backgroundColor: Color,
    faceWidth: Dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shadowOffset = 2.dp
    Box(
        modifier = Modifier.width(faceWidth + shadowOffset).height(28.dp)
    ) {
        Box(
            modifier = Modifier
                .width(faceWidth)
                .height(26.dp)
                .offset(x = shadowOffset, y = shadowOffset)
                .background(SlockColor.ShadowBlack)
        )
        Row(
            modifier = Modifier
                .width(faceWidth)
                .height(26.dp)
                .background(backgroundColor)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SlockLucideIcon(name = icon, modifier = Modifier.size(12.dp), color = SlockColor.TextBlackArgb)
            Text(
                label,
                style = SlockType.Button.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun VisualAgentRow(
    item: KuiklySettingsAgentItem,
    status: SlockAgentStatusIndicatorState?,
    onClick: (() -> Unit)?
) {
    val displayName = item.displayLabel()
    val subtitle = item.subtitleLabel()
    val activityLabel = item.activityDisplayLabel(status)
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SlockAvatar(
            avatarWireValue = item.avatarWireValue,
            label = displayName,
            avatarSize = 34.dp,
            borderWidth = SlockStroke.Default
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                displayName,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    it,
                    style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SlockStatusIndicator(
                color = status?.color ?: SlockColor.BrandYellow,
                size = 10.dp,
                pulsing = status?.pulsing == true
            )
            activityLabel?.let {
                Text(
                    it,
                    style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VisualWorkspaceRow(item: KuiklySettingsWorkspaceItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            item.name,
            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            item.path,
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        listOf(item.statusLabel, item.lastActiveLabel).filterNotNull().filter { it.isNotBlank() }.joinToString("  ").takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VisualActionPanel(
    title: String,
    description: String,
    label: String,
    icon: SlockLucideIconName,
    backgroundColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Text(
            description,
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        VisualSmallActionButton(
            label = label,
            icon = icon,
            onClick = onClick,
            backgroundColor = backgroundColor,
            faceWidth = 116.dp,
            enabled = enabled
        )
    }
}

@Composable
private fun VisualDivider() {
    Box(
        modifier = Modifier.fillMaxWidth().height(1.dp).background(SlockColor.EmptyStateGray)
    )
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

private fun visualSettingsComputersPayload(): KuiklySettingsComputersPayload =
    KuiklySettingsComputersPayload(
        instanceId = "visual-settings-computers",
        status = "content",
        computers =
            listOf(
                visualPrimaryComputer(),
                KuiklySettingsComputerItem(
                    id = "computer-studio",
                    name = "Studio Test Rig",
                    daemonVersion = "0.62.0",
                    online = false,
                    hostname = "studio-test-rig.local",
                    platform = "linux",
                    lastSeenLabel = "Jun 26, 2026, 4:10 PM",
                    runtimes = listOf("codex"),
                    createdAt = "Jun 19, 2026",
                    apiKeyPrefix = "slock_lab",
                    statusVersion = 3
                )
            ),
        canAddComputer = true,
        addComputerUnavailableReason = null,
        latestDaemonVersion = "0.63.7",
        latestComputerVersion = "0.0.62"
    )

private fun visualSettingsComputerDetailPayload(
    timeContext: SlockTimeContext?
): KuiklySettingsComputerDetailPayload =
    SettingsComputerDetailSnapshot(
        computer =
            SettingsComputerSnapshot(
                id = "computer-mbp",
                name = "Jiachengs-MacBook-Pro",
                daemonVersion = "0.0.50",
                computerVersion = "0.0.50",
                isComputer = true,
                online = true,
                hostname = "Jiachengs-MacBook-Pro.local",
                platform = "darwin",
                lastSeenLabel = "Active now",
                runtimes = listOf("Claude Code", "Codex CLI"),
                createdAt = "Jun 18, 2026",
                lastHeartbeat = "2026-06-19T12:28:00.000Z",
                apiKeyPrefix = "slock_vis",
                statusVersion = 7L
            ),
        workspaces = emptyList(),
        agents =
            listOf(
                SettingsComputerAgentSnapshot(
                    id = "agent-product-ux",
                    name = "Product UX Designer",
                    statusLabel = "online",
                    runtime = "codex",
                    avatarUrl = "pixel:paint",
                    description = "Reviews visual parity across surfaces."
                )
            ),
        sections = emptyList(),
        canManageMachines = true
    ).toComputerDetailPayload(timeContext).let { payload ->
        payload.copy(
            agents =
                payload.agents.map { agent ->
                    if (agent.id == "agent-product-ux") {
                        agent.copy(presenceLabel = "online", activityLabel = "Online")
                    } else {
                        agent
                    }
                }
        )
    }

private fun visualPrimaryComputer(): KuiklySettingsComputerItem =
    KuiklySettingsComputerItem(
        id = "computer-mbp",
        name = "Jiachengs-MacBook-Pro.local",
        daemonVersion = "0.63.7",
        computerVersion = "0.0.50",
        isComputer = true,
        online = true,
        hostname = "Jiachengs-MacBook-Pro.local",
        platform = "darwin",
        lastSeenLabel = "Active now",
        runtimes = listOf("codex", "claude"),
        createdAt = "Jun 18, 2026",
        apiKeyPrefix = "slock_vis",
        statusVersion = 7
    )

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
        latestDaemonVersion = latestDaemonVersion,
        latestComputerVersion = latestComputerVersion
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
        computerVersion = computerVersion,
        isComputer = isComputer,
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
    val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
    val runtimeLabel =
        settingsComputerRuntimeDisplayName(runtime)
            ?: profile?.roleDescription?.takeIf { it.isNotBlank() }
    return KuiklySettingsAgentItem(
        id = id,
        name = name,
        statusLabel = statusLabel,
        ownerLabel = ownerLabel,
        runtimeLabel = runtimeLabel,
        avatarWireValue = profile?.avatarWireValueForAvatarSlot() ?: avatarUrl?.takeIf { it.isNotBlank() },
        displayName = displayName,
        handle = profile?.handle,
        description = profile?.description ?: description,
        presenceLabel = profile?.presence?.name,
        roleLabel = profile?.roleDescription,
        activityLabel = statusLabel?.toSettingsAgentStatusLabel() ?: profile?.agentStatus?.label
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
private fun SettingsComputerCard(
    item: KuiklySettingsComputerItem,
    latestComputerVersion: String?,
    onClick: (() -> Unit)?
) {
    // #77: align the Settings Computers list card with React's
    // `MobileComputersPanel.tsx` (107 行) and `Sidebar.tsx` line 1248-1290 list
    // pattern — each card shows name + status dot + run-kind version line.
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
                val runLabel = settingsComputerRunLabel(item)
                val versionWarning =
                    settingsComputerVersionWarning(
                        currentVersion = item.computerVersion,
                        latestVersion = latestComputerVersion,
                        isComputer = item.isComputer
                    )
                Text(
                    text = runLabel.text,
                    style = SlockType.Input.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = if (runLabel.isOffline) SlockColor.EmptyStateGray else SlockColor.TextMutedGray,
                        fontStyle = if (runLabel.isOffline) FontStyle.Italic else null
                    ),
                    color = if (runLabel.isOffline) SlockColor.EmptyStateGray else SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                versionWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = SlockType.Input.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = SlockColor.AccentOrange,
                            fontWeight = FontWeight.Bold
                        ),
                        color = SlockColor.AccentOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private data class SettingsComputerRunLabel(
    val text: String,
    val isOffline: Boolean
)

private fun settingsComputerRunLabel(item: KuiklySettingsComputerItem): SettingsComputerRunLabel {
    val kind = if (item.isComputer) "computer" else "daemon"
    if (!item.online) return SettingsComputerRunLabel("$kind offline", isOffline = true)
    val version =
        if (item.isComputer) item.computerVersion?.takeIf { it.isNotBlank() }
        else item.daemonVersion.takeIf { it.isNotBlank() }
    return if (version != null) {
        SettingsComputerRunLabel("$kind v$version", isOffline = false)
    } else {
        SettingsComputerRunLabel("$kind online", isOffline = false)
    }
}

private fun settingsComputerVersionWarning(
    currentVersion: String?,
    latestVersion: String?,
    isComputer: Boolean
): String? {
    if (!isComputer || !isSettingsComputerOutdated(currentVersion, latestVersion)) return null
    return "v${currentVersion?.trim()} -> v${latestVersion?.trim()}"
}

private val SettingsComputerSemverTriple = Regex("""^\d+\.\d+\.\d+$""")

private fun isSettingsComputerOutdated(currentVersion: String?, latestVersion: String?): Boolean {
    val current = currentVersion.settingsComputerVersionTripleOrNull() ?: return false
    val latest = latestVersion.settingsComputerVersionTripleOrNull() ?: return false
    for (index in current.indices) {
        if (current[index] < latest[index]) return true
        if (current[index] > latest[index]) return false
    }
    return false
}

private fun String?.settingsComputerVersionTripleOrNull(): List<Int>? {
    val value = this?.trim()?.removePrefix("v")?.takeIf { SettingsComputerSemverTriple.matches(it) } ?: return null
    return value.split('.').map { it.toInt() }
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
    SlockStatusIndicator(color = if (online) SlockColor.AccentLime else SlockColor.EmptyStateGray)
}

internal fun KuiklySettingsAgentItem.settingsAgentFallbackStatusState(): SlockAgentStatusIndicatorState =
    slockAgentStatusIndicatorState(
        statusValue = statusLabel,
        fallbackPresence = presenceLabel.toSettingsAgentPresenceFallback()
    )

private fun KuiklySettingsAgentItem.displayLabel(): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: name.takeIf { it.isNotBlank() }
        ?: handle?.takeIf { it.isNotBlank() }
        ?: "Agent"

private fun KuiklySettingsAgentItem.subtitleLabel(): String? =
    runtimeLabel?.takeIf { it.isNotBlank() }
        ?: roleLabel?.takeIf { it.isNotBlank() }
        ?: handle?.takeIf { it.isNotBlank() }?.let { "@$it" }

private fun KuiklySettingsAgentItem.activityDisplayLabel(status: SlockAgentStatusIndicatorState?): String? =
    status?.status?.label
        ?: activityLabel?.toSettingsAgentStatusLabel()
        ?: statusLabel?.toSettingsAgentStatusLabel()

private fun settingsComputerRuntimeDisplayName(runtime: String?): String? {
    val normalized = runtime?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return RUNTIMES.firstOrNull { info ->
        info.id.equals(normalized, ignoreCase = true) ||
            info.displayName.equals(normalized, ignoreCase = true) ||
            info.binary.equals(normalized, ignoreCase = true)
    }?.displayName ?: normalized
}

private fun String.toSettingsAgentStatusLabel(): String =
    trim()
        .replace('-', ' ')
        .replace('_', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.replaceFirstChar { char -> char.uppercase() }
        }
        .ifBlank { this }

private fun String?.toSettingsAgentPresenceFallback(): Presence =
    when (this?.trim()?.lowercase()) {
        "online", "active", "connected" -> Presence.Active
        "idle", "away", "busy" -> Presence.Away
        "offline", "disconnected", "not connected", "inactive" -> Presence.Offline
        else -> Presence.Offline
    }

@Composable
private fun VisualInstalledRuntimePill(text: String) {
    Box(
        modifier = Modifier.heightIn(min = 22.dp).background(SlockColor.AccentCyan).border(SlockStroke.Default, SlockColor.BorderBlack).padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = SlockType.Input.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack, maxLines = 1)
    }
}

@Composable
private fun MissingRuntimePill(text: String) {
    Box(
        modifier = Modifier.heightIn(min = 22.dp).background(SlockColor.EmptyStateGray).border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f)).padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = SlockType.Input.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray, maxLines = 1)
    }
}
