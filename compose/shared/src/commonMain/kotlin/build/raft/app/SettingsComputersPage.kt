package build.raft.app

import build.raft.app.base.Utils
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalCard
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SettingsComputerDescriptionUpdateRequest
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerRenameRequest
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerWorkspaceSnapshot
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.formatAttachmentSizeLabel
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
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import com.tencent.kuikly.compose.ui.text.font.FontFamily
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
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.resource.format
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

const val SETTINGS_COMPUTERS_PAGE = "compose_settings_computers"
const val SETTINGS_COMPUTER_DETAIL_PAGE = "compose_settings_computer_detail"

private const val SettingsCreateAgentErrorEmpty = "settings_computers:create_agent_empty"
private const val SettingsCreateAgentErrorForbidden = "settings_computers:create_agent_forbidden"
private const val SettingsCreateAgentErrorFailedPrefix = "settings_computers:create_agent_failed:"

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
    val status: String = "content",
    val computer: KuiklySettingsComputerItem = KuiklySettingsComputerItem(),
    val workspaces: List<KuiklySettingsWorkspaceItem> = emptyList(),
    val workspacesScanned: Boolean = false,
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
    val description: String? = null,
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
    val lastActiveLabel: String? = null,
    val totalSizeLabel: String? = null,
    val fileCount: Int? = null,
    val fileCountLabel: String? = null
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
    if (visualFixture == "computers" || visualFixture == "computers-loading") {
        Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
            SettingsComputersListHeader(onBack = {}, onAddComputer = {})
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SettingsComputersContent(
                    if (visualFixture == "computers-loading") {
                        visualSettingsComputersLoadingPayload()
                    } else {
                        visualSettingsComputersPayload()
                    }
                ) { _, _ -> }
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
        settingsViewModel.dispatch(SharedSettingsIntent.RefreshComputers)
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    val computersPayload =
        settingsState.computers.toComputersPayload(
            refreshing = settingsState.isRefreshing(SharedSettingsDestination.Computers),
            timeContext = timeContext
        )
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SettingsComputersListHeader(
            onBack = { pager.closeSettingsComputersPage() },
            onAddComputer =
                if (computersPayload.canAddComputer) {
                    { settingsViewModel.dispatch(SharedSettingsIntent.ShowAddComputerDialog) }
                } else {
                    null
                }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SettingsComputersContent(computersPayload) { action, itemId ->
                when (action) {
                    "openComputer" ->
                        itemId?.takeIf { it.isNotBlank() }?.let {
                            pager.openSettingsComputerDetailPage(it, timeContext, environment.storeScope)
                        }
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
    val serverId = currentServerId.orEmpty()
    val computersSlice by environment.computersStore
        .computerPickerSliceFlow(serverId)
        .collectAsState(SharedComputersPickerSlice())
    val createAgentScope = rememberCoroutineScope()
    var createAgentForComputerOpen by remember(computerId) { mutableStateOf(false) }
    var createAgentSubmitting by remember { mutableStateOf(false) }
    var createAgentError by remember { mutableStateOf<String?>(null) }
    var createAgentRuntimeModels by remember(serverId) {
        mutableStateOf<Map<Pair<String, String>, SharedCreateAgentRuntimeModelCatalog>>(emptyMap())
    }
    var createAgentRuntimeModelLoadsInFlight by remember(serverId) {
        mutableStateOf<Set<Pair<String, String>>>(emptySet())
    }
    val loadCreateAgentMachines: () -> Unit = {
        if (serverId.isNotBlank()) {
            environment.computersStore.dispatch(SharedComputersIntent.EnsureComputerPicker(serverId))
        } else {
            bridge.log("settings_computer_detail_create_agent_skip reason=missing_server_id computerId=$computerId")
        }
    }
    LaunchedEffect(createAgentForComputerOpen, serverId) {
        if (createAgentForComputerOpen) {
            loadCreateAgentMachines()
        }
    }
    val loadCreateAgentRuntimeModels: (String, String, Boolean) -> Unit = { machineId, runtime, force ->
        val key = machineId.trim() to runtime.trim()
        if (serverId.isBlank() || key.first.isBlank() || key.second.isBlank()) {
            // no-op: the dialog keeps using the static runtime catalog fallback
        } else if (!force && (key in createAgentRuntimeModels || key in createAgentRuntimeModelLoadsInFlight)) {
            // no-op: cached or already loading
        } else {
            createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight + key
            createAgentScope.launch {
                when (val result = environment.createAgentCoordinator.loadRuntimeModels(serverId, key.first, key.second)) {
                    is DataResult.Content -> {
                        createAgentRuntimeModels = createAgentRuntimeModels + (key to result.data)
                    }
                    is DataResult.Empty -> Unit
                    is DataResult.Error ->
                        bridge.log("create_agent_runtime_models_failed source=settings_computer_detail code=${result.error.code}")
                    is DataResult.NoPermission ->
                        bridge.log("create_agent_runtime_models_blocked source=settings_computer_detail code=${result.blocker.code}")
                }
                createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight - key
            }
        }
    }
    LaunchedEffect(computerId) {
        bridge.log("settings_computer_detail_open instanceId=${launchPayload.instanceId} computerId=$computerId")
        settingsViewModel.dispatch(SharedSettingsIntent.OpenComputer(computerId))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    SettingsComputerDetailRouteShell(
        title = settingsState.computerDetail.localizedCurrentComputerTitle(),
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
                    if (fallbackAction == "createAgentForComputer") {
                        createAgentError = null
                        createAgentForComputerOpen = true
                        loadCreateAgentMachines()
                    } else {
                        bridge.log("settings_computer_detail_unsupported_action action=$fallbackAction itemId=${fallbackItem.orEmpty()}")
                    }
                }
            )
        }
    }
    if (createAgentForComputerOpen) {
        val localizedCreateAgentError = localizedSettingsCreateAgentError(createAgentError)
        SharedCreateAgentDialog(
            submitting = createAgentSubmitting,
            errorMessage = localizedCreateAgentError,
            requiredComputer = computerId,
            machines = computersSlice.machines,
            machinesLoading = computersSlice.status == SharedComputersLoadStatus.InFlight,
            machinesErrorMessage =
                computersSlice.errorCode?.let { code ->
                    MR.strings.settings_computers_create_agent_error_load_computers.format(listOf(code)).localized()
                },
            runtimeModelsByMachineAndRuntime = createAgentRuntimeModels,
            runtimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight,
            onRuntimeModelsRequest = loadCreateAgentRuntimeModels,
            currentAgentCount = settingsState.computerDetail.toComputerDetailPayload(computerId, timeContext).agents.size,
            onDismiss = {
                if (!createAgentSubmitting) {
                    createAgentForComputerOpen = false
                    createAgentError = null
                }
            },
            onSubmit = { request ->
                createAgentSubmitting = true
                createAgentError = null
                createAgentScope.launch {
                    val result = environment.createAgentCoordinator.createAgent(request.toCreateAgentRequest())
                    createAgentSubmitting = false
                    when (result) {
                        is DataResult.Content -> {
                            createAgentForComputerOpen = false
                            createAgentError = null
                            settingsViewModel.dispatch(SharedSettingsIntent.OpenComputer(computerId))
                        }
                        is DataResult.Empty -> {
                            createAgentError = SettingsCreateAgentErrorEmpty
                        }
                        is DataResult.Error -> {
                            createAgentError = SettingsCreateAgentErrorFailedPrefix + result.error.code
                        }
                        is DataResult.NoPermission -> {
                            createAgentError = SettingsCreateAgentErrorForbidden
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun SettingsComputersListHeader(
    onBack: () -> Unit,
    onAddComputer: (() -> Unit)?
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SlockSize.PageHeaderHeight)
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
        Text(
            stringResource(MR.strings.settings_computers_title),
            style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        onAddComputer?.let { onClick ->
            SlockBrutalIconButton(
                iconName = SlockLucideIconName.Plus,
                onClick = onClick,
                size = 30.dp,
                shadowOffset = 2.dp
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
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
            subtitle = stringResource(MR.strings.settings_computers_detail_subtitle),
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
        "retryWorkspaces", "scanWorkspaces" ->
            computerId.takeIf { it.isNotBlank() }?.let { dispatch(SharedSettingsIntent.ScanComputerWorkspaces(it)) }
        "retryAgents" ->
            computerId.takeIf { it.isNotBlank() }?.let { dispatch(SharedSettingsIntent.OpenComputer(it)) }
        "renameComputer" -> itemId?.takeIf { it.isNotBlank() }?.let { payload ->
            val parts = payload.split("|", limit = 2)
            val machineId = parts[0]
            val name = parts.getOrElse(1) { "" }
            dispatch(SharedSettingsIntent.RenameComputer(SettingsComputerRenameRequest(machineId = machineId, name = name)))
        }
        "updateComputerDescription" -> itemId?.let { payload ->
            val parts = payload.split("|", limit = 2)
            val machineId = parts[0]
            val description = parts.getOrElse(1) { "" }
            dispatch(
                SharedSettingsIntent.UpdateComputerDescription(
                    SettingsComputerDescriptionUpdateRequest(machineId = machineId, description = description)
                )
            )
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
    if (payload.isPageLevelLoading()) {
        SettingsComputersPageLoadingMessage(payload.localizedPageLevelLoadingMessage())
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (payload.computers.isEmpty()) {
            item {
                val presentation = payload.localizedStatusPresentation()
                SettingsComputerStatusMessage(
                    iconName = presentation.iconName,
                    title = presentation.title,
                    detail = presentation.detail,
                    accentColor = presentation.accentColor
                )
            }
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
            item {
                SettingsInfoStrip(
                    stringResource(MR.strings.settings_computers_add_computer),
                    localizedSettingsComputerErrorCopy(reason)
                )
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

internal fun KuiklySettingsComputersPayload.isPageLevelLoading(): Boolean =
    status == "loading" && computers.isEmpty()

internal fun KuiklySettingsComputersPayload.pageLevelLoadingMessage(): String =
    emptyTitle.ifBlank { "Loading computers" }

@Composable
private fun SettingsComputersPageLoadingMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            style = SlockType.Body.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
    }
}

internal data class SettingsComputersStatusPresentation(
    val iconName: SlockLucideIconName,
    val title: String,
    val detail: String?,
    val accentColor: String
)

internal fun KuiklySettingsComputersPayload.statusPresentation(): SettingsComputersStatusPresentation =
    when (status) {
        "loading" ->
            SettingsComputersStatusPresentation(
                iconName = SlockLucideIconName.LoaderCircle,
                title = emptyTitle.ifBlank { "Loading computers" },
                detail = emptyDetail.takeIf { it.isNotBlank() },
                accentColor = SlockColor.TextBlackArgb
            )
        "error" ->
            SettingsComputersStatusPresentation(
                iconName = SlockLucideIconName.TriangleAlert,
                title = emptyTitle.ifBlank { "Unable to load computers" },
                detail =
                    listOfNotNull(
                        emptyDetail.takeIf { it.isNotBlank() },
                        addComputerUnavailableReason?.takeIf { it.isNotBlank() }?.let { settingsComputerErrorCopy(it) }
                    ).joinToString(" ").ifBlank { null },
                accentColor = "#FFF97264"
            )
        "blocked", "noPermission" ->
            SettingsComputersStatusPresentation(
                iconName = SlockLucideIconName.Lock,
                title = emptyTitle.ifBlank { "No access to computers" },
                detail = emptyDetail.takeIf { it.isNotBlank() },
                accentColor = SlockColor.TextMutedGrayArgb
            )
        else ->
            SettingsComputersStatusPresentation(
                iconName = SlockLucideIconName.Monitor,
                title = emptyTitle.ifBlank { "No computers yet" },
                detail = emptyDetail.takeIf { it.isNotBlank() },
                accentColor = SlockColor.TextMutedGrayArgb
            )
    }

@Composable
private fun KuiklySettingsComputersPayload.localizedPageLevelLoadingMessage(): String =
    if (emptyTitle.isBlank() || emptyTitle == "Loading computers") {
        stringResource(MR.strings.settings_computers_loading)
    } else {
        emptyTitle
    }

@Composable
private fun KuiklySettingsComputersPayload.localizedStatusPresentation(): SettingsComputersStatusPresentation {
    val canonical = statusPresentation()
    val title =
        when (status) {
            "loading" -> stringResource(MR.strings.settings_computers_loading)
            "error" -> stringResource(MR.strings.settings_computers_error_load)
            "blocked", "noPermission" -> stringResource(MR.strings.settings_computers_no_access)
            else -> stringResource(MR.strings.settings_computers_empty)
        }
    val detail =
        when (emptyDetail) {
            "Fetching connected machines from this server." -> stringResource(MR.strings.settings_computers_loading_detail)
            "Pull to refresh or try again." -> stringResource(MR.strings.settings_computers_error_retry_detail)
            "Settings cannot load connected machines right now." -> stringResource(MR.strings.settings_computers_error_unavailable_detail)
            "You do not have permission to view connected machines." -> stringResource(MR.strings.settings_computers_no_access_detail)
            "Connect a desktop daemon to see it here." -> stringResource(MR.strings.settings_computers_empty_detail)
            else -> canonical.detail
        }
    return canonical.copy(title = title, detail = detail)
}

@Composable
private fun KuiklySettingsComputerDetailPayload.localizedComputerName(): String =
    when (status) {
        "loading" -> stringResource(MR.strings.settings_computers_detail_loading)
        "error" -> stringResource(MR.strings.settings_computers_detail_error_load)
        "blocked", "noPermission" -> stringResource(MR.strings.settings_computers_detail_no_access)
        "empty" -> stringResource(MR.strings.settings_computers_detail_unavailable)
        else ->
            computer.name.takeIf { it.isNotBlank() }
                ?: stringResource(MR.strings.settings_computers_detail_computer)
    }

@Composable
private fun localizedSettingsCreateAgentError(error: String?): String? =
    when {
        error.isNullOrBlank() -> null
        error == SettingsCreateAgentErrorEmpty ->
            stringResource(MR.strings.settings_computers_create_agent_error_empty)
        error == SettingsCreateAgentErrorForbidden ->
            stringResource(MR.strings.settings_computers_create_agent_error_forbidden)
        error.startsWith(SettingsCreateAgentErrorFailedPrefix) ->
            MR.strings.settings_computers_create_agent_error_failed
                .format(listOf(error.removePrefix(SettingsCreateAgentErrorFailedPrefix)))
                .localized()
        else -> error
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
    val localizedComputerName = payload.localizedComputerName()
    val renderPayload =
        if (localizedComputerName == computerName) {
            payload
        } else {
            payload.copy(computer = payload.computer.copy(name = localizedComputerName))
        }
    val renameBusy = actionState?.action == "renameComputer" && actionState.inProgress && actionState.itemId == computerId
    val descriptionBusy = actionState?.action == "updateComputerDescription" && actionState.inProgress && actionState.itemId == computerId
    val deleteBusy = actionState?.action == "deleteComputer" && actionState.inProgress && actionState.itemId == computerId
    val rotateBusy = actionState?.action == "rotateComputerKey" && actionState.inProgress && actionState.itemId == computerId
    val workspacesBusy = actionState?.action == "scanComputerWorkspaces" && actionState.inProgress && actionState.itemId == computerId
    val anyBusy = renameBusy || descriptionBusy || deleteBusy || rotateBusy
    var renameDraft by remember(computerId) { mutableStateOf(computerName) }
    var editingName by remember(computerId) { mutableStateOf(false) }
    var descriptionDraft by remember(computerId) { mutableStateOf(payload.computer.description.orEmpty()) }
    var editingDescription by remember(computerId) { mutableStateOf(false) }
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
    fun startDescriptionEdit() {
        descriptionDraft = payload.computer.description.orEmpty()
        editingDescription = true
    }
    fun cancelDescriptionEdit() {
        descriptionDraft = payload.computer.description.orEmpty()
        editingDescription = false
    }
    fun saveDescription() {
        val nextDescription = descriptionDraft.trim()
        if (nextDescription != payload.computer.description.orEmpty()) {
            onEvent("updateComputerDescription", "$computerId|$nextDescription")
        }
        if (!descriptionBusy) editingDescription = false
    }

    SettingsComputerDetailVisualContent(
        payload = renderPayload,
        actionState = actionState,
        agentStatusResolver = agentStatusResolver,
        productionMode = true,
        editingName = editingName,
        renameDraft = renameDraft,
        renameBusy = renameBusy,
        editingDescription = editingDescription,
        descriptionDraft = descriptionDraft,
        descriptionBusy = descriptionBusy,
        anyBusy = anyBusy,
        rotateBusy = rotateBusy,
        deleteBusy = deleteBusy,
        workspacesBusy = workspacesBusy,
        bottomContentPadding = bottomContentPadding,
        onRenameDraftChange = { renameDraft = it.replace('\n', ' ').replace('\r', ' ') },
        onStartRename = { startRename() },
        onCancelRename = { cancelRename() },
        onSaveRename = { saveRename() },
        onDescriptionDraftChange = { descriptionDraft = it.replace('\r', '\n').lineSequence().take(4).joinToString("\n") },
        onStartDescriptionEdit = { startDescriptionEdit() },
        onCancelDescriptionEdit = { cancelDescriptionEdit() },
        onSaveDescription = { saveDescription() },
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
    editingDescription: Boolean = false,
    descriptionDraft: String = payload.computer.description.orEmpty(),
    descriptionBusy: Boolean = false,
    anyBusy: Boolean = false,
    rotateBusy: Boolean = false,
    deleteBusy: Boolean = false,
    workspacesBusy: Boolean = false,
    bottomContentPadding: Dp = 24.dp,
    onRenameDraftChange: (String) -> Unit = {},
    onStartRename: () -> Unit = {},
    onCancelRename: () -> Unit = {},
    onSaveRename: () -> Unit = {},
    onDescriptionDraftChange: (String) -> Unit = {},
    onStartDescriptionEdit: () -> Unit = {},
    onCancelDescriptionEdit: () -> Unit = {},
    onSaveDescription: () -> Unit = {},
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
        item {
            VisualComputerDescriptionSection(
                description = payload.computer.description,
                canEdit = payload.canManageMachines,
                enabled = !anyBusy,
                editing = editingDescription,
                draft = descriptionDraft,
                busy = descriptionBusy,
                onDraftChange = onDescriptionDraftChange,
                onStartEdit = onStartDescriptionEdit,
                onCancel = onCancelDescriptionEdit,
                onSave = onSaveDescription
            )
        }
        item { VisualDivider() }
        item { VisualComputerInfoSection(payload.computer) }
        item { VisualDivider() }
        item { Spacer(Modifier.height(20.dp)) }
        item { VisualSectionHeader(stringResource(MR.strings.settings_computers_section_agents), payload.agents.size) }
        if (payload.canManageMachines) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VisualSmallActionButton(
                        label = stringResource(MR.strings.settings_computers_action_select),
                        icon = SlockLucideIconName.Check,
                        onClick = { onEvent("selectAgentsForComputer", computerId) },
                        backgroundColor = SlockColor.SurfaceWhite,
                        faceWidth = 72.dp
                    )
                    VisualSmallActionButton(
                        label = stringResource(MR.strings.settings_computers_action_create),
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
                    SettingsInlineError(stringResource(MR.strings.settings_computers_error_load_agents), error, payload.agentsRetryable) { onEvent("retryAgents", null) }
                }
            }
        } ?: if (payload.agents.isEmpty()) {
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SettingsEmptyMessage(
                        stringResource(MR.strings.settings_computers_empty_agents),
                        stringResource(MR.strings.settings_computers_empty_agents_detail)
                    )
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
            item {
                VisualSectionHeader(
                    title = stringResource(MR.strings.settings_computers_section_workspaces),
                    count = payload.workspaces.takeIf { payload.workspacesScanned }?.size,
                    action = {
                        VisualSmallActionButton(
                            label =
                                when {
                                    workspacesBusy -> stringResource(MR.strings.settings_computers_action_scanning)
                                    payload.workspacesScanned -> stringResource(MR.strings.settings_computers_action_rescan)
                                    else -> stringResource(MR.strings.settings_computers_action_scan)
                                },
                            icon = SlockLucideIconName.RefreshCw,
                            onClick = { onEvent("scanWorkspaces", null) },
                            backgroundColor = SlockColor.SurfaceWhite,
                            faceWidth = 76.dp,
                            enabled = !workspacesBusy
                        )
                    }
                )
            }
            payload.workspacesErrorCode?.let { error ->
                item {
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        SettingsInlineError(stringResource(MR.strings.settings_computers_error_load_workspaces), error, payload.workspacesRetryable) { onEvent("retryWorkspaces", null) }
                    }
                }
            } ?: if (!payload.workspacesScanned) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        SettingsEmptyMessage(
                            stringResource(MR.strings.settings_computers_workspace_scan_title),
                            stringResource(MR.strings.settings_computers_workspace_scan_detail)
                        )
                    }
                }
            } else if (payload.workspaces.isEmpty()) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        SettingsEmptyMessage(
                            stringResource(MR.strings.settings_computers_empty_workspaces),
                            stringResource(MR.strings.settings_computers_empty_workspaces_detail)
                        )
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
                item { VisualSectionHeader(stringResource(MR.strings.settings_computers_section_actions)) }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VisualActionPanel(
                            title = stringResource(MR.strings.settings_computers_rotate_key_title),
                            description = stringResource(MR.strings.settings_computers_rotate_key_detail),
                            label =
                                if (rotateBusy) {
                                    stringResource(MR.strings.settings_computers_rotate_key_busy)
                                } else {
                                    stringResource(MR.strings.settings_computers_rotate_key_action)
                                },
                            icon = SlockLucideIconName.RefreshCw,
                            backgroundColor = SlockColor.BrandYellow,
                            enabled = !anyBusy,
                            onClick = { onEvent("rotateComputerKey", computerId) }
                        )
                        VisualActionPanel(
                            title = stringResource(MR.strings.settings_computers_delete_title),
                            description = stringResource(MR.strings.settings_computers_delete_detail),
                            label =
                                if (deleteBusy) {
                                    stringResource(MR.strings.settings_computers_delete_busy)
                                } else {
                                    stringResource(MR.strings.settings_computers_delete_action)
                                },
                            icon = SlockLucideIconName.Trash2,
                            backgroundColor = SlockColor.AccentRed,
                            enabled = !anyBusy,
                            onClick = { onEvent("deleteComputer", computerId) }
                        )
                        actionState?.errorCode?.takeIf { it.isNotBlank() && actionState.itemId == computerId }?.let { code ->
                            SettingsInlineError(
                                title = localizedSettingsComputerErrorTitle(code),
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
                    if (item.online) {
                        stringResource(MR.strings.settings_computers_status_connected)
                    } else {
                        stringResource(MR.strings.settings_computers_status_offline)
                    },
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
                stringResource(MR.strings.settings_computers_section_name).uppercase(),
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
                label = stringResource(MR.strings.settings_computers_label_name),
                value = draft,
                placeholder = name,
                onValueChange = onDraftChange,
                required = true,
                enabled = !busy
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VisualSmallActionButton(
                    label =
                        if (busy) {
                            stringResource(MR.strings.settings_computers_action_saving)
                        } else {
                            stringResource(MR.strings.common_save)
                        },
                    icon = SlockLucideIconName.Save,
                    onClick = onSave,
                    backgroundColor = SlockColor.AccentPink,
                    faceWidth = 64.dp,
                    enabled = !busy && draft.trim().isNotBlank()
                )
                VisualSmallActionButton(
                    label = stringResource(MR.strings.common_cancel),
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
private fun VisualComputerDescriptionSection(
    description: String?,
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
    val currentDescription = description?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(MR.strings.settings_computers_section_description).uppercase(),
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
                label = stringResource(MR.strings.settings_computers_label_description),
                value = draft,
                placeholder = stringResource(MR.strings.settings_computers_placeholder_description),
                onValueChange = onDraftChange,
                optional = true,
                enabled = !busy,
                maxLines = 4,
                fieldMinHeight = 86.dp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                VisualSmallActionButton(
                    label =
                        if (busy) {
                            stringResource(MR.strings.settings_computers_action_saving)
                        } else {
                            stringResource(MR.strings.common_save)
                        },
                    icon = SlockLucideIconName.Save,
                    onClick = onSave,
                    backgroundColor = SlockColor.AccentPink,
                    faceWidth = 64.dp,
                    enabled = !busy
                )
                VisualSmallActionButton(
                    label = stringResource(MR.strings.common_cancel),
                    icon = SlockLucideIconName.X,
                    onClick = onCancel,
                    backgroundColor = SlockColor.SurfaceWhite,
                    faceWidth = 72.dp,
                    enabled = !busy
                )
            }
        } else {
            Text(
                currentDescription ?: stringResource(MR.strings.settings_computers_state_no_description),
                style =
                    SlockType.Input.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = if (currentDescription == null) SlockColor.TextMutedGray else SlockColor.TextBlack
                    ),
                color = if (currentDescription == null) SlockColor.TextMutedGray else SlockColor.TextBlack
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
            stringResource(MR.strings.settings_computers_section_info).uppercase(),
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        VisualInfoValue(
            stringResource(MR.strings.settings_computers_label_os),
            item.platform ?: stringResource(MR.strings.settings_computers_state_unknown)
        )
        VisualInfoValue(
            stringResource(MR.strings.settings_computers_label_version),
            item.daemonVersion.takeIf { it.isNotBlank() }?.let { "v$it" }
                ?: stringResource(MR.strings.settings_computers_state_unknown)
        )
        VisualRuntimeRows(item.runtimes)
        VisualInfoValue(
            stringResource(MR.strings.settings_computers_label_created),
            item.createdAt ?: stringResource(MR.strings.settings_computers_state_unknown)
        )
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
            stringResource(MR.strings.settings_computers_label_runtimes),
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
        runtimeRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { runtime ->
                    if (runtime.detected) {
                        VisualInstalledRuntimePill(runtime.label)
                    } else {
                        MissingRuntimePill(runtime.localizedMissingLabel())
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

@Composable
private fun SettingsComputerRuntimePill.localizedMissingLabel(): String =
    label +
        stringResource(
            if (supported) {
                MR.strings.agent_create_suffix_not_installed
            } else {
                MR.strings.agent_create_suffix_coming_soon
            }
        )

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
private fun VisualSectionHeader(
    title: String,
    count: Int? = null,
    action: (@Composable () -> Unit)? = null
) {
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
        if (action != null) {
            Spacer(Modifier.weight(1f))
            action()
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
    val fileCountLabel =
        item.fileCount?.let { count ->
            val resource =
                if (count == 1) {
                    MR.strings.settings_computers_file_count_one
                } else {
                    MR.strings.settings_computers_file_count_many
                }
            resource.format(listOf(count)).localized()
        } ?: item.fileCountLabel
    val metadata =
        listOf(
            item.totalSizeLabel,
            fileCountLabel,
            item.lastActiveLabel?.let { MR.strings.settings_computers_workspace_modified.format(listOf(it)).localized() }
        )
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString("  ")
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
            item.path.takeIf { it.isNotBlank() } ?: item.id,
            style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        listOf(item.statusLabel?.uppercase(), metadata).filterNotNull().filter { it.isNotBlank() }.joinToString("  ").takeIf { it.isNotBlank() }?.let {
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
                    daemonVersion = "0.65.0",
                    computerVersion = "0.0.50",
                    isComputer = true,
                    online = false,
                    hostname = null,
                    platform = "linux",
                    lastSeenLabel = "Jun 26, 2026, 4:10 PM",
                    runtimes = listOf("codex"),
                    createdAt = "Jun 19, 2026",
                    apiKeyPrefix = "slock_lab",
                    statusVersion = 3
                ),
                KuiklySettingsComputerItem(
                    id = "machine-legacy",
                    name = "Legacy daemon",
                    daemonVersion = "0.65.0",
                    isComputer = false,
                    online = true,
                    hostname = null,
                    platform = "linux",
                    lastSeenLabel = "Active now",
                    runtimes = listOf("claude"),
                    createdAt = "Jun 17, 2026",
                    apiKeyPrefix = "slock_legacy",
                    statusVersion = 4
                )
            ),
        canAddComputer = true,
        addComputerUnavailableReason = null,
        latestDaemonVersion = "0.65.0",
        latestComputerVersion = "0.0.50"
    )

private fun visualSettingsComputersLoadingPayload(): KuiklySettingsComputersPayload =
    KuiklySettingsComputersPayload(
        instanceId = "visual-settings-computers-loading",
        status = "loading",
        emptyTitle = "Loading computers",
        emptyDetail = "Fetching connected machines from this server."
    )

private fun visualSettingsComputerDetailPayload(
    timeContext: SlockTimeContext?
): KuiklySettingsComputerDetailPayload =
    SettingsComputerDetailSnapshot(
        computer =
            SettingsComputerSnapshot(
                id = "computer-mbp",
                name = "Jiachengs-MacBook-Pro",
                daemonVersion = "0.0.48",
                computerVersion = "0.0.48",
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
                ),
                SettingsComputerAgentSnapshot(
                    id = "agent-cindy",
                    name = "Cindy",
                    statusLabel = "online",
                    runtime = "codex",
                    avatarUrl = "pixel:robot",
                    description = "Keeps visual testing release notes aligned."
                )
            ),
        sections = emptyList(),
        canManageMachines = true
    ).toComputerDetailPayload(timeContext).let { payload ->
        payload.copy(
            agents =
                payload.agents.map { agent ->
                    if (agent.id == "agent-product-ux" || agent.id == "agent-cindy") {
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
        name = "Jiachengs-MacBook-Pro",
        daemonVersion = "0.65.0",
        computerVersion = "0.0.48",
        isComputer = true,
        online = true,
        hostname = null,
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
                status = "error",
                name = "Unable to load computer",
                workspacesErrorCode = error.code,
                workspacesRetryable = error.retryable,
                agentsErrorCode = error.code,
                agentsRetryable = error.retryable
            )
        is ScreenLoadState.NoPermission ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                status = "blocked",
                name = "No access to computer",
                workspacesErrorCode = blocker.code,
                workspacesRetryable = false,
                agentsErrorCode = blocker.code,
                agentsRetryable = false
            )
        is ScreenLoadState.Empty ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                status = "empty",
                name = "Computer unavailable"
            )
        ScreenLoadState.Loading ->
            loadingComputerDetailPayload(
                selectedComputerId = selectedComputerId,
                status = "loading",
                name = "Loading computer"
            )
    }

@Composable
private fun ScreenLoadState<SettingsComputerDetailSnapshot>.localizedCurrentComputerTitle(): String =
    when (this) {
        is ScreenLoadState.Content ->
            data.computer.name.takeIf { it.isNotBlank() }
                ?: stringResource(MR.strings.settings_computers_detail_computer)
        is ScreenLoadState.Error -> stringResource(MR.strings.settings_computers_detail_error_load)
        is ScreenLoadState.NoPermission -> stringResource(MR.strings.settings_computers_detail_no_access)
        is ScreenLoadState.Empty -> stringResource(MR.strings.settings_computers_detail_unavailable)
        ScreenLoadState.Loading -> stringResource(MR.strings.settings_computers_detail_computer)
    }

private fun SettingsComputerDetailSnapshot.toComputerDetailPayload(
    timeContext: SlockTimeContext?
): KuiklySettingsComputerDetailPayload =
    KuiklySettingsComputerDetailPayload(
        instanceId = "shared-shell-settings-computer-${computer.id}",
        computer = computer.toComputerItem(timeContext),
        workspaces = workspaces.map { it.toWorkspaceItem(timeContext) },
        workspacesScanned = workspacesScanned,
        agents = agents.map { it.toAgentItem() },
        canManageMachines = canManageMachines,
        workspacesErrorCode = workspacesError?.code,
        workspacesRetryable = workspacesError?.retryable ?: false,
        agentsErrorCode = agentsError?.code,
        agentsRetryable = agentsError?.retryable ?: false
    )

private fun loadingComputerDetailPayload(
    selectedComputerId: String?,
    status: String,
    name: String,
    workspacesErrorCode: String? = null,
    workspacesRetryable: Boolean = false,
    agentsErrorCode: String? = null,
    agentsRetryable: Boolean = false
): KuiklySettingsComputerDetailPayload =
    KuiklySettingsComputerDetailPayload(
        instanceId = "shared-shell-settings-computer-${selectedComputerId.orEmpty()}",
        status = status,
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
        description = description,
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
        lastActiveLabel = formatSettingsTimestampLabel(lastActiveLabel, timeContext),
        totalSizeLabel = totalSizeBytes?.let(::formatAttachmentSizeLabel),
        fileCount = fileCount,
        fileCountLabel = fileCount?.let { count -> "$count ${if (count == 1) "file" else "files"}" }
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
    timeContext: SlockTimeContext?,
    storeScope: build.raft.app.data.SlockShellStoreScope,
) {
    val id = computerId.takeIf { it.isNotBlank() } ?: return
    val pageData =
        settingsComputerDetailPageData(
            computerId = id,
            timeContext = timeContext,
            storeScope = storeScope,
        )
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(SETTINGS_COMPUTER_DETAIL_PAGE, pageData)
}

internal fun settingsComputerDetailPageData(
    computerId: String,
    timeContext: SlockTimeContext?,
    storeScope: build.raft.app.data.SlockShellStoreScope,
): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_COMPUTER_DETAIL_PAGE)
        put("instanceId", "settings-computer-detail-$computerId")
        put("computerId", computerId)
        put("machineId", computerId)
        put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        storeScope.initialServerId?.let { put(SLOCK_SHELL_SERVER_ID, it) }
        put("localOffsetMinutes", timeContext?.localOffsetMinutes ?: 0)
        timeContext?.todayEpochDay?.let { put("todayEpochDay", it) }
        timeContext?.nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeContext?.timeFormat ?: SlockTimeFormat24Hour)
    }

private fun Pager.closeSettingsComputerDetailPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
}

private fun Pager.closeSettingsComputersPage() {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
}

@Composable
private fun localizedSettingsComputerErrorTitle(code: String): String =
    when (code) {
        "computer_has_agents" -> stringResource(MR.strings.settings_computers_error_title_cannot_delete)
        "computer_bad_request" -> stringResource(MR.strings.settings_computers_error_title_request_rejected)
        "computer_offline_replay", "machine_offline_replay", "machine_offline" ->
            stringResource(MR.strings.settings_computers_error_title_offline)
        "server_forbidden" -> stringResource(MR.strings.settings_computers_error_title_permission)
        "auth_required" -> stringResource(MR.strings.settings_computers_error_title_session)
        else -> stringResource(MR.strings.settings_computers_error_title_action)
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
        contentPadding = 12.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box {
                IconBox(SlockLucideIconName.Monitor, SlockColor.BrandYellow)
                if (item.shouldShowComputerAttentionDot(latestComputerVersion)) {
                    SlockStatusIndicator(
                        color = SlockColor.AccentPink,
                        size = 9.dp,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.name,
                        style = SlockType.Body
                            .copy(fontSize = 14.sp, lineHeight = 18.sp, color = SlockColor.TextBlack)
                            .withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    OnlineDot(item.online)
                }
                val runLabel = settingsComputerRunLabel(item)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = runLabel.text,
                        style = SlockType.Input.copy(
                            fontFamily = SlockMonoFamily,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = if (runLabel.isOffline) SlockColor.EmptyStateGray else SlockColor.TextMutedGray,
                            fontStyle = if (runLabel.isOffline) FontStyle.Italic else null
                        ),
                        color = if (runLabel.isOffline) SlockColor.EmptyStateGray else SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    runLabel.upgradeText?.let { upgrade ->
                        Text(
                            text = upgrade,
                            style = SlockType.Input.copy(
                                fontFamily = SlockMonoFamily,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
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
}

private data class SettingsComputerRunLabel(
    val text: String,
    val isOffline: Boolean,
    val upgradeText: String? = null
)

@Composable
private fun settingsComputerRunLabel(item: KuiklySettingsComputerItem): SettingsComputerRunLabel {
    val kind =
        if (item.isComputer) {
            stringResource(MR.strings.settings_computers_kind_computer)
        } else {
            stringResource(MR.strings.settings_computers_kind_daemon)
        }
    val version =
        if (item.isComputer) item.computerVersion?.takeIf { it.isNotBlank() }
        else item.daemonVersion.takeIf { it.isNotBlank() }
    if (!item.online) {
        val upgradeText = version?.let { "-> v$it" }
        return SettingsComputerRunLabel(
            MR.strings.settings_computers_run_offline.format(listOf(kind)).localized(),
            isOffline = true,
            upgradeText = upgradeText
        )
    }
    return if (version != null) {
        SettingsComputerRunLabel(
            MR.strings.settings_computers_run_version.format(listOf(kind, version)).localized(),
            isOffline = false
        )
    } else {
        SettingsComputerRunLabel(
            MR.strings.settings_computers_run_online.format(listOf(kind)).localized(),
            isOffline = false
        )
    }
}

private fun KuiklySettingsComputerItem.shouldShowComputerAttentionDot(latestComputerVersion: String?): Boolean =
    isComputer && !online && latestComputerVersion?.isNotBlank() == true

@Composable
private fun SettingsInlineError(title: String, errorCode: String, retryable: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(localizedSettingsComputerErrorCopy(errorCode), style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        if (retryable) {
            SettingsPrimaryButton(stringResource(MR.strings.common_retry), onRetry)
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
private fun localizedSettingsComputerErrorCopy(code: String): String =
    when (code) {
        "machine_offline" -> stringResource(MR.strings.settings_computers_error_offline)
        "machine_offline_replay" -> stringResource(MR.strings.settings_computers_error_replica)
        "computer_has_agents" -> stringResource(MR.strings.settings_computers_error_has_agents)
        "computer_bad_request" -> stringResource(MR.strings.settings_computers_error_bad_request)
        "server_forbidden" -> stringResource(MR.strings.settings_computers_error_forbidden)
        "auth_required" -> stringResource(MR.strings.settings_computers_error_auth)
        "server_unavailable" -> stringResource(MR.strings.settings_computers_error_server_unavailable)
        else -> MR.strings.settings_computers_error_code.format(listOf(code)).localized()
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
private fun SettingsComputerStatusMessage(
    iconName: SlockLucideIconName,
    title: String,
    detail: String?,
    accentColor: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Default, SlockColor.BorderBlack).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(17.dp), color = accentColor)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
            }
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
        modifier = Modifier.size(40.dp).background(color).border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(name = iconName, modifier = Modifier.size(20.dp))
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
    SlockStatusIndicator(color = if (online) SlockColor.AccentLime else SlockColor.StatusNeutralGray)
}

internal fun KuiklySettingsAgentItem.settingsAgentFallbackStatusState(): SlockAgentStatusIndicatorState =
    slockAgentStatusIndicatorState(
        statusValue = statusLabel,
        fallbackPresence = presenceLabel.toSettingsAgentPresenceFallback()
    )

@Composable
private fun KuiklySettingsAgentItem.displayLabel(): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: name.takeIf { it.isNotBlank() }
        ?: handle?.takeIf { it.isNotBlank() }
        ?: stringResource(MR.strings.thread_member_role_agent)

private fun KuiklySettingsAgentItem.subtitleLabel(): String? =
    runtimeLabel?.takeIf { it.isNotBlank() }
        ?: roleLabel?.takeIf { it.isNotBlank() }
        ?: handle?.takeIf { it.isNotBlank() }?.let { "@$it" }

private fun KuiklySettingsAgentItem.activityDisplayLabel(status: SlockAgentStatusIndicatorState?): String? =
    status?.label
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
