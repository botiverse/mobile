package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.AgentResetMode
import build.raft.app.data.AgentResetRequest
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRepository
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftBulkModelTarget
import build.raft.app.data.RaftBulkPlanAgent
import build.raft.app.data.RaftBulkPlanRow
import build.raft.app.data.RaftBulkSkipReason
import build.raft.app.data.RaftBulkUnfinishedPhase
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.SharedCreateAgentCoordinator
import build.raft.app.data.buildRaftBulkAgentModelUpdateBody
import build.raft.app.data.classifyRaftBulkUnfinished
import build.raft.app.data.isRaftBulkModelSelectableRuntime
import build.raft.app.data.planRaftBulkModelChange
import build.raft.app.data.raftBulkReasoningEffortOptions
import build.raft.app.data.raftBulkReconcileReasoningEffort
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.getModelLabel
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftCheckboxRow
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftSelect
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Web `MachineAgentList` bulk selection on a Computer's page (botiverse/slock
 * `packages/web/src/features/computer/MachineAgentList.tsx`): Select puts a checkbox on every agent
 * row, and the selection's toolbar starts, stops (after a confirm), restarts / resets (with the
 * reset options) or changes the model of the selected agents. Each agent gets its own request,
 * the same ones its own page sends; there is no bulk endpoint.
 */
internal enum class RaftComputerBulkAction { Start, Stop, Restart, ResetSession, ResetFull }

private val RaftComputerBulkAction.resetMode: AgentResetMode?
    get() =
        when (this) {
            RaftComputerBulkAction.Restart -> AgentResetMode.Restart
            RaftComputerBulkAction.ResetSession -> AgentResetMode.Session
            RaftComputerBulkAction.ResetFull -> AgentResetMode.Full
            else -> null
        }

/** The per-agent requests (the agent page's start / stop / reset, and its `PATCH /agents/:id`). */
internal class RaftComputerBulkAgentActions(
    private val memberRepository: MemberRepository,
    private val agentsApi: RaftAgentsApiClient,
    private val modelCatalog: SharedCreateAgentCoordinator,
    val serverId: String,
    val machineId: String,
) {
    /** Null when it went through; otherwise the failure (with its trace ID). */
    suspend fun run(agentId: String, action: RaftComputerBulkAction): ScreenError? {
        val id = MemberId(agentId)
        val result =
            when (action) {
                RaftComputerBulkAction.Start -> memberRepository.startAgent(DataRequestPriority.Foreground, id)
                RaftComputerBulkAction.Stop -> memberRepository.stopAgent(DataRequestPriority.Foreground, id)
                else -> memberRepository.resetAgent(DataRequestPriority.Foreground, AgentResetRequest(id, action.resetMode ?: AgentResetMode.Restart))
            }
        return when (result) {
            is DataResult.Content -> if (result.data.success) null else ScreenError(code = "agent_bulk_action_failed")
            else -> result.failure()
        }
    }

    suspend fun changeModel(agent: SettingsComputerAgentSnapshot, target: RaftBulkModelTarget, restart: Boolean): ScreenError? =
        when (val result = agentsApi.updateAgent(MemberId(agent.id), buildRaftBulkAgentModelUpdateBody(agent, target, restart))) {
            is DataResult.Content -> null
            else -> result.failure()
        }

    suspend fun restart(agentId: String): ScreenError? = run(agentId, RaftComputerBulkAction.Restart)

    /** The agent's saved runtime, model and effort as the server has them now; null when unread. */
    suspend fun saved(agentId: String): RaftBulkModelTarget? =
        (agentsApi.agent(MemberId(agentId)) as? DataResult.Content)?.data?.let { fresh ->
            val runtime = fresh.runtime ?: return@let null
            RaftBulkModelTarget(runtime, fresh.model.orEmpty(), fresh.reasoning?.takeIf { it.isNotBlank() })
        }

    suspend fun models(runtime: String): RaftBulkModelCatalog? =
        (modelCatalog.loadRuntimeModels(serverId, machineId, runtime) as? DataResult.Content)?.data?.let { catalog ->
            RaftBulkModelCatalog(
                models = catalog.models.map { RaftBulkModelOption(it.id, it.label, it.supportedReasoningEfforts, it.defaultReasoningEffort) },
                defaultModel = catalog.defaultModel,
            )
        }

    private fun DataResult<*>.failure(): ScreenError =
        when (this) {
            is DataResult.Error -> error
            is DataResult.NoPermission -> ScreenError(code = "agent_bulk_action_forbidden", traceId = blocker.traceId)
            else -> ScreenError(code = "agent_bulk_action_failed")
        }
}

internal data class RaftBulkModelOption(
    val id: String,
    val label: String,
    val supportedReasoningEfforts: List<String>,
    val defaultReasoningEffort: String?,
)

internal data class RaftBulkModelCatalog(val models: List<RaftBulkModelOption>, val defaultModel: String?)

/** The last bulk Change model on this page (web `bulkChangeModelReceiptStore`). */
internal sealed class RaftComputerBulkReceipt {
    abstract val target: RaftBulkModelTarget

    data class Success(override val target: RaftBulkModelTarget, val changed: Int, val skipped: Int) : RaftComputerBulkReceipt()

    data class Partial(
        override val target: RaftBulkModelTarget,
        val total: Int,
        val unfinished: Map<String, RaftBulkUnfinishedPhase>,
        val traceId: String?,
    ) : RaftComputerBulkReceipt()
}

/** What the Change model dialog is opened for: the agents, and a partial receipt's target to retry. */
internal data class RaftComputerBulkModelRequest(
    val agentIds: List<String>,
    val retry: RaftComputerBulkReceipt.Partial? = null,
)

/** Web: the success banner shows for 5 s. */
private const val RAFT_BULK_SUCCESS_BANNER_MS = 5_000L

/**
 * The page's bulk selection. It lives on the detail body, outside the lazy list, so scrolling the
 * agents section away does not drop the selection or a receipt.
 */
@Stable
internal class RaftComputerAgentsBulkState {
    var selectionMode by mutableStateOf(false)
    var selected by mutableStateOf(emptySet<String>())
    /** The lifecycle action in flight, if any (web `bulkAction`). */
    var busy by mutableStateOf<RaftComputerBulkAction?>(null)
    /** "N of M agent actions failed" (web `bulkError`), with the first failure's trace ID. */
    var failure by mutableStateOf<RaftComputerBulkFailure?>(null)
    var resetOptionsOpen by mutableStateOf(false)
    var resetAction by mutableStateOf(RaftComputerBulkAction.Restart)
    var stopConfirmOpen by mutableStateOf(false)
    var modelRequest by mutableStateOf<RaftComputerBulkModelRequest?>(null)
    var receipt by mutableStateOf<RaftComputerBulkReceipt?>(null)

    fun toggle(agentId: String) {
        selected = if (agentId in selected) selected - agentId else selected + agentId
    }

    fun clearSelection() {
        selectionMode = false
        selected = emptySet()
        failure = null
    }
}

internal data class RaftComputerBulkFailure(val failed: Int, val total: Int, val traceId: String?)

/** Web `displayState.isOnline`: the agent runs (any live activity but offline). */
internal fun RaftAgentStatusIndicatorState?.raftComputerAgentRunning(): Boolean =
    when (this?.status) {
        null, AgentStatus.Offline, AgentStatus.Disconnected -> false
        else -> true
    }

/** Everything the agents section needs for bulk selection; null for a viewer who can't manage. */
internal class RaftComputerAgentsBulk(
    val state: RaftComputerAgentsBulkState,
    val computerOnline: Boolean,
    val running: (String) -> Boolean,
    val onStart: () -> Unit,
    val onRetryModel: () -> Unit,
)

/** Runs [action] on the selected agents it applies to, all at once (web `runBulkAction`). */
internal fun RaftComputerAgentsBulkState.runLifecycle(
    scope: CoroutineScope,
    actions: RaftComputerBulkAgentActions,
    agents: List<SettingsComputerAgentSnapshot>,
    running: (String) -> Boolean,
    action: RaftComputerBulkAction,
    onDone: () -> Unit,
) {
    val chosen = agents.filter { it.id in selected }
    val targets =
        when (action) {
            RaftComputerBulkAction.Start -> chosen.filterNot { running(it.id) }
            RaftComputerBulkAction.Stop -> chosen.filter { running(it.id) }
            else -> chosen
        }
    if (targets.isEmpty() || busy != null) return
    busy = action
    failure = null
    scope.launch {
        try {
            val errors = coroutineScope { targets.map { agent -> async { actions.run(agent.id, action) } }.awaitAll() }.filterNotNull()
            if (errors.isEmpty()) {
                clearSelection()
                resetOptionsOpen = false
            } else {
                failure = RaftComputerBulkFailure(errors.size, targets.size, errors.firstNotNullOfOrNull { it.traceId })
            }
            stopConfirmOpen = false
            onDone()
        } finally {
            busy = null
        }
    }
}

/** The header's Select, or in select mode Select all / Clear selection and Cancel. */
@Composable
internal fun RaftComputerAgentsSelectButtons(state: RaftComputerAgentsBulkState, agentIds: List<String>) {
    if (state.selectionMode) {
        val allSelected = agentIds.isNotEmpty() && agentIds.all { it in state.selected }
        RaftButton(
            text = stringResource(if (allSelected) Res.string.settings_computers_bulk_clear_all else Res.string.settings_computers_bulk_select_all),
            onClick = { state.selected = if (allSelected) emptySet() else agentIds.toSet() },
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.Check) },
        )
        RaftButton(
            text = stringResource(Res.string.common_cancel),
            onClick = state::clearSelection,
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.X) },
        )
    } else {
        RaftButton(
            text = stringResource(Res.string.settings_computers_bulk_select),
            onClick = { state.selectionMode = true },
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Md,
            leadingIcon = { RaftComputersIcon(RaftLucideIconName.Check) },
        )
    }
}

/** The bulk Change model's result (web success / partial banners) above the agents. */
@Composable
internal fun RaftComputerAgentsBulkReceiptBanner(bulk: RaftComputerAgentsBulk) {
    val state = bulk.state
    when (val receipt = state.receipt) {
        null -> Unit
        is RaftComputerBulkReceipt.Success -> {
            LaunchedEffect(receipt) {
                delay(RAFT_BULK_SUCCESS_BANNER_MS)
                if (state.receipt === receipt) state.receipt = null
            }
            val target = raftComputerAgentIdentityLabel(receipt.target.runtime, receipt.target.model).orEmpty()
            val changed =
                if (receipt.changed == 1) {
                    stringResource(Res.string.settings_computers_bulk_model_success_one, receipt.changed, target)
                } else {
                    stringResource(Res.string.settings_computers_bulk_model_success_many, receipt.changed, target)
                }
            val skipped = if (receipt.skipped > 0) " " + stringResource(Res.string.settings_computers_bulk_model_success_skipped, receipt.skipped) else ""
            RaftBanner(
                description = changed + skipped,
                status = RaftBannerStatus.Success,
                size = RaftBannerSize.Sm,
                onDismiss = { state.receipt = null },
                dismissLabel = stringResource(Res.string.common_dismiss),
            )
        }
        is RaftComputerBulkReceipt.Partial ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftBanner(
                    description = stringResource(Res.string.settings_computers_bulk_model_partial, receipt.unfinished.size, receipt.total),
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    action = {
                        RaftButton(
                            text = stringResource(Res.string.common_dismiss),
                            onClick = { state.receipt = null },
                            variant = RaftButtonVariant.Ghost,
                            size = RaftButtonSize.Md,
                        )
                        RaftButton(
                            text = stringResource(Res.string.settings_computers_bulk_model_try_again),
                            onClick = bulk.onRetryModel,
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                        )
                    },
                )
                RaftTraceIdNote(receipt.traceId)
            }
    }
}

/**
 * Web's bulk toolbar, on the selection's `info-soft` wash: "N selected", then Start, Stop,
 * Restart / reset and Change model; a failure says how many didn't go through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RaftComputerAgentsBulkToolbar(bulk: RaftComputerAgentsBulk, agents: List<SettingsComputerAgentSnapshot>) {
    val state = bulk.state
    val chosen = agents.filter { it.id in state.selected }
    if (chosen.isEmpty()) return
    val offline = chosen.count { !bulk.running(it.id) }
    val online = chosen.size - offline
    val idle = state.busy == null
    Column(
        Modifier
            .fillMaxWidth()
            .background(RaftTheme.colors.infoSoft, RoundedCornerShape(RaftRadius.Control))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftText(
            stringResource(Res.string.settings_computers_bulk_selected_count, chosen.size),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftButton(
                text = stringResource(Res.string.settings_computers_bulk_start),
                onClick = bulk.onStart,
                variant = RaftButtonVariant.Default,
                enabled = idle && offline > 0 && bulk.computerOnline,
                loading = state.busy == RaftComputerBulkAction.Start,
                loadingLabel = stringResource(Res.string.settings_computers_bulk_starting),
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.Play) },
            )
            RaftButton(
                text = stringResource(Res.string.settings_computers_bulk_stop),
                onClick = { state.stopConfirmOpen = true },
                variant = RaftButtonVariant.Default,
                enabled = idle && online > 0,
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.Square) },
            )
            RaftButton(
                text = stringResource(Res.string.settings_computers_bulk_restart_reset),
                onClick = { state.resetOptionsOpen = true },
                variant = RaftButtonVariant.Default,
                enabled = idle && bulk.computerOnline,
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.RotateCcw) },
            )
            RaftButton(
                text = stringResource(Res.string.settings_computers_bulk_model_button),
                onClick = { state.modelRequest = RaftComputerBulkModelRequest(chosen.map { it.id }) },
                variant = RaftButtonVariant.Default,
                enabled = idle,
            )
        }
        // Web says why Start and Restart / reset are off in their tooltips; a phone has no hover.
        if (!bulk.computerOnline) {
            RaftText(
                stringResource(Res.string.settings_computers_bulk_must_be_online),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundMuted,
            )
        }
        state.failure?.let { failure ->
            RaftBanner(
                description =
                    if (failure.total == 1) {
                        stringResource(Res.string.settings_computers_bulk_action_failed_one, failure.failed, failure.total)
                    } else {
                        stringResource(Res.string.settings_computers_bulk_action_failed_many, failure.failed, failure.total)
                    },
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
            )
            RaftTraceIdNote(failure.traceId)
        }
    }
}

/**
 * An agent row in select mode (web `MachineAgentRow` with `selectionMode`): a leading checkbox
 * before the same avatar, name and runtime · model line; the whole row toggles it, and a selected
 * row has the selection's wash.
 */
@Composable
internal fun RaftComputerSelectableAgentRow(
    agent: SettingsComputerAgentSnapshot,
    live: RaftAgentStatusIndicatorState?,
    divider: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    RaftListRow(
        title = agent.name,
        onClick = onToggle,
        divider = divider,
        selected = selected,
        subtitle = raftComputerAgentIdentityLabel(agent.runtime, agent.model),
        leading = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftCheckboxIndicator(checked = selected)
                avatar()
            }
        },
        trailing = if (live != null) { { RaftStatusIndicator(color = live.color, size = 10.dp) } } else null,
    )
}

/** Web's bulk Restart / reset dialog: the three modes, the full reset's warning, and the confirm. */
@Composable
internal fun RaftComputerBulkResetDialog(state: RaftComputerAgentsBulkState, count: Int, onConfirm: () -> Unit) {
    val busy = state.busy != null
    val modes = listOf(RaftComputerBulkAction.Restart, RaftComputerBulkAction.ResetSession, RaftComputerBulkAction.ResetFull)
    val label: @Composable (RaftComputerBulkAction) -> String = { mode ->
        stringResource(
            when (mode) {
                RaftComputerBulkAction.ResetSession -> Res.string.settings_computers_bulk_reset_session
                RaftComputerBulkAction.ResetFull -> Res.string.settings_computers_bulk_full_reset
                else -> Res.string.settings_computers_bulk_restart
            }
        )
    }
    RaftDialog(
        onDismissRequest = { if (!busy) state.resetOptionsOpen = false },
        title =
            if (count == 1) {
                stringResource(Res.string.settings_computers_bulk_reset_title_one, count)
            } else {
                stringResource(Res.string.settings_computers_bulk_reset_title_many, count)
            },
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = { state.resetOptionsOpen = false },
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy,
            )
            // The confirm's colour says the consequence: a restart or new session recovers
            // (warning); a full reset cannot be undone (danger).
            RaftButton(
                text = label(state.resetAction),
                onClick = onConfirm,
                variant = if (state.resetAction == RaftComputerBulkAction.ResetFull) RaftButtonVariant.Danger else RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                loading = busy,
                loadingLabel = stringResource(Res.string.settings_computers_bulk_restarting),
                leadingIcon = { RaftComputersIcon(RaftLucideIconName.RotateCcw) },
            )
        },
    ) {
        RaftRadioGroup {
            modes.forEach { mode ->
                RaftRadioRow(
                    label = label(mode),
                    description =
                        stringResource(
                            when (mode) {
                                RaftComputerBulkAction.ResetSession -> Res.string.settings_computers_bulk_reset_session_description
                                RaftComputerBulkAction.ResetFull -> Res.string.settings_computers_bulk_full_reset_description
                                else -> Res.string.settings_computers_bulk_restart_description
                            }
                        ),
                    selected = state.resetAction == mode,
                    enabled = !busy,
                    onClick = { state.resetAction = mode },
                )
            }
        }
        if (state.resetAction == RaftComputerBulkAction.ResetFull) {
            RaftBanner(
                description = stringResource(Res.string.settings_computers_bulk_full_reset_warning),
                status = RaftBannerStatus.Destructive,
                size = RaftBannerSize.Sm,
            )
        }
        state.failure?.let { failure ->
            RaftBanner(
                description =
                    if (failure.total == 1) {
                        stringResource(Res.string.settings_computers_bulk_action_failed_one, failure.failed, failure.total)
                    } else {
                        stringResource(Res.string.settings_computers_bulk_action_failed_many, failure.failed, failure.total)
                    },
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
            )
            RaftTraceIdNote(failure.traceId)
        }
    }
}

/** Web's Stop agents confirm, counting the selected agents that are online. */
@Composable
internal fun RaftComputerBulkStopDialog(state: RaftComputerAgentsBulkState, onlineCount: Int, onConfirm: () -> Unit) {
    val busy = state.busy == RaftComputerBulkAction.Stop
    RaftDialog(
        onDismissRequest = { if (!busy) state.stopConfirmOpen = false },
        title = stringResource(Res.string.settings_computers_bulk_stop_title),
        description =
            if (onlineCount == 1) {
                stringResource(Res.string.settings_computers_bulk_stop_message_one, onlineCount)
            } else {
                stringResource(Res.string.settings_computers_bulk_stop_message_many, onlineCount)
            },
        dismissOnClickOutside = false,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = { state.stopConfirmOpen = false },
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy,
            )
            RaftButton(
                text = stringResource(Res.string.settings_computers_bulk_stop_confirm),
                onClick = onConfirm,
                variant = RaftButtonVariant.Warning,
                size = RaftButtonSize.Lg,
                loading = busy,
                loadingLabel = stringResource(Res.string.settings_computers_bulk_stopping),
            )
        },
    )
}

private enum class RaftBulkModelStep { Choose, Review, Applying }

private enum class RaftBulkApplyState { Saving, Saved, Failed }

/**
 * Web `BulkChangeModelDialog`: choose a runtime (this Computer's, those the bulk change can set),
 * a model and a reasoning effort; review what happens to each selected agent (changes, and skips
 * with the reason); then apply, one `PATCH /agents/:id` per agent, and leave a receipt on the page.
 * Running agents switch when next idle unless "Restart running agents now" is ticked.
 */
@Composable
internal fun RaftComputerBulkChangeModelDialog(
    request: RaftComputerBulkModelRequest,
    agents: List<SettingsComputerAgentSnapshot>,
    computerRuntimes: List<String>,
    running: (String) -> Boolean,
    actions: RaftComputerBulkAgentActions,
    scope: CoroutineScope,
    onOpenAgent: (SettingsComputerAgentSnapshot) -> Unit,
    onClose: () -> Unit,
    onFinished: (RaftComputerBulkReceipt) -> Unit,
) {
    val runtimeIds = remember(computerRuntimes) { computerRuntimes.filter(::isRaftBulkModelSelectableRuntime) }
    val retry = request.retry
    var step by remember(request) { mutableStateOf(RaftBulkModelStep.Choose) }
    var runtime by remember(request) {
        mutableStateOf(
            retry?.target?.runtime?.takeIf { it in runtimeIds }
                ?: agents.mapNotNull { it.runtime }.filter { it in runtimeIds }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?: runtimeIds.firstOrNull().orEmpty()
        )
    }
    var modelChoice by remember(request) { mutableStateOf(retry?.target?.model.orEmpty()) }
    var effortChoice by remember(request) { mutableStateOf(retry?.target?.reasoningEffort) }
    var restartNow by remember(request) { mutableStateOf(false) }
    val applyStates = remember(request) { mutableStateMapOf<String, RaftBulkApplyState>() }
    // The runtime's model list on this Computer: live when it answers, else nothing to pick.
    var catalog by remember(request) { mutableStateOf<RaftBulkModelCatalog?>(null) }
    var catalogLoading by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request, runtime) {
        catalog = null
        if (runtime.isBlank()) return@LaunchedEffect
        catalogLoading = true
        catalog = actions.models(runtime)
        catalogLoading = false
    }
    val models = catalog?.models.orEmpty()
    val model =
        models.firstOrNull { it.id == modelChoice }
            ?: models.firstOrNull { it.id == catalog?.defaultModel }
            ?: models.firstOrNull()
    val efforts = model?.let { raftBulkReasoningEffortOptions(runtime, it.supportedReasoningEfforts) }.orEmpty()
    val effort = model?.let { raftBulkReconcileReasoningEffort(runtime, it.supportedReasoningEfforts, it.defaultReasoningEffort, effortChoice) }
    val target = model?.let { RaftBulkModelTarget(runtime, it.id, effort) }
    val plan =
        target?.let {
            planRaftBulkModelChange(
                agents.map { agent -> RaftBulkPlanAgent(agent, running(agent.id), retry?.unfinished?.get(agent.id)) },
                it,
                restartNow,
            )
        }
    val agentById = remember(agents) { agents.associateBy { it.id } }

    fun apply() {
        val target0 = target ?: return
        val plan0 = plan ?: return
        val applying = plan0.rows.filter { it !is RaftBulkPlanRow.Skip }
        if (applying.isEmpty()) return
        step = RaftBulkModelStep.Applying
        applying.forEach { applyStates[it.agentId] = RaftBulkApplyState.Saving }
        scope.launch {
            val results =
                coroutineScope {
                    applying.map { row ->
                        async<Triple<String, RaftBulkUnfinishedPhase?, ScreenError?>> {
                            val agent = agentById.getValue(row.agentId)
                            val error =
                                when (row) {
                                    is RaftBulkPlanRow.RestartOnly -> actions.restart(row.agentId)
                                    is RaftBulkPlanRow.Change -> actions.changeModel(agent, target0, row.restarts)
                                    is RaftBulkPlanRow.Skip -> null
                                }
                            if (error == null) {
                                applyStates[row.agentId] = RaftBulkApplyState.Saved
                                Triple(row.agentId, null, null)
                            } else {
                                // The server saves before it restarts: read what it has now.
                                val phase =
                                    actions.saved(row.agentId)?.let { classifyRaftBulkUnfinished(it, target0) }
                                        ?: if (row is RaftBulkPlanRow.RestartOnly) RaftBulkUnfinishedPhase.RestartPending else RaftBulkUnfinishedPhase.SaveFailed
                                applyStates[row.agentId] = RaftBulkApplyState.Failed
                                Triple(row.agentId, phase, error)
                            }
                        }
                    }.awaitAll()
                }
            val failed = results.filter { it.second != null }
            val receipt =
                if (failed.isEmpty()) {
                    RaftComputerBulkReceipt.Success(target0, changed = results.size, skipped = plan0.rows.size - applying.size)
                } else {
                    RaftComputerBulkReceipt.Partial(
                        target0,
                        total = applying.size,
                        unfinished = failed.associate { it.first to it.second!! },
                        traceId = failed.firstNotNullOfOrNull { it.third?.traceId },
                    )
                }
            onFinished(receipt)
        }
    }

    val count = agents.size
    val title =
        when (step) {
            RaftBulkModelStep.Applying -> {
                val n = plan?.applyCount ?: 0
                if (n == 1) {
                    stringResource(Res.string.settings_computers_bulk_model_applying_title_one, n)
                } else {
                    stringResource(Res.string.settings_computers_bulk_model_applying_title_many, n)
                }
            }
            else ->
                if (count == 1) {
                    stringResource(Res.string.settings_computers_bulk_model_title_one, count)
                } else {
                    stringResource(Res.string.settings_computers_bulk_model_title_many, count)
                }
        }
    val chooseFooter: @Composable RowScope.() -> Unit = {
        RaftButton(
            text = stringResource(Res.string.common_cancel),
            onClick = onClose,
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Lg,
        )
        RaftButton(
            text = stringResource(Res.string.settings_computers_bulk_model_next),
            onClick = { step = RaftBulkModelStep.Review },
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg,
            enabled = target != null,
        )
    }
    val reviewFooter: @Composable RowScope.() -> Unit = {
        RaftButton(
            text = stringResource(Res.string.common_back),
            onClick = { step = RaftBulkModelStep.Choose },
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Lg,
        )
        Spacer(Modifier.weight(1f))
        val n = plan?.applyCount ?: 0
        RaftButton(
            text =
                if (n == 1) {
                    stringResource(Res.string.settings_computers_bulk_model_apply_one, n)
                } else {
                    stringResource(Res.string.settings_computers_bulk_model_apply_many, n)
                },
            onClick = ::apply,
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg,
            enabled = n > 0,
        )
    }
    val applying = step == RaftBulkModelStep.Applying
    RaftDialog(
        onDismissRequest = { if (!applying) onClose() },
        title = title,
        dismissOnClickOutside = false,
        showClose = !applying,
        closeLabel = stringResource(Res.string.common_close),
        footer =
            when (step) {
                RaftBulkModelStep.Choose -> chooseFooter
                RaftBulkModelStep.Review -> reviewFooter
                RaftBulkModelStep.Applying -> null
            },
    ) {
        when (step) {
            RaftBulkModelStep.Choose ->
                if (runtimeIds.isEmpty()) {
                    RaftText(
                        stringResource(Res.string.settings_computers_bulk_model_no_runtimes),
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                } else {
                    RaftField(
                        label = stringResource(Res.string.settings_computers_bulk_model_runtime),
                        description = stringResource(Res.string.settings_computers_bulk_model_account_hint, raftAgentRuntimeDisplayName(runtime)),
                    ) {
                        RaftSelect(
                            value = runtime,
                            options = runtimeIds,
                            onValueChange = { next ->
                                if (next != runtime) {
                                    runtime = next
                                    modelChoice = ""
                                }
                            },
                            optionLabel = ::raftAgentRuntimeDisplayName,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    RaftField(label = stringResource(Res.string.settings_computers_bulk_model_model)) {
                        RaftSelect(
                            value = model,
                            options = models,
                            onValueChange = { modelChoice = it.id },
                            optionLabel = { it.label },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = stringResource(Res.string.settings_computers_bulk_model_loading),
                            enabled = !catalogLoading,
                        )
                    }
                    if (efforts.isNotEmpty()) {
                        val defaultLabel = stringResource(Res.string.settings_computers_bulk_model_reasoning_default)
                        val effortLabels = efforts.associateWith { id -> membersReasoningEffortLabel(id)?.let { stringResource(it) } ?: id }
                        RaftField(label = stringResource(Res.string.settings_computers_bulk_model_reasoning)) {
                            RaftSelect<String?>(
                                value = effort,
                                options = listOf<String?>(null) + efforts,
                                onValueChange = { effortChoice = it },
                                optionLabel = { id -> id?.let { effortLabels[it] } ?: defaultLabel },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            RaftBulkModelStep.Review -> {
                val plan0 = plan ?: return@RaftDialog
                Column {
                    plan0.rows.forEach { row ->
                        val agent = agentById[row.agentId] ?: return@forEach
                        RaftBulkModelReviewRow(agent, row, runtime, target?.model.orEmpty(), onOpenAgent)
                    }
                }
                if (plan0.runningChangeCount > 0) {
                    RaftCheckboxRow(
                        label = stringResource(Res.string.settings_computers_bulk_model_restart_now),
                        checked = restartNow,
                        onCheckedChange = { restartNow = it },
                    )
                    if (!restartNow) {
                        val n = plan0.runningChangeCount
                        RaftText(
                            if (n == 1) {
                                stringResource(Res.string.settings_computers_bulk_model_idle_line_one, n)
                            } else {
                                stringResource(Res.string.settings_computers_bulk_model_idle_line_many, n)
                            },
                            style = RaftTheme.typography.footnote,
                            color = RaftTheme.colors.foregroundMuted,
                        )
                    }
                }
                if (plan0.restartCount > 0) {
                    val n = plan0.restartCount
                    RaftText(
                        if (n == 1) {
                            stringResource(Res.string.settings_computers_bulk_model_restart_line_one, n)
                        } else {
                            stringResource(Res.string.settings_computers_bulk_model_restart_line_many, n)
                        },
                        style = RaftTheme.typography.footnote,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                }
            }
            RaftBulkModelStep.Applying ->
                Column {
                    plan?.rows?.filter { it !is RaftBulkPlanRow.Skip }?.forEach { row ->
                        val agent = agentById[row.agentId] ?: return@forEach
                        RaftBulkModelApplyRow(agent, applyStates[row.agentId] ?: RaftBulkApplyState.Saving)
                    }
                }
        }
    }
}

/** A review line: the agent, what it moves from and to, or why it is skipped. */
@Composable
private fun RaftBulkModelReviewRow(
    agent: SettingsComputerAgentSnapshot,
    row: RaftBulkPlanRow,
    runtime: String,
    model: String,
    onOpenAgent: (SettingsComputerAgentSnapshot) -> Unit,
) {
    val skipped = row as? RaftBulkPlanRow.Skip
    val sameRuntime = agent.runtime == runtime
    val from = raftBulkModelLabel(agent.runtime, agent.model, withRuntime = !sameRuntime)
    val to = raftBulkModelLabel(runtime, model, withRuntime = !sameRuntime)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(
            agent.name,
            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = if (skipped != null) RaftTheme.colors.foregroundHint else RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RaftText(
            if (row is RaftBulkPlanRow.RestartOnly) to else "$from → $to",
            style = RaftTheme.typography.monoMeta.copy(textDecoration = if (skipped != null) TextDecoration.LineThrough else null),
            color = if (skipped != null) RaftTheme.colors.foregroundHint else RaftTheme.colors.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (skipped != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftText(
                    stringResource(
                        when (skipped.reason) {
                            RaftBulkSkipReason.CustomSettings -> Res.string.settings_computers_bulk_model_skip_custom
                            RaftBulkSkipReason.NoChange -> Res.string.settings_computers_bulk_model_skip_no_change
                        }
                    ),
                    style = RaftTheme.typography.caption,
                    color = RaftTheme.colors.warningStrong,
                )
                if (skipped.reason != RaftBulkSkipReason.NoChange) {
                    RaftTextLink(
                        text = stringResource(Res.string.common_edit),
                        onClick = { onOpenAgent(agent) },
                        variant = RaftTextLinkVariant.Strong,
                        style = RaftTheme.typography.caption,
                    )
                }
            }
        }
    }
}

@Composable
private fun RaftBulkModelApplyRow(agent: SettingsComputerAgentSnapshot, state: RaftBulkApplyState) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            agent.name,
            modifier = Modifier.weight(1f),
            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when (state) {
            RaftBulkApplyState.Saving -> RaftSpinner(size = RaftSpinnerSize.Xs)
            RaftBulkApplyState.Saved -> RaftComputersIcon(RaftLucideIconName.Check, size = 14.dp)
            RaftBulkApplyState.Failed -> RaftComputersIcon(RaftLucideIconName.X, size = 14.dp)
        }
        Spacer(Modifier.width(2.dp))
        RaftText(
            stringResource(
                when (state) {
                    RaftBulkApplyState.Saving -> Res.string.settings_computers_bulk_model_saving
                    RaftBulkApplyState.Saved -> Res.string.settings_computers_bulk_model_saved
                    RaftBulkApplyState.Failed -> Res.string.settings_computers_bulk_model_failed
                }
            ),
            style = RaftTheme.typography.caption,
            color = if (state == RaftBulkApplyState.Failed) RaftTheme.colors.warningStrong else RaftTheme.colors.foregroundMuted,
        )
    }
}

/** Web `formatBulkModelLabel`: "Model", or "Runtime · Model" when the runtime changes. */
private fun raftBulkModelLabel(runtime: String?, model: String?, withRuntime: Boolean): String =
    if (withRuntime) {
        raftComputerAgentIdentityLabel(runtime, model).orEmpty()
    } else {
        model?.let { getModelLabel(runtimeId = runtime.orEmpty(), modelId = it).ifBlank { it } }.orEmpty()
    }
